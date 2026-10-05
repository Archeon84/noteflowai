package com.noteflowai.app.whisper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.*

class WhisperQnnBridge(private val context: Context) {

    companion object {
        private const val TAG = "WhisperQnnBridge"
        private const val PREFS_NAME = "whisper_prefs"
        private const val KEY_CACHED_EP = "cached_whisper_ep"

        // Hallucination detection thresholds (tunable)
        private const val NO_SPEECH_PROB_THRESHOLD = 0.5f
        private const val AVG_LOGPROB_THRESHOLD = -1.5f
        private const val REPETITION_PENALTY_WINDOW = 20
        private const val REPETITION_PENALTY_FACTOR = 0.7f

        init {
            System.loadLibrary("whisper_jni")
        }
    }

    private external fun nativeSetupEnv(nativeLibDir: String): Boolean

    private val prefs: android.content.SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var initialized = false
    @Volatile private var isReleasing = false

    // Speculative decoding: draft model (smaller/faster)
    private var draftEncoderSession: OrtSession? = null
    private var draftDecoderSession: OrtSession? = null
    private var draftEncInputNames: List<String> = emptyList()
    private var draftDecInputNames: List<String> = emptyList()
    private var draftDecOutputNames: List<String> = emptyList()
    private var draftDecInputIdsName = "input_ids"
    private var draftDecEncHiddenName = "encoder_hidden_states"
    private var draftIsMergedDecoder = false
    private var draftKvInfos: List<KvInfo> = emptyList()
    var speculativeDecodingEnabled = false
        private set

    private var encInputNames: List<String> = emptyList()
    private var encOutputNames: List<String> = emptyList()
    private var decInputNames: List<String> = emptyList()
    private var decOutputNames: List<String> = emptyList()

    private var decInputIdsName = "input_ids"
    private var decEncHiddenName = "encoder_hidden_states"
    private var decUseCacheName = "use_cache_branch"

    private var isMergedDecoder = false

    private data class KvInfo(val inputName: String, val outputName: String, val shape: LongArray)

    private var kvInfos: List<KvInfo> = emptyList()

    // Encoder output cache: MD5(melData) -> (hiddenState, hiddenShape)
    // Avoids re-encoding identical mel spectrograms (e.g., overlapping chunks)
    private val encOutputCache = object : LinkedHashMap<String, Pair<FloatArray, LongArray>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<FloatArray, LongArray>>?): Boolean {
            return size > 8  // ~7.7MB for 8 entries of ~960KB each
        }
    }

    private fun createSessionOptions(cacheDir: File? = null, modelTag: String = ""): List<Pair<String, (OrtSession.SessionOptions) -> Unit>> {
        val availableProcessors = Runtime.getRuntime().availableProcessors()
        // NNAPI+HTP: NPU does the heavy lifting, fewer CPU threads needed
        // NNAPI+CPU_DISABLED: only NPU, minimal CPU threads
        // CPU: maximize CPU threads
        val nnapiHtpThreads = (availableProcessors / 2).coerceIn(2, 3)
        val nnapiThreads = (availableProcessors - 1).coerceIn(2, 4)
        val cpuThreads = (availableProcessors - 1).coerceIn(3, 6)
        Log.i(TAG, "Threads: NNAPI+HTP=$nnapiHtpThreads, NNAPI=$nnapiThreads, CPU=$cpuThreads (available=$availableProcessors)")

        return listOf(
            "NNAPI(FP16+CPU_DISABLED)" to { so ->
                so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                so.setIntraOpNumThreads(nnapiThreads)
                val flags = java.util.EnumSet.of(
                    ai.onnxruntime.providers.NNAPIFlags.USE_FP16,
                    ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED
                )
                so.addNnapi(flags)
            },
            "NNAPI(FP16+HTP)" to { so ->
                so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                so.setIntraOpNumThreads(nnapiHtpThreads)
                val flags = java.util.EnumSet.of(
                    ai.onnxruntime.providers.NNAPIFlags.USE_FP16
                )
                so.addNnapi(flags)
            },
            "NNAPI(FP16)" to { so ->
                so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                so.setIntraOpNumThreads(nnapiThreads)
                val flags = java.util.EnumSet.of(
                    ai.onnxruntime.providers.NNAPIFlags.USE_FP16
                )
                so.addNnapi(flags)
            },
            "NNAPI(default)" to { so ->
                so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                so.setIntraOpNumThreads(nnapiThreads)
                so.addNnapi()
            },
            "CPU" to { so ->
                so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                so.setIntraOpNumThreads(cpuThreads)
            }
        )
    }

    private var currentEpName = "unknown"

    fun initialize(encoderPath: String, decoderPath: String, nativeLibDir: String, cacheDir: File? = null, draftEncoderPath: String? = null, draftDecoderPath: String? = null): Boolean {
        Log.i(TAG, "Initializing ONNX bridge...")
        Log.i(TAG, "Encoder: $encoderPath")
        Log.i(TAG, "Decoder: $decoderPath")
        if (draftEncoderPath != null) Log.i(TAG, "Draft encoder: $draftEncoderPath")
        if (draftDecoderPath != null) Log.i(TAG, "Draft decoder: $draftDecoderPath")

        try {
            nativeSetupEnv(nativeLibDir)

            env = OrtEnvironment.getEnvironment()

            val allStrategies = createSessionOptions(cacheDir, "primary")
            val cachedEp = prefs.getString(KEY_CACHED_EP, null)
            Log.i(TAG, "Cached EP: ${cachedEp ?: "none"}")

            val strategies = if (cachedEp != null) {
                val cached = allStrategies.find { it.first == cachedEp }
                val rest = allStrategies.filter { it.first != cachedEp }
                if (cached != null) listOf(cached) + rest else allStrategies
            } else {
                allStrategies
            }

            for ((epName, configure) in strategies) {
                try {
                    Log.i(TAG, "Trying $epName...")
                    val so = OrtSession.SessionOptions()
                    configure(so)

                    Log.i(TAG, "Creating encoder session with $epName...")
                    encoderSession = env!!.createSession(encoderPath, so)
                    encInputNames = encoderSession!!.inputNames.toList()
                    encOutputNames = encoderSession!!.outputNames.toList()
                    Log.i(TAG, "Encoder OK. Inputs: $encInputNames, Outputs: $encOutputNames")

                    Log.i(TAG, "Creating decoder session with $epName...")
                    decoderSession = env!!.createSession(decoderPath, so)
                    decInputNames = decoderSession!!.inputNames.toList()
                    decOutputNames = decoderSession!!.outputNames.toList()
                    Log.i(TAG, "Decoder OK. Inputs: $decInputNames, Outputs: $decOutputNames")

                    currentEpName = epName
                    prefs.edit().putString(KEY_CACHED_EP, epName).apply()
                    Log.i(TAG, "SUCCESS: Using $epName (cached)")
                    // Log memory usage after successful load
                    val runtime = Runtime.getRuntime()
                    val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                    val maxMB = runtime.maxMemory() / (1024 * 1024)
                    Log.i(TAG, "Memory after Whisper load: ${usedMB}MB / ${maxMB}MB (${usedMB * 100 / maxMB}% used)")

                    // Warm-up: run dummy inference to pre-compile NNAPI delegate graphs
                    // First inference is 5-10x slower due to graph compilation
                    if (epName.contains("NNAPI")) {
                        Log.i(TAG, "Running warm-up inference (encoder + decoder)...")
                        val warmupStart = System.nanoTime()
                        // Warm-up encoder
                        val dummyInput = OnnxTensor.createTensor(
                            env!!, FloatBuffer.wrap(FloatArray(1 * 80 * 3000)),
                            longArrayOf(1, 80, 3000)
                        )
                        val warmupMap = mutableMapOf<String, OnnxTensor>()
                        warmupMap[encInputNames[0]] = dummyInput
                        val encWarmupResults = encoderSession!!.run(warmupMap)
                        val encHiddenForWarmup = (encWarmupResults.get(0) as OnnxTensor).floatBuffer.array()
                        val encHiddenShapeForWarmup = (encWarmupResults.get(0) as OnnxTensor).info.shape
                        encWarmupResults.close()
                        dummyInput.close()
                        Log.i(TAG, "Encoder warm-up done")

                        // Warm-up decoder with a single step using the encoder output
                        try {
                            val warmupDecInputIds = longArrayOf(50258, 50259, 50359, 50363)
                            val warmupDecInputTensor = OnnxTensor.createTensor(
                                env!!,
                                java.nio.LongBuffer.wrap(warmupDecInputIds),
                                longArrayOf(1, warmupDecInputIds.size.toLong())
                            )
                            val warmupEncHiddenTensor = OnnxTensor.createTensor(
                                env!!,
                                FloatBuffer.wrap(encHiddenForWarmup),
                                encHiddenShapeForWarmup
                            )
                            val warmupDecInputs = mutableMapOf<String, OnnxTensor>()
                            warmupDecInputs[decInputIdsName] = warmupDecInputTensor
                            warmupDecInputs[decEncHiddenName] = warmupEncHiddenTensor
                            if (decUseCacheName.isNotEmpty() && decInputNames.contains(decUseCacheName)) {
                                try {
                                    val useCacheBuf = java.nio.ByteBuffer.allocate(1).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    useCacheBuf.put(0.toByte())
                                    useCacheBuf.flip()
                                    warmupDecInputs[decUseCacheName] = OnnxTensor.createTensor(
                                        env!!, useCacheBuf, longArrayOf(1), ai.onnxruntime.OnnxJavaType.BOOL
                                    )
                                } catch (_: Exception) {}
                            }
                            decoderSession!!.run(warmupDecInputs).close()
                            Log.i(TAG, "Decoder warm-up done")
                        } catch (e: Exception) {
                            Log.w(TAG, "Decoder warm-up failed (non-fatal): ${e.message}")
                        }

                        val warmupElapsed = (System.nanoTime() - warmupStart) / 1_000_000.0
                        Log.i(TAG, "Warm-up done in ${String.format("%.0f", warmupElapsed)}ms")
                    }

                    break
                } catch (e: Exception) {
                    Log.w(TAG, "$epName failed: ${e.message}")
                    encoderSession?.close()
                    decoderSession?.close()
                    encoderSession = null
                    decoderSession = null
                }
            }

            if (encoderSession == null || decoderSession == null) {
                Log.e(TAG, "All EP strategies failed!")
                return false
            }

            for (name in decInputNames) {
                val info = decoderSession!!.inputInfo[name]
                Log.i(TAG, "  decoder input: $name shape=${info?.info}")
            }

            isMergedDecoder = decInputNames.any { it.contains("past_key_values") || it.contains("past.") }
            Log.i(TAG, "Merged decoder: $isMergedDecoder")

            if (isMergedDecoder) {
                decInputIdsName = decInputNames.first { it.contains("input_ids") }
                decEncHiddenName = decInputNames.first { it.contains("encoder_hidden") || it.contains("encoder_output") }
                decUseCacheName = decInputNames.firstOrNull { it.contains("use_cache") } ?: ""

                val pastInputNames = decInputNames.filter { it.contains("past.") || it.contains("past_key_values") }
                val presentOutputNames = decOutputNames.filter { it.startsWith("present.") || it.contains("present_key_values") }

                kvInfos = pastInputNames.mapNotNull { pastName ->
                    val suffix = pastName.removePrefix("past_key_values.").removePrefix("past.")
                    val matchingPresent = presentOutputNames.firstOrNull { p ->
                        val pSuffix = p.removePrefix("present_key_values.").removePrefix("present.")
                        pSuffix == suffix
                    }
                    if (matchingPresent != null) {
                        val shape = try {
                            val ti = decoderSession!!.inputInfo[pastName]?.info
                            if (ti != null) {
                                val shapeField = ti.javaClass.getMethod("getShape")
                                shapeField.invoke(ti) as LongArray
                            } else longArrayOf(1, 6, 0, 64)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not get shape for $pastName: ${e.message}")
                            longArrayOf(1, 6, 0, 64)
                        }
                        KvInfo(pastName, matchingPresent, shape)
                    } else null
                }
                Log.i(TAG, "KV cache entries: ${kvInfos.size}")
                for (kv in kvInfos) {
                    Log.i(TAG, "  ${kv.inputName} -> ${kv.outputName} shape=${kv.shape.contentToString()}")
                }
            } else {
                decInputIdsName = "input_ids"
                decEncHiddenName = decInputNames.firstOrNull { it.contains("encoder") } ?: "encoder_hidden_states"
            }

            initialized = true
            Log.i(TAG, "=== ONNX $currentEpName Ready ===")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Init failed: ${e.message}", e)
            release()
            return false
        }
    }

    fun loadDraftModel(cacheDir: File?, draftEncoderPath: String, draftDecoderPath: String) {
        if (!initialized || env == null) return
        try {
            Log.i(TAG, "Initializing draft model for speculative decoding...")
            val draftStrategies = createSessionOptions(cacheDir, "draft")
            for ((epName, configure) in draftStrategies) {
                try {
                    val dso = OrtSession.SessionOptions()
                    configure(dso)
                    draftEncoderSession = env!!.createSession(draftEncoderPath, dso)
                    draftEncInputNames = draftEncoderSession!!.inputNames.toList()
                    draftDecoderSession = env!!.createSession(draftDecoderPath, dso)
                    draftDecInputNames = draftDecoderSession!!.inputNames.toList()
                    draftDecOutputNames = draftDecoderSession!!.outputNames.toList()

                    draftIsMergedDecoder = draftDecInputNames.any { it.contains("past_key_values") || it.contains("past.") }
                    draftDecInputIdsName = draftDecInputNames.firstOrNull { it.contains("input_ids") } ?: "input_ids"
                    draftDecEncHiddenName = draftDecInputNames.firstOrNull { it.contains("encoder_hidden") || it.contains("encoder_output") } ?: "encoder_hidden_states"

                    if (draftIsMergedDecoder) {
                        val pastInputNames = draftDecInputNames.filter { it.contains("past.") || it.contains("past_key_values") }
                        val presentOutputNames = draftDecOutputNames.filter { it.startsWith("present.") || it.contains("present_key_values") }
                        draftKvInfos = pastInputNames.mapNotNull { pastName ->
                            val suffix = pastName.removePrefix("past_key_values.").removePrefix("past.")
                            val matchingPresent = presentOutputNames.firstOrNull { p ->
                                val pSuffix = p.removePrefix("present_key_values.").removePrefix("present.")
                                pSuffix == suffix
                            }
                            if (matchingPresent != null) {
                                val shape = try {
                                    val ti = draftDecoderSession!!.inputInfo[pastName]?.info
                                    if (ti != null) {
                                        val shapeField = ti.javaClass.getMethod("getShape")
                                        shapeField.invoke(ti) as LongArray
                                    } else longArrayOf(1, 4, 0, 64)
                                } catch (_: Exception) { longArrayOf(1, 4, 0, 64) }
                                KvInfo(pastName, matchingPresent, shape)
                            } else null
                        }
                    }

                    speculativeDecodingEnabled = true
                    Log.i(TAG, "=== Draft model ($epName) ready - Speculative decoding ENABLED ===")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Draft model $epName failed: ${e.message}")
                    draftEncoderSession?.close()
                    draftDecoderSession?.close()
                    draftEncoderSession = null
                    draftDecoderSession = null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Draft model init failed: ${e.message}")
        }
    }

    fun transcribe(melData: FloatArray, langToken: Int, audioDurationSec: Float = 30f): IntArray? {
        if (isReleasing || !initialized || env == null || encoderSession == null || decoderSession == null) return null

        // Use speculative decoding if draft model is available
        if (speculativeDecodingEnabled && draftEncoderSession != null && draftDecoderSession != null) {
            try {
                return transcribeSpeculative(melData, langToken, audioDurationSec)
            } catch (e: Exception) {
                Log.e(TAG, "Speculative decode failed, disabling: ${e.message}")
                speculativeDecodingEnabled = false
            }
        }

        return try {
            transcribeStandard(melData, langToken, audioDurationSec)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during transcription — model too large for device memory", e)
            System.gc()
            null
        }
    }

    private fun transcribeStandard(melData: FloatArray, langToken: Int, audioDurationSec: Float): IntArray? {

        try {
            val TOKEN_EOT = 50256
            val TOKEN_SOT = 50258
            val TOKEN_TRANSCRIBE = 50359
            val TOKEN_NOTIMESTAMPS = 50363
            // Cap max tokens: ~2 tokens per second of audio, minimum 30, maximum 128
            val MAX_TOKENS = minOf(128, maxOf(30, (audioDurationSec * 2).toInt()))

            Log.i(TAG, "Running encoder... melData size=${melData.size}, range=[${melData.min()}, ${melData.max()}]")
            Log.i(TAG, "Audio duration=${String.format("%.1f", audioDurationSec)}s, max_tokens=$MAX_TOKENS")

            // Check encoder output cache
            val melHash = md5FloatArray(melData)
            val cachedEnc = synchronized(encOutputCache) { encOutputCache[melHash] }
            var lastHiddenState: FloatArray? = null
            var lastHiddenShape: LongArray? = null

            if (cachedEnc != null) {
                lastHiddenState = cachedEnc.first
                lastHiddenShape = cachedEnc.second
                Log.i(TAG, "Encoder cache HIT: hidden_size=${lastHiddenState!!.size}")
            } else {
                val encInput = OnnxTensor.createTensor(
                    env!!,
                    FloatBuffer.wrap(melData),
                    longArrayOf(1, 80, 3000)
                )

                val encInputMap = mutableMapOf<String, OnnxTensor>()
                encInputMap[encInputNames[0]] = encInput
                val encStartTime = System.nanoTime()
                val encResults = encoderSession!!.run(encInputMap)
                val encElapsed = (System.nanoTime() - encStartTime) / 1_000_000.0
                Log.i(TAG, "Encoder inference: ${String.format("%.1f", encElapsed)}ms")

                for (i in 0 until encResults.size()) {
                    val name = encOutputNames[i]
                    val tensor = encResults.get(i) as OnnxTensor
                    lastHiddenState = tensor.floatBuffer.array()
                    lastHiddenShape = tensor.info.shape
                    val mean = lastHiddenState!!.average()
                    val absMax = lastHiddenState!!.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
                    Log.i(TAG, "  enc output[$i] '$name': shape=${lastHiddenShape!!.contentToString()} size=${lastHiddenState!!.size} mean=$mean absMax=$absMax")
                }
                encInput.close()
                encResults.close()

                // Store in cache
                if (lastHiddenState != null && lastHiddenShape != null) {
                    synchronized(encOutputCache) {
                        encOutputCache[melHash] = lastHiddenState!! to lastHiddenShape!!
                    }
                    Log.i(TAG, "Encoder output cached (melHash=$melHash)")
                }
            }

            Log.i(TAG, "Encoder OK: hidden_size=${lastHiddenState?.size}")

            if (isMergedDecoder) {
                return transcribeMerged(TOKEN_SOT, langToken, TOKEN_TRANSCRIBE, TOKEN_NOTIMESTAMPS, TOKEN_EOT, MAX_TOKENS, lastHiddenState!!, lastHiddenShape!!)
            } else {
                return transcribeNonMerged(TOKEN_SOT, langToken, TOKEN_TRANSCRIBE, TOKEN_EOT, MAX_TOKENS, lastHiddenState!!, lastHiddenShape!!)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe failed: ${e.message}", e)
            return null
        }
    }

    private fun transcribeMerged(
        TOKEN_SOT: Int, langToken: Int, TOKEN_TRANSCRIBE: Int, TOKEN_NOTIMESTAMPS: Int, TOKEN_EOT: Int, MAX_TOKENS: Int,
        encHidden: FloatArray, encShape: LongArray
    ): IntArray? {
        try {
        // Pre-allocate input buffer to avoid allocation per decoder step
        val inputBuffer = LongArray(MAX_TOKENS + 8)
        inputBuffer[0] = TOKEN_SOT.toLong(); inputBuffer[1] = langToken.toLong()
        inputBuffer[2] = TOKEN_TRANSCRIBE.toLong(); inputBuffer[3] = TOKEN_NOTIMESTAMPS.toLong()
        var inputLen = 4
        val allTokens = mutableListOf<Int>()

        // Whisper hallucination detection
        val logProbs = mutableListOf<Float>()
        val TOKEN_NOSPEECH = 50361

        // Pre-create encoder hidden state tensor (reused every decoder step)
        val encHiddenTensor = OnnxTensor.createTensor(
            env!!, FloatBuffer.wrap(encHidden), encShape
        )
        val decodeStartTime = System.nanoTime()
        val MAX_DECODE_MS = 90_000L

        for (step in 0 until MAX_TOKENS) {
            val stepTensors = mutableListOf<OnnxTensor>()

            val inputIdsTensor = OnnxTensor.createTensor(
                env!!,
                java.nio.LongBuffer.wrap(inputBuffer, 0, inputLen),
                longArrayOf(1, inputLen.toLong())
            )
            stepTensors.add(inputIdsTensor)

            // All KV inputs empty — model recomputes from scratch each step
            // (use_cache_branch is hardcoded False in traced ONNX model, KV caching not supported)
            val kvTensors = mutableListOf<OnnxTensor>()
            for (kv in kvInfos) {
                val numHeads = kv.shape.getOrElse(1) { 12L }.let { if (it <= 0) 12L else it }
                val headDim = kv.shape.getOrElse(3) { 64L }.let { if (it <= 0) 64L else it }
                kvTensors.add(OnnxTensor.createTensor(env!!, FloatBuffer.wrap(FloatArray(0)), longArrayOf(1L, numHeads, 0L, headDim)))
            }
            stepTensors.addAll(kvTensors)

            val inputs = mutableMapOf<String, OnnxTensor>()
            inputs[decInputIdsName] = inputIdsTensor
            inputs[decEncHiddenName] = encHiddenTensor
            for ((idx, kv) in kvInfos.withIndex()) {
                inputs[kv.inputName] = kvTensors[idx]
            }

            var useCacheTensor: OnnxTensor? = null
            if (decUseCacheName.isNotEmpty() && decInputNames.contains(decUseCacheName)) {
                try {
                    val boolBuffer = java.nio.ByteBuffer.allocate(1).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    boolBuffer.put(0.toByte())
                    boolBuffer.flip()
                    useCacheTensor = OnnxTensor.createTensor(env!!, boolBuffer, longArrayOf(1), ai.onnxruntime.OnnxJavaType.BOOL)
                    stepTensors.add(useCacheTensor)
                    inputs[decUseCacheName] = useCacheTensor
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set use_cache_branch: ${e.message}")
                }
            }

            Log.i(TAG, "Decoder step $step: ${inputs.size} inputs, seq_len=$inputLen")

            val elapsedMs = (System.nanoTime() - decodeStartTime) / 1_000_000L
            if (elapsedMs > MAX_DECODE_MS) {
                Log.i(TAG, "Time limit reached (${elapsedMs}ms > ${MAX_DECODE_MS}ms) at step $step, stopping")
                stepTensors.forEach { it.close() }
                break
            }

            val decStartTime = System.nanoTime()
            val results = decoderSession!!.run(inputs)
            val decElapsed = (System.nanoTime() - decStartTime) / 1_000_000.0
            if (step == 0) Log.i(TAG, "Decoder step 0 inference: ${String.format("%.1f", decElapsed)}ms")
            else if (step % 10 == 0) Log.i(TAG, "Decoder step $step: ${String.format("%.1f", decElapsed)}ms")

            val logitsTensor = results.get(0) as OnnxTensor
            val logits = logitsTensor.floatBuffer.array()
            val shape = logitsTensor.info.shape
            val vocabSize = shape.last().toInt()
            val seqLen = if (shape.size >= 2) shape[shape.size - 2].toInt() else 1

            val offset = (seqLen - 1) * vocabSize

            // Repetition penalty: reduce logits of recently-used tokens to break loops
            if (allTokens.size >= 4) {
                val recentWindow = allTokens.takeLast(REPETITION_PENALTY_WINDOW)
                for (recentToken in recentWindow) {
                    if (recentToken in 0 until vocabSize) {
                        logits[offset + recentToken] *= REPETITION_PENALTY_FACTOR
                    }
                }
            }

            // no_speech_prob check at step 0: probability of <|nospeech|> token
            // Use proper softmax over top-K tokens + no_speech token for accurate probability
            if (step == 0 && TOKEN_NOSPEECH < vocabSize) {
                val nospeechLogit = logits[offset + TOKEN_NOSPEECH]
                // Collect top-10 logits + no_speech logit for softmax denominator
                val topLogits = mutableListOf(nospeechLogit)
                for (i in 0 until vocabSize) {
                    if (i != TOKEN_NOSPEECH && topLogits.size < 11) {
                        topLogits.add(logits[offset + i])
                    }
                }
                // Sort and keep only top-10 non-nospeech for denominator
                val sortedTop = topLogits.filter { it != nospeechLogit }.sorted().take(10)
                val maxForStability = maxOf(nospeechLogit, sortedTop.maxOrNull() ?: nospeechLogit)
                var expSum = kotlin.math.exp((nospeechLogit - maxForStability).toDouble()).toFloat()
                for (v in sortedTop) {
                    expSum += kotlin.math.exp((v - maxForStability).toDouble()).toFloat()
                }
                val nospeechProb = kotlin.math.exp((nospeechLogit - maxForStability).toDouble()).toFloat() / expSum
                Log.i(TAG, "no_speech_prob = ${String.format("%.4f", nospeechProb)} (logit=${String.format("%.3f", nospeechLogit)}, top10_avg=${String.format("%.3f", sortedTop.average())})")
                if (nospeechProb > NO_SPEECH_PROB_THRESHOLD) {
                    Log.i(TAG, "No speech detected (prob=$nospeechProb > $NO_SPEECH_PROB_THRESHOLD), returning empty")
                    stepTensors.forEach { it.close() }
                    results.close()
                    encHiddenTensor.close()
                    return intArrayOf()
                }
            }

            // Suppress special tokens (>= 50257)
            for (i in 50257 until vocabSize) {
                logits[offset + i] = -1e9f
            }

            var best = 0
            for (i in 1 until vocabSize) {
                if (logits[offset + i] > logits[offset + best]) best = i
            }

            val bestLogit = logits[offset + best]

            // Log probability of selected token
            var expSum = 0f
            for (i in 0 until vocabSize) {
                expSum += exp((logits[offset + i] - bestLogit).toDouble()).toFloat()
            }
            val logProb = -ln(expSum.toDouble()).toFloat()
            logProbs.add(logProb)

            allTokens.add(best)
            inputBuffer[inputLen++] = best.toLong()

            if (best == TOKEN_EOT) {
                Log.i(TAG, "Got EOT at step $step")
                stepTensors.forEach { it.close() }
                results.close()
                break
            }

            // Repetition detection: check for both long-block and short-phrase repeats
            var shouldStop = false
            if (step >= 4) {
                // Short-phrase repeat: e.g. token A B A B A B (phrase_len=2, repeats>=2)
                for (phraseLen in 1..(allTokens.size / 3).coerceAtMost(8)) {
                    val phrase = allTokens.takeLast(phraseLen)
                    var repeats = 0
                    var checkIdx = allTokens.size - phraseLen
                    while (checkIdx - phraseLen >= 0 && repeats < 5) {
                        if (allTokens.subList(checkIdx - phraseLen, checkIdx) == phrase) {
                            repeats++
                            checkIdx -= phraseLen
                        } else break
                    }
                    if (repeats >= 2) {
                        Log.i(TAG, "Short-phrase repetition: phrase_len=$phraseLen repeats=$repeats at step $step, stopping")
                        shouldStop = true
                        break
                    }
                }

                // Long-block repeat: 10-token blocks
                if (!shouldStop && step >= 10) {
                    val recent = allTokens.takeLast(10)
                    var repetitions = 0
                    var checkStart = allTokens.size - 20
                    while (checkStart >= 0 && checkStart + 10 <= allTokens.size - 10) {
                        if (allTokens.subList(checkStart, checkStart + 10) == recent) {
                            repetitions++
                        }
                        checkStart -= 10
                    }
                    if (repetitions >= 2) {
                        Log.i(TAG, "Long-block repetition detected at step $step ($repetitions repeats), stopping")
                        shouldStop = true
                    }
                }
            }
            if (shouldStop) {
                stepTensors.forEach { it.close() }
                results.close()
                break
            }

            // Avg log-prob threshold: Whisper's standard hallucination detector
            // Check every 5 tokens after the first 15 generated tokens
            if (allTokens.size >= 15 && allTokens.size % 5 == 0) {
                val recentLogProbs = logProbs.takeLast(15)
                val avgLogProb = recentLogProbs.average().toFloat()
                Log.i(TAG, "avg_logprob (last 15) = ${String.format("%.3f", avgLogProb)}")
                if (avgLogProb < AVG_LOGPROB_THRESHOLD) {
                    Log.i(TAG, "Avg log-prob too low ($avgLogProb < $AVG_LOGPROB_THRESHOLD), likely hallucination, stopping")
                    stepTensors.forEach { it.close() }
                    results.close()
                    break
                }
            }

            if (step < 5) {
                val top5 = logits.slice(offset until offset + vocabSize).mapIndexed { idx, v -> idx to v }
                    .sortedByDescending { it.second }.take(5)
                Log.i(TAG, "  step $step: token=$best logprob=${String.format("%.3f", logProb)} top5=${top5.map { "${it.first}(${String.format("%.3f", it.second)})" }}")
            }

            stepTensors.forEach { it.close() }
            results.close()

            // Yield every 10 steps to prevent thread starvation on long recordings
            if (step % 10 == 0) {
                Thread.sleep(1)
            }
        }

        val totalDecodeMs = (System.nanoTime() - decodeStartTime) / 1_000_000L
        Log.i(TAG, "Done: ${allTokens.size} tokens, total decode: ${totalDecodeMs}ms, avg ${if (allTokens.isNotEmpty()) totalDecodeMs / allTokens.size else 0}ms/token")
        encHiddenTensor.close()
        return allTokens.toIntArray()
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM in merged decoder — returning partial tokens", e)
            System.gc()
            return null
        }
    }

    private fun transcribeNonMerged(
        TOKEN_SOT: Int, langToken: Int, TOKEN_TRANSCRIBE: Int, TOKEN_EOT: Int, MAX_TOKENS: Int,
        encHidden: FloatArray, encShape: LongArray
    ): IntArray? {
        try {
        val inputBuffer = LongArray(MAX_TOKENS + 8)
        inputBuffer[0] = TOKEN_SOT.toLong(); inputBuffer[1] = langToken.toLong()
        inputBuffer[2] = TOKEN_TRANSCRIBE.toLong()
        var inputLen = 3
        val allTokens = mutableListOf<Int>()
        val decodeStartTime = System.nanoTime()
        val MAX_DECODE_MS = 90_000L
        val runtime = Runtime.getRuntime()

        for (step in 0 until MAX_TOKENS) {
            // Time limit: prevent runaway decode from freezing the app
            val elapsedMs = (System.nanoTime() - decodeStartTime) / 1_000_000L
            if (elapsedMs > MAX_DECODE_MS) {
                Log.i(TAG, "NonMerged time limit reached (${elapsedMs}ms > ${MAX_DECODE_MS}ms) at step $step, stopping")
                break
            }

            // Memory check every 10 steps to prevent OOM on long recordings
            if (step % 10 == 0 && step > 0) {
                runtime.gc()
                val freeMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
                if (freeMB < 50) {
                    Log.w(TAG, "NonMerged low memory at step $step (${freeMB}MB free), stopping")
                    break
                }
            }
            val inputs = mutableMapOf<String, OnnxTensor>()

            for (name in decInputNames) {
                when {
                    name == "input_ids" || name.contains("input_ids") -> {
                        inputs[name] = OnnxTensor.createTensor(
                            env!!,
                            java.nio.LongBuffer.wrap(inputBuffer, 0, inputLen),
                            longArrayOf(1, inputLen.toLong())
                        )
                    }
                    name == "encoder_hidden_states" || name.contains("encoder_output") -> {
                        inputs[name] = OnnxTensor.createTensor(
                            env!!,
                            FloatBuffer.wrap(encHidden),
                            encShape
                        )
                    }
                }
            }

            if (inputs.isEmpty()) {
                Log.e(TAG, "No inputs mapped! decInputNames=$decInputNames")
                break
            }

            Log.i(TAG, "Decoder step $step: ${inputs.size} inputs")
            val results = decoderSession!!.run(inputs)
            val logitsTensor = results.get(0) as OnnxTensor
            val logits = logitsTensor.floatBuffer.array()
            val shape = logitsTensor.info.shape
            val vocabSize = shape.last().toInt()
            val seqLen = if (shape.size >= 2) shape[shape.size - 2].toInt() else 1

            val offset = (seqLen - 1) * vocabSize

            // Apply logits suppression: suppress special tokens (>= 50257) after initial prompt
            for (i in 50257 until vocabSize) {
                logits[offset + i] = -1e9f
            }

            var best = 0
            for (i in 1 until vocabSize) {
                if (logits[offset + i] > logits[offset + best]) best = i
            }

            allTokens.add(best)
            if (best != TOKEN_EOT) inputBuffer[inputLen++] = best.toLong()
            if (inputLen > 448) {
                Log.w(TAG, "Input sequence too long, stopping")
                break
            }

            if (step < 5) {
                val top5 = logits.slice(offset until offset + vocabSize).mapIndexed { idx, v -> idx to v }
                    .sortedByDescending { it.second }.take(5)
                Log.i(TAG, "  step $step: token=$best top5=${top5.map { "${it.first}(${String.format("%.3f", it.second)})" }}")
            }

            inputs.values.forEach { it.close() }
            results.close()

            // Yield every 10 steps to prevent thread starvation on long recordings
            if (step % 10 == 0) {
                Thread.sleep(1)
            }

            if (best == TOKEN_EOT) break
        }

        Log.i(TAG, "Done: ${allTokens.size} tokens")
        return allTokens.toIntArray()
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM in non-merged decoder — returning partial tokens", e)
            System.gc()
            return null
        }
    }

    private fun transcribeSpeculative(melData: FloatArray, langToken: Int, audioDurationSec: Float): IntArray? {
        try {
            val TOKEN_EOT = 50256
            val TOKEN_SOT = 50258
            val TOKEN_TRANSCRIBE = 50359
            val TOKEN_NOTIMESTAMPS = 50363
            val MAX_TOKENS = minOf(444, maxOf(50, (audioDurationSec * 3).toInt()))
            val SPEC_K = 4  // Number of tokens to speculate ahead

            Log.i(TAG, "=== Speculative Decoding (K=$SPEC_K) ===")
            val totalStart = System.nanoTime()

            // 1. Run draft encoder
            val draftMelTensor = OnnxTensor.createTensor(
                env!!, FloatBuffer.wrap(melData), longArrayOf(1, 80, 3000)
            )
            val draftEncStart = System.nanoTime()
            val draftEncInputMap = mutableMapOf<String, OnnxTensor>()
            draftEncInputMap[draftEncInputNames[0]] = draftMelTensor
            val draftEncResults = draftEncoderSession!!.run(draftEncInputMap)
            var draftEncHidden: FloatArray? = null
            var draftEncShape: LongArray? = null
            for (i in 0 until draftEncResults.size()) {
                val tensor = draftEncResults.get(i) as OnnxTensor
                draftEncHidden = tensor.floatBuffer.array()
                draftEncShape = tensor.info.shape
            }
            draftMelTensor.close()
            draftEncResults.close()
            val draftEncMs = (System.nanoTime() - draftEncStart) / 1_000_000.0

            // 2. Run primary encoder
            val primMelTensor = OnnxTensor.createTensor(
                env!!, FloatBuffer.wrap(melData), longArrayOf(1, 80, 3000)
            )
            val primEncStart = System.nanoTime()
            val primEncInputMap = mutableMapOf<String, OnnxTensor>()
            primEncInputMap[encInputNames[0]] = primMelTensor
            val primEncResults = encoderSession!!.run(primEncInputMap)
            var primEncHidden: FloatArray? = null
            var primEncShape: LongArray? = null
            for (i in 0 until primEncResults.size()) {
                val tensor = primEncResults.get(i) as OnnxTensor
                primEncHidden = tensor.floatBuffer.array()
                primEncShape = tensor.info.shape
            }
            primMelTensor.close()
            primEncResults.close()
            val primEncMs = (System.nanoTime() - primEncStart) / 1_000_000.0
            Log.i(TAG, "Draft encoder: ${String.format("%.0f", draftEncMs)}ms, Primary encoder: ${String.format("%.0f", primEncMs)}ms")

            // 3. Speculative decoding loop
            val allTokens = mutableListOf<Int>()
            var primInputIds = mutableListOf(TOKEN_SOT.toLong(), langToken.toLong(), TOKEN_TRANSCRIBE.toLong(), TOKEN_NOTIMESTAMPS.toLong())
            var draftTokenCount = 0
            var acceptedCount = 0
            var rejectedCount = 0
            var step = 0

            while (step < MAX_TOKENS) {
                // Phase A: Draft model generates K tokens
                val draftTokens = mutableListOf<Int>()
                var draftInputIds = primInputIds.toMutableList()

                for (k in 0 until SPEC_K) {
                    val draftInputs = mutableMapOf<String, OnnxTensor>()
                    draftInputs[draftDecInputIdsName] = OnnxTensor.createTensor(
                        env!!, java.nio.LongBuffer.wrap(draftInputIds.toLongArray()),
                        longArrayOf(1, draftInputIds.size.toLong())
                    )
                    draftInputs[draftDecEncHiddenName] = OnnxTensor.createTensor(
                        env!!, FloatBuffer.wrap(draftEncHidden!!), draftEncShape!!
                    )

                    if (draftIsMergedDecoder) {
                        for (kv in draftKvInfos) {
                            val resolvedShape = longArrayOf(1L, kv.shape.getOrElse(1) { 4L }.let { if (it <= 0) 4L else it }, 0L, kv.shape.getOrElse(3) { 64L }.let { if (it <= 0) 64L else it })
                            draftInputs[kv.inputName] = OnnxTensor.createTensor(env!!, FloatBuffer.wrap(FloatArray(0)), resolvedShape)
                        }
                    }

                    val draftResults = draftDecoderSession!!.run(draftInputs)
                    val draftLogitsTensor = draftResults.get(0) as OnnxTensor
                    val draftLogits = draftLogitsTensor.floatBuffer.array()
                    val draftShape = draftLogitsTensor.info.shape
                    val draftVocabSize = draftShape.last().toInt()
                    val draftSeqLen = if (draftShape.size >= 2) draftShape[draftShape.size - 2].toInt() else 1
                    val draftOffset = (draftSeqLen - 1) * draftVocabSize

                    // Suppress special tokens
                    for (i in 50257 until draftVocabSize) { draftLogits[draftOffset + i] = -1e9f }

                    var bestDraft = 0
                    for (i in 1 until draftVocabSize) {
                        if (draftLogits[draftOffset + i] > draftLogits[draftOffset + bestDraft]) bestDraft = i
                    }

                    draftInputs.values.forEach { it.close() }
                    draftResults.close()

                    if (bestDraft == TOKEN_EOT) break
                    draftTokens.add(bestDraft)
                    draftInputIds.add(bestDraft.toLong())
                    draftTokenCount++
                }

                if (draftTokens.isEmpty()) break

                // Phase B: Primary model verifies all draft tokens in ONE pass
                val verifyInputIds = primInputIds.toMutableList()
                verifyInputIds.addAll(draftTokens.map { it.toLong() })

                val primInputs = mutableMapOf<String, OnnxTensor>()
                primInputs[decInputIdsName] = OnnxTensor.createTensor(
                    env!!, java.nio.LongBuffer.wrap(verifyInputIds.toLongArray()),
                    longArrayOf(1, verifyInputIds.size.toLong())
                )
                primInputs[decEncHiddenName] = OnnxTensor.createTensor(
                    env!!, FloatBuffer.wrap(primEncHidden!!), primEncShape!!
                )

                if (isMergedDecoder) {
                    for (kv in kvInfos) {
                        val resolvedShape = longArrayOf(1L, kv.shape.getOrElse(1) { 6L }.let { if (it <= 0) 6L else it }, 0L, kv.shape.getOrElse(3) { 64L }.let { if (it <= 0) 64L else it })
                        primInputs[kv.inputName] = OnnxTensor.createTensor(env!!, FloatBuffer.wrap(FloatArray(0)), resolvedShape)
                    }
                }

                val primResults = decoderSession!!.run(primInputs)
                val primLogitsTensor = primResults.get(0) as OnnxTensor
                val primLogits = primLogitsTensor.floatBuffer.array()
                val primVocabSize = primLogitsTensor.info.shape.last().toInt()
                val primSeqLen = if (primLogitsTensor.info.shape.size >= 2) primLogitsTensor.info.shape[primLogitsTensor.info.shape.size - 2].toInt() else 1

                // Compare primary vs draft at each position
                var acceptCount = 0
                for (k in draftTokens.indices) {
                    val posOffset = (primSeqLen - draftTokens.size + k) * primVocabSize
                    // Suppress special tokens
                    for (i in 50257 until primVocabSize) { primLogits[posOffset + i] = -1e9f }

                    var bestPrim = 0
                    for (i in 1 until primVocabSize) {
                        if (primLogits[posOffset + i] > primLogits[posOffset + bestPrim]) bestPrim = i
                    }

                    if (bestPrim == draftTokens[k]) {
                        allTokens.add(bestPrim)
                        primInputIds.add(bestPrim.toLong())
                        acceptCount++
                        acceptedCount++
                    } else {
                        // Mismatch: accept primary's token and discard rest
                        allTokens.add(bestPrim)
                        primInputIds.add(bestPrim.toLong())
                        rejectedCount++
                        break
                    }
                }

                // If all K draft tokens accepted, also sample one more from primary
                if (acceptCount == draftTokens.size) {
                    val lastOffset = (primSeqLen - 1) * primVocabSize
                    for (i in 50257 until primVocabSize) { primLogits[lastOffset + i] = -1e9f }
                    var bonus = 0
                    for (i in 1 until primVocabSize) {
                        if (primLogits[lastOffset + i] > primLogits[lastOffset + bonus]) bonus = i
                    }
                    if (bonus != TOKEN_EOT) {
                        allTokens.add(bonus)
                        primInputIds.add(bonus.toLong())
                    }
                }

                primInputs.values.forEach { it.close() }
                primResults.close()

                step += acceptCount + 1

                if (step % 10 == 0) {
                    Log.i(TAG, "Spec step ~$step: accepted=$acceptedCount rejected=$rejectedCount total=${allTokens.size}")
                }

                // Check for EOT
                if (allTokens.lastOrNull() == TOKEN_EOT) break
            }

            val totalMs = (System.nanoTime() - totalStart) / 1_000_000.0
            val acceptanceRate = if (draftTokenCount > 0) (acceptedCount.toDouble() / draftTokenCount * 100) else 0.0
            Log.i(TAG, "=== Speculative Done: ${allTokens.size} tokens, ${String.format("%.0f", totalMs)}ms ===")
            Log.i(TAG, "Draft tokens: $draftTokenCount, Accepted: $acceptedCount, Rejected: $rejectedCount, Acceptance rate: ${String.format("%.1f", acceptanceRate)}%")

            return allTokens.toIntArray()
        } catch (e: Exception) {
            Log.e(TAG, "Speculative decode failed: ${e.message}")
            throw e
        }
    }

    fun release() {
        // Signal releasing BEFORE touching any fields so concurrent
        // transcribe() calls bail out immediately instead of racing.
        isReleasing = true
        // Capture fields locally so we close exactly what we saw, even if
        // another thread nulls them in between.
        val enc = encoderSession
        val dec = decoderSession
        val dEnc = draftEncoderSession
        val dDec = draftDecoderSession
        val e = env
        try {
            enc?.close()
            dec?.close()
            dEnc?.close()
            dDec?.close()
            e?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Release error", e)
        }
        encoderSession = null
        decoderSession = null
        draftEncoderSession = null
        draftDecoderSession = null
        env = null
        initialized = false
        speculativeDecodingEnabled = false
        Log.i(TAG, "Released")
    }

    private fun md5FloatArray(data: FloatArray): String {
        val bytes = ByteArray(data.size * 4)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(data)
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
