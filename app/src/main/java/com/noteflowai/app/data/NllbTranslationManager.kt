package com.noteflowai.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.LinkedHashMap

class NllbTranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "NllbTranslation"

        private const val MODEL_BASE_URL =
            "https://huggingface.co/forkjoin-ai/nllb-200-distilled-1.3B-onnx/resolve/main"

        private const val ENCODER_URL = "$MODEL_BASE_URL/encoder_model_quantized.onnx"
        private const val DECODER_URL = "$MODEL_BASE_URL/decoder_model_quantized.onnx"
        private const val TOKENIZER_URL = "$MODEL_BASE_URL/tokenizer.json"

        fun languageCodeToNllb(code: String): String {
            return com.noteflowai.app.data.nllbCodeFor(
                com.noteflowai.app.data.normalizeLangCode(code)
            )
        }

        // Token IDs from HuggingFace tokenizer.json (NOT protobuf order!)
        private const val BOS_ID = 0   // <s>
        private const val PAD_ID = 1   // <pad>
        private const val EOS_ID = 2   // </s>
        private const val UNK_ID = 3   // <unk>
    }

    private val modelDir = File(context.filesDir, "models/nllb")
    private val encoderFile = File(modelDir, "encoder_model_quantized.onnx")
    private val decoderFile = File(modelDir, "decoder_model_quantized.onnx")
    private val tokenizerFile = File(modelDir, "tokenizer.json")

    private val prefs: SharedPreferences = context.getSharedPreferences("nllb_prefs", Context.MODE_PRIVATE)

    private val _isDownloaded = MutableStateFlow(checkModelExists())
    val isDownloaded: StateFlow<Boolean> = _isDownloaded.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private var ortEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null
    private var tokenizer: SpTokenizer? = null

    // Translation memory cache: key = md5(srcLang+tgtLang+text), value = translation
    // LRU: 1000 entries max, evicts oldest on overflow
    private val translationCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > 1000
            }
        }
    )

    fun checkModelExists(): Boolean {
        val encOk = encoderFile.exists() && encoderFile.length() > 1000
        val decOk = decoderFile.exists() && decoderFile.length() > 1000
        val tokOk = tokenizerFile.exists() && tokenizerFile.length() > 1000
        if (!encOk || !decOk || !tokOk) {
            // Migrate from old sentencepiece.bpe.model to new tokenizer.json:
            // If old tokenizer exists but new one doesn't, clean up so we re-download.
            val oldSp = File(modelDir, "sentencepiece.bpe.model")
            if (oldSp.exists() && !tokenizerFile.exists()) {
                Log.i(TAG, "Migrating from sentencepiece.bpe.model to tokenizer.json")
                oldSp.delete()
                prefs.edit().remove("nllb_size_tokenizer").apply()
            }
            return false
        }
        // If we recorded expected sizes from a successful download, enforce an exact
        // match so a truncated/corrupt file is treated as missing (and re-fetched).
        val encExp = prefs.getLong("nllb_size_encoder", -1L)
        val decExp = prefs.getLong("nllb_size_decoder", -1L)
        val tokExp = prefs.getLong("nllb_size_tokenizer", -1L)
        if (encExp > 0 && encoderFile.length() != encExp) return false
        if (decExp > 0 && decoderFile.length() != decExp) return false
        if (tokExp > 0 && tokenizerFile.length() != tokExp) return false
        return true
    }

    suspend fun downloadModel(
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (_isDownloading.value) return@withContext false
        // Local-Only Mode blocks model downloads (throws before any traffic).
        com.noteflowai.app.data.NetworkModule.requireNetworkAllowed(ENCODER_URL)
        _isDownloading.value = true
        _downloadProgress.value = 0f

        try {
            modelDir.mkdirs()

            val files = listOf(
                encoderFile to ENCODER_URL,
                decoderFile to DECODER_URL,
                tokenizerFile to TOKENIZER_URL
            )

            // Clean up old sentencepiece.bpe.model if present
            File(modelDir, "sentencepiece.bpe.model").delete()

            val sizes = files.map { (file, url) ->
                if (file.exists() && file.length() > 1000) file.length()
                else getUrlSize(url)
            }
            val totalBytes = sizes.sum()
            var downloadedBytes = 0L

            files.forEachIndexed { index, (file, url) ->
                val expected = sizes[index]
                if (file.exists() && file.length() == expected) {
                    downloadedBytes += expected
                    onProgress(downloadedBytes.toFloat() / totalBytes)
                    return@forEachIndexed
                }
                // Re-download, retrying if the file ends up truncated/corrupt.
                val maxAttempts = 3
                var ok = false
                for (attempt in 1..maxAttempts) {
                    file.delete()
                    try {
                        downloadFile(url, file, expected) { bytes ->
                            val current = downloadedBytes + bytes
                            onProgress((current.coerceAtMost(totalBytes)).toFloat() / totalBytes)
                            _downloadProgress.value = (current.coerceAtMost(totalBytes)).toFloat() / totalBytes
                        }
                        if (file.exists() && file.length() == expected) {
                            ok = true
                            break
                        }
                        Log.w(TAG, "Size mismatch for ${file.name}: got ${file.length()}, expected $expected (attempt $attempt)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Download attempt $attempt failed for ${file.name}: ${e.message}")
                    }
                    file.delete()
                }
                if (!ok) throw Exception("Failed to download ${file.name} after $maxAttempts attempts")
                downloadedBytes += expected
            }

            // Record expected sizes so checkModelExists() can detect truncation later.
            prefs.edit().apply {
                putLong("nllb_size_encoder", encoderFile.length())
                putLong("nllb_size_decoder", decoderFile.length())
                putLong("nllb_size_tokenizer", tokenizerFile.length())
            }.apply()

            _isDownloaded.value = checkModelExists()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            modelDir.listFiles()?.forEach { it.delete() }
            false
        } finally {
            _isDownloading.value = false
        }
    }

    fun deleteModel() {
        encoderFile.delete()
        decoderFile.delete()
        tokenizerFile.delete()
        modelDir.delete()
        _isDownloaded.value = false
        unloadModels()
    }

    private fun ensureModelsLoaded() {
        if (encoderSession != null) return
        if (!checkModelExists()) throw IllegalStateException("NLLB model not downloaded")

        ortEnv = OrtEnvironment.getEnvironment()
        val availableProcessors = Runtime.getRuntime().availableProcessors()
        // NNAPI: NPU handles heavy lifting, fewer CPU threads
        // CPU: maximize CPU threads
        val nnapiThreads = (availableProcessors - 1).coerceIn(2, 4)
        val cpuThreads = (availableProcessors - 1).coerceIn(3, 6)

        val cachedBackend = prefs.getString("cached_nllb_backend", null)

        // NOTE: QNN HTP was tested but is SLOWER than CPU for NLLB decoder
        // (800-1600ms/step vs 200-300ms/step). The autoregressive decoder pattern
        // with growing sequences doesn't map well to NPU. CPU with ARM NEON is faster.
        val sessionOptionsList = buildList {
            // If cached backend is valid and not old/bad ones, try it first
            if (cachedBackend != null && cachedBackend != "NNAPI(FP16)" && cachedBackend != "NNAPI(default)") {
                add(cachedBackend to { so: OrtSession.SessionOptions ->
                    so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    so.setIntraOpNumThreads(cpuThreads)
                })
            }
            // CPU — native INT8 quantized inference with ARM NEON, fastest for NLLB
            add("CPU" to { so: OrtSession.SessionOptions ->
                so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                so.setIntraOpNumThreads(cpuThreads)
            })
            // NNAPI fallback — falls back to nnapi-reference on most devices, worse than CPU
            add("NNAPI(default)" to { so: OrtSession.SessionOptions ->
                so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                so.setIntraOpNumThreads(nnapiThreads)
                so.addNnapi()
            })
        }

        val seen = mutableSetOf<String>()
        for ((name, configure) in sessionOptionsList) {
            if (name in seen) continue
            seen.add(name)
            try {
                Log.i(TAG, "Trying $name for NLLB encoder...")
                val options = OrtSession.SessionOptions()
                configure(options)
                encoderSession = ortEnv!!.createSession(encoderFile.absolutePath, options)
                decoderSession = ortEnv!!.createSession(decoderFile.absolutePath, options)
                Log.i(TAG, "NLLB sessions created with $name")
                Log.i(TAG, "Decoder inputs: ${decoderSession?.inputInfo?.map { it.key + "=" + it.value.info }?.joinToString(", ")}")
                Log.i(TAG, "Decoder outputs: ${decoderSession?.outputInfo?.map { it.key + "=" + it.value.info }?.joinToString(", ")}")
                prefs.edit().putString("cached_nllb_backend", name).apply()
                logMemoryUsage("NLLB load ($name)")
                break
            } catch (e: Exception) {
                Log.w(TAG, "$name failed for NLLB: ${e.message}")
                Log.w(TAG, android.util.Log.getStackTraceString(e))
                encoderSession?.close()
                decoderSession?.close()
                encoderSession = null
                decoderSession = null
            }
        }

        if (encoderSession == null) {
            Log.w(TAG, "All EP strategies failed, trying plain CPU...")
            encoderSession = ortEnv!!.createSession(encoderFile.absolutePath)
            decoderSession = ortEnv!!.createSession(decoderFile.absolutePath)
        }

        try {
            tokenizer = SpTokenizer(tokenizerFile.absolutePath)
            Log.i(TAG, "NLLB tokenizer loaded (${tokenizerFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load NLLB tokenizer from ${tokenizerFile.absolutePath}", e)
            tokenizer = null
            throw e
        }
    }

    fun unloadModels() {
        encoderSession?.close()
        decoderSession?.close()
        tokenizer = null
        encoderSession = null
        decoderSession = null
        ortEnv = null
    }

    fun clearTranslationCache() {
        synchronized(translationCache) {
            translationCache.clear()
            Log.i(TAG, "Translation cache cleared")
        }
    }

    private fun logMemoryUsage(label: String) {
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val freeMB = runtime.freeMemory() / (1024 * 1024)
        val maxMB = runtime.maxMemory() / (1024 * 1024)
        Log.i(TAG, "[$label] Memory: used=${usedMB}MB, free=${freeMB}MB, max=${maxMB}MB (${usedMB * 100 / maxMB}% used)")
    }

    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text

        // Check translation memory cache first
        val cacheKey = text.trim()
        val cached = synchronized(translationCache) {
            val key = computeCacheKey(sourceLang, targetLang, cacheKey)
            translationCache[key]
        }
        if (cached != null) {
            Log.d(TAG, "Translation cache HIT (${text.length} chars, $sourceLang->$targetLang)")
            return@withContext cached
        }

        val startTime = System.currentTimeMillis()
        try {
            ensureModelsLoaded()
            val env = ortEnv ?: throw IllegalStateException("ORT env not initialized")
            val encoder = encoderSession ?: throw IllegalStateException("Encoder not loaded")
            val decoder = decoderSession ?: throw IllegalStateException("Decoder not loaded")
            val sp = tokenizer ?: throw IllegalStateException("Tokenizer not loaded")

            Log.i(TAG, "translate() called with sourceLang='$sourceLang', targetLang='$targetLang'")
            val srcCode = languageCodeToNllb(sourceLang)
            val tgtCode = languageCodeToNllb(targetLang)

            // Language codes are special tokens in tokenizer.json — direct lookup, no BPE
            // The ONNX model uses HuggingFace vocab ordering (bos=0, pad=1, eos=2)
            val srcLangToken = sp.tokenId(srcCode) ?: throw IllegalStateException("Source lang '$srcCode' not in vocab")
            val tgtLangToken = sp.tokenId(tgtCode) ?: throw IllegalStateException("Target lang '$tgtCode' not in vocab")
            Log.i(TAG, "Source lang: $srcCode HF=$srcLangToken")
            Log.i(TAG, "Target lang: $tgtCode HF=$tgtLangToken")

            // NLLB encoder format: [src_lang_token, ...text_tokens, </s>]
            // Matches tokenizer.json post-processor: [eng_Latn, A, </s>]
            // Strip markdown formatting to prevent gibberish number output
            val cleanText = stripMarkdown(text)
            val textTokens = sp.encode(cleanText)
            Log.i(TAG, "Text tokens: ${textTokens.size} tokens")

            val allTokens = longArrayOf(srcLangToken.toLong()) + textTokens.map { it.toLong() }.toLongArray() + longArrayOf(EOS_ID.toLong())

            // Diagnostic: print final encoder sequence header (first 5 + last 5)
            val header = if (allTokens.size <= 10) {
                allTokens.joinToString(" ") { "${it}:${sp.pieceForId(it.toInt())}" }
            } else {
                val first5 = (0 until 5).joinToString(" ") { "${allTokens[it]}:${sp.pieceForId(allTokens[it].toInt())}" }
                val last5 = (allTokens.size - 5 until allTokens.size).joinToString(" ") { "${allTokens[it]}:${sp.pieceForId(allTokens[it].toInt())}" }
                "$first5 ... $last5"
            }
            Log.i(TAG, "Encoder sequence (${allTokens.size} tokens): $header")

            // The NLLB decoder's position-embedding Gather node is bounded at 1024
            // and indexes at (encoder length + decode step). To avoid overflow we
            // must keep encoderLen + maxDecodeLen <= 1023. We cap the encoder input
            // at 192 tokens — covers most notes without truncation. Going higher
            // doesn't help because the OOM bottleneck is the logits tensor on JVM
            // heap (1MB × seqLen per decode step), not encoder length.
            val cap = 192
            val truncated = allTokens.size > cap
            val inputArray = if (truncated) allTokens.copyOf(cap) else allTokens
            if (truncated) {
                Log.w(TAG, "Encoder input truncated from ${allTokens.size} to $cap tokens to avoid decoder position overflow")
            }
            Log.i(TAG, "Encoder input: ${inputArray.size} tokens (HF IDs)")

            val inputTensor = OnnxTensor.createTensor(env, arrayOf(inputArray))
            // HuggingFace ONNX export: attention_mask 1=attend, 0=don't.
            // The get_extended_attention_mask inversion is NOT in the graph — it's
            // applied by the pipeline before calling the model.
            val attentionArray = LongArray(inputArray.size) { 1L }
            val attentionTensor = OnnxTensor.createTensor(env, arrayOf(attentionArray))

            val generatedHfTokens = encoder.run(mapOf(
                "input_ids" to inputTensor,
                "attention_mask" to attentionTensor
            )).use { encoderOutput ->
                val encoderHidden = encoderOutput[0] as ai.onnxruntime.OnnxTensor
                Log.i(TAG, "encoder hidden shape: ${encoderHidden.info.shape.contentToString()}")
                try {
                    translateGreedy(env, decoder, encoderHidden, attentionTensor, inputArray.size, tgtLangToken, sp)
                } finally {
                    encoderHidden.close()
                }
            }

            inputTensor.close()
            attentionTensor.close()

            // Model outputs HF vocab IDs directly
            // Filter: skip all language tokens (ID >= 256000) and <unk> tokens.
            // The forced BOS token from step 0 and any mid-sequence language tokens
            // would produce "zho_hans" / "eng_Latn" gibberish in the output.
            val rawTokens = generatedHfTokens.toIntArray()
            Log.i(TAG, "Raw output tokens: ${rawTokens.size}")
            val decodeTokens = rawTokens
                .filter { it < 256000 && it != UNK_ID }
                .toIntArray()
            val result = sp.decode(decodeTokens)
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Translation: ${text.length} chars -> ${result.length} chars in ${elapsed}ms ($sourceLang->$targetLang, ${textTokens.size} input tokens, ${generatedHfTokens.size} output tokens)")
            // Decoded text is user content: never log it.

            // Store in translation memory cache
            synchronized(translationCache) {
                val key = computeCacheKey(sourceLang, targetLang, text.trim())
                translationCache[key] = result
            }

            result
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "Translation failed after ${elapsed}ms: ${e.message}")
            Log.e(TAG, android.util.Log.getStackTraceString(e))
            text
        }
    }

    private fun translateGreedy(
        env: OrtEnvironment,
        decoder: OrtSession,
        encoderHidden: ai.onnxruntime.OnnxTensor,
        attentionTensor: OnnxTensor,
        inputTokenCount: Int = 50,
        tgtLangToken: Int,
        sp: SpTokenizer? = null
    ): MutableList<Int> {
        // Adaptive max length: translation is typically 1-3x source length.
        // The NLLB decoder's position embeddings are bounded at 1024, and the
        // effective index is (encoder length + decode step), so the decode step
        // must stay below 1024 - inputLen or we overflow the Gather node.
        //
        // MEMORY CONSTRAINT: Each decode step materializes a logits tensor of
        // shape [1, seqLen, 256000] on JVM heap (~1MB × seqLen). After model
        // loading (~400MB+), a 4-8GB Android device has 2-6GB free. We adapt
        // maxLen based on available heap: more headroom → longer output.
        // Chinese translations need ~3x the English input length, so 40 was
        // too low for anything beyond short phrases.
        val modelMaxPos = 1024
        val posLimit = (modelMaxPos - inputTokenCount - 2).coerceAtLeast(20)
        val runtime = Runtime.getRuntime()
        runtime.gc()
        val freeMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
        // Adaptive maxLen: more free heap → longer decode.
        // Chinese translations need ~3x the English input length.
        // For 150-char chunks (~40 input tokens), Chinese output needs ~120 tokens.
        // Logits tensor peaks at ~1MB × seqLen per step; freed after each step.
        val maxLen = when {
            freeMB > 200 -> (inputTokenCount * 3 + 40).coerceIn(60, 200).coerceAtMost(posLimit)
            freeMB > 100 -> (inputTokenCount * 3 + 20).coerceIn(40, 160).coerceAtMost(posLimit)
            freeMB > 60  -> (inputTokenCount * 2 + 20).coerceIn(40, 100).coerceAtMost(posLimit)
            else         -> (inputTokenCount * 2 + 10).coerceIn(30, 60).coerceAtMost(posLimit)
        }
        val generatedTokens = mutableListOf<Int>()
        // NLLB decoder starts with ONLY decoder_start_token_id (EOS=2).
        // The target language token is injected by ForcedBOSTokenLogitsProcessor:
        // when cur_len==1, it sets all logits to -inf except forced_bos_token_id.
        // So we must start with [EOS] only — NOT [EOS, tgtLang].
        var currentIds = longArrayOf(EOS_ID.toLong())
        val tokenFrequency = mutableMapOf<Long, Int>()  // track token usage for repetition penalty

        for (step in 0 until maxLen) {
            // Memory safety: check heap every 5 steps and force GC to prevent OOM.
            // The logits tensor (~1MB × seqLen) is the main heap consumer.
            if (step % 5 == 0) {
                runtime.gc()
                val freeMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
                Log.i(TAG, "greedy step $step/$maxLen, free heap: ${freeMB}MB")
                if (freeMB < 40) {
                    Log.w(TAG, "Low memory at step $step (${freeMB}MB free), stopping decode early")
                    break
                }
            }
            Log.d(TAG, "greedy loop step $step, maxLen=$maxLen, currentIdsLen=${currentIds.size}")
            val decoderInput = OnnxTensor.createTensor(env, arrayOf(currentIds))

            val decoderInputs = mapOf(
                "input_ids" to decoderInput,
                "encoder_hidden_states" to encoderHidden,
                "encoder_attention_mask" to attentionTensor
            )
            if (step == 0) {
                Log.i(TAG, "decoder input_ids shape: [1, ${currentIds.size}]")
                Log.i(TAG, "encoderHidden shape: ${encoderHidden.info.shape}")
                Log.i(TAG, "attentionTensor shape: ${attentionTensor.info.shape}")
            }
            val decoderOutput = decoder.run(decoderInputs)
            try {
                val logits = decoderOutput[0].value as Array<Array<FloatArray>>
                val lastLogits = logits[0][logits[0].size - 1]

                // Simulate ForcedBOSTokenLogitsProcessor: at step 0 (cur_len==1),
                // force the target language token as first generated token.
                // This matches HuggingFace's generate() which injects the tgt lang
                // via logits processing when the decoder input has exactly 1 token.
                val nextToken = if (currentIds.size == 1) {
                    Log.i(TAG, "ForcedBOS: forcing tgtLang token $tgtLangToken at step 0")
                    tgtLangToken.toLong()
                } else {
                    lastLogits.indices.maxByOrNull { lastLogits[it] }?.toLong() ?: break
                }

                // Detailed logging for first few steps
                if (step < 3) {
                    // Find top-5 without sorting full array
                    val top5Pairs = mutableListOf<Pair<Int, Float>>()
                    for (i in lastLogits.indices) {
                        if (top5Pairs.size < 5) {
                            top5Pairs.add(i to lastLogits[i])
                            top5Pairs.sortByDescending { it.second }
                        } else if (lastLogits[i] > top5Pairs.last().second) {
                            top5Pairs[4] = i to lastLogits[i]
                            top5Pairs.sortByDescending { it.second }
                        }
                    }
                    val top5Info = top5Pairs.joinToString(", ") { (idx, logit) ->
                        val piece = sp?.pieceForId(idx) ?: "?"
                        "id=$idx($piece,${String.format("%.2f", logit)})"
                    }
                    Log.i(TAG, "greedy step $step nextToken=$nextToken top5=[$top5Info]")
                } else if (step % 20 == 0) {
                    Log.i(TAG, "greedy step $step nextToken=$nextToken generated=${generatedTokens.size}")
                }

                if (nextToken == EOS_ID.toLong()) {
                    Log.i(TAG, "greedy hit EOS at step $step")
                    break
                }

                // Skip target language token and any language code tokens after step 0.
                // The model sometimes regenerates the language code mid-sequence,
                // which produces "zho_hans" gibberish in the output.
                if (step > 0 && nextToken >= 256000) {
                    Log.d(TAG, "greedy step $step: skipping language token $nextToken")
                    tokenFrequency[nextToken] = (tokenFrequency[nextToken] ?: 0) + 1
                    decoderInput.close()
                    continue
                }

                // Skip unk tokens — they produce gibberish in the output
                if (nextToken == UNK_ID.toLong()) {
                    Log.d(TAG, "greedy step $step: skipping unk token")
                    tokenFrequency[nextToken] = (tokenFrequency[nextToken] ?: 0) + 1
                    decoderInput.close()
                    continue
                }

                // Track frequency — no aggressive break for common words.
                // Words like "untuk", "yang", "dan" naturally appear 3-5x in translations.
                tokenFrequency[nextToken] = (tokenFrequency[nextToken] ?: 0) + 1

                // Safety: if generated output exceeds max possible output length,
                // stop to avoid position overflow.
                val maxOutputLen = (modelMaxPos - inputTokenCount - 2).coerceAtLeast(20)
                if (generatedTokens.size >= maxOutputLen) {
                    Log.i(TAG, "greedy breaking: output ${generatedTokens.size} tokens >= max output $maxOutputLen")
                    break
                }

                generatedTokens.add(nextToken.toInt())
                currentIds += nextToken
            } catch (e: Exception) {
                Log.e(TAG, "greedy step $step failed: ${e.message}")
                Log.e(TAG, android.util.Log.getStackTraceString(e))
                break
            } finally {
                decoderOutput.close()
            }

            decoderInput.close()
            // Hint GC to free ONNX native memory from the logits tensor.
            // Without this, the native allocator may hold onto freed blocks,
            // causing memory to grow unboundedly across decode steps.
            if (step % 3 == 0) runtime.gc()
            Log.d(TAG, "greedy step $step done, continuing to step ${step + 1}")
        }
        return generatedTokens
    }

    private fun computeCacheKey(srcLang: String, tgtLang: String, text: String): String {
        // Use MD5 of full text to prevent false cache hits from prefix collision.
        // Two different texts with same first-50-chars and same length would
        // incorrectly share a cache entry with the old prefix+length scheme.
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$srcLang|$tgtLang|$hash"
    }

    suspend fun translateBatch(
        texts: List<String>,
        sourceLang: String,
        targetLang: String,
        isOcr: Boolean = false
    ): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        if (texts.size == 1) {
            // For single-item batch, apply OCR stripping if needed before
            // calling translate() which doesn't have an isOcr parameter.
            val text = if (isOcr) stripMarkdown(texts[0], isOcr = true) else texts[0]
            return@withContext listOf(translate(text, sourceLang, targetLang))
        }

        try {
            ensureModelsLoaded()
            val env = ortEnv ?: throw IllegalStateException("ORT env not initialized")
            val encoder = encoderSession ?: throw IllegalStateException("Encoder not loaded")
            val decoder = decoderSession ?: throw IllegalStateException("Decoder not loaded")
            val sp = tokenizer ?: throw IllegalStateException("Tokenizer not loaded")

            val srcCode = languageCodeToNllb(sourceLang)
            val tgtCode = languageCodeToNllb(targetLang)

            // Language codes are special tokens — direct lookup (HF IDs, model uses HF ordering)
            val srcLangToken = sp.tokenId(srcCode) ?: throw IllegalStateException("Source lang '$srcCode' not in vocab")
            val tgtLangToken = sp.tokenId(tgtCode) ?: throw IllegalStateException("Target lang '$tgtCode' not in vocab")
            Log.i(TAG, "Batch translate: src=$srcCode HF=$srcLangToken, tgt=$tgtCode HF=$tgtLangToken")

            // Check cache for each text, collect uncached ones
            val results = Array(texts.size) { "" }
            val uncachedIndices = mutableListOf<Int>()
            val uncachedTexts = mutableListOf<String>()

            for (i in texts.indices) {
                val cached = synchronized(translationCache) {
                    translationCache[computeCacheKey(sourceLang, targetLang, texts[i].trim())]
                }
                if (cached != null) {
                    results[i] = cached
                } else {
                    uncachedIndices.add(i)
                    uncachedTexts.add(texts[i])
                }
            }

            if (uncachedTexts.isNotEmpty()) {
                // Translate uncached texts
                val runtime = Runtime.getRuntime()
                var batchAborted = false
                for ((batchIdx, origIdx) in uncachedIndices.withIndex()) {
                    // Memory safety: check heap before each chunk translation
                    if (batchIdx > 0) {
                        System.gc()
                        val freeMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
                        Log.i(TAG, "Batch chunk $batchIdx/${uncachedIndices.size}, free heap: ${freeMB}MB")
                        if (freeMB < 60) {
                            Log.w(TAG, "Low memory during batch translation (${freeMB}MB free), stopping early")
                            batchAborted = true
                            break
                        }
                    }
                    // Text is already stripped by translateLongText() — no double-strip
                    val text = uncachedTexts[batchIdx]
                    val textTokens = sp.encode(text)
                    val inputArray = longArrayOf(srcLangToken.toLong()) + textTokens.map { it.toLong() }.toLongArray() + longArrayOf(EOS_ID.toLong())

                    val inputTensor = OnnxTensor.createTensor(env, arrayOf(inputArray))
                    val attentionArray = LongArray(inputArray.size) { 1L }
                    val attentionTensor = OnnxTensor.createTensor(env, arrayOf(attentionArray))

                    val generatedHfTokens = encoder.run(mapOf(
                        "input_ids" to inputTensor,
                        "attention_mask" to attentionTensor
                    )).use { encoderOutput ->
                        val encoderHidden = encoderOutput[0] as ai.onnxruntime.OnnxTensor
                        try {
                            // Always use greedy for batch — greedy produces good
                            // results and beam search would be 4x slower + memory-heavy
                            translateGreedy(env, decoder, encoderHidden, attentionTensor, inputArray.size, tgtLangToken, sp)
                        } finally {
                            encoderHidden.close()
                        }
                    }

                    inputTensor.close()
                    attentionTensor.close()

                    // Filter out language tokens (ID >= 256000) and <unk> before decoding,
                    // matching the filter in translate(). Without this, the forced BOS
                    // language token from step 0 appears as "zho_hans" in the output.
                    val decodedTokens = generatedHfTokens
                        .filter { it < 256000 && it != UNK_ID }
                        .toIntArray()
                    val result = sp.decode(decodedTokens)
                    results[origIdx] = result

                    // Cache
                    synchronized(translationCache) {
                        translationCache[computeCacheKey(sourceLang, targetLang, text.trim())] = result
                    }
                }

                // If batch was aborted due to memory, fill remaining untranslated
                // slots with the original source text so the user sees something
                // instead of empty gaps.
                if (batchAborted) {
                    for (i in (uncachedIndices.size - 1) downTo 0) {
                        val origIdx = uncachedIndices[i]
                        if (results[origIdx].isEmpty()) {
                            results[origIdx] = texts[origIdx]
                        } else break  // first non-empty means all before it are done
                    }
                }
            }

            results.toList()
        } catch (e: Exception) {
            Log.e(TAG, "Batch translation failed", e)
            texts
        }
    }

    suspend fun translateLongText(
        text: String,
        sourceLang: String,
        targetLang: String,
        isOcr: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text

        // Strip markdown formatting before translation.
        // For OCR text, also remove punctuation that confuses NLLB.
        val cleanText = stripMarkdown(text, isOcr = isOcr)

        // Always split into token-sized chunks and translate each.
        // The encoder caps at 192 tokens (~750 chars) — texts longer than
        // that must be split or they get truncated at the encoder.
        val sentences = splitIntoSentences(cleanText)
        if (sentences.size <= 1 && cleanText.length < 150) {
            // Short text — translate directly
            return@withContext translate(cleanText, sourceLang, targetLang)
        }

        Log.i(TAG, "Long text split into ${sentences.size} chunks for translation")

        val translated = translateBatch(sentences, sourceLang, targetLang, isOcr = isOcr)
        translated.joinToString(" ")
    }

    /**
     * Strip markdown formatting and optionally punctuation before translation.
     *
     * @param isOcr when true, also removes special punctuation that confuses NLLB
     *               (colons, semicolons, brackets, etc.). These characters ARE
     *               meaningful in user notes, so they are kept for non-OCR text.
     */
    private fun stripMarkdown(text: String, isOcr: Boolean = false): String {
        // Strip markdown and formatting characters before translation.
        // The NLLB model gets confused by markdown tokens, punctuation,
        // and code-like patterns — it reproduces them instead of translating.
        var result = text
        result = result.replace(Regex("""#{1,6}\s+"""), "")           // headings
        result = result.replace(Regex("""\*{1,3}"""), "")              // bold/italic
        result = result.replace(Regex("""`{1,3}[^`]*`{1,3}"""), "")   // inline code
        result = result.replace(Regex("""\[[^\]]*\]\([^)]*\)"""), "")  // links [text](url)
        result = result.replace(Regex("""^[-*+]\s+""", RegexOption.MULTILINE), "") // list markers
        result = result.replace(Regex("""^\d+\.\s+""", RegexOption.MULTILINE), "")  // numbered lists
        result = result.replace(Regex("""^>\s+""", RegexOption.MULTILINE), "")       // blockquotes
        result = result.replace(Regex("""^---+\s*$""", RegexOption.MULTILINE), "")   // horizontal rules
        result = result.replace(Regex("""\|[^|]+\|"""), "")            // tables
        if (isOcr) {
            // Only strip these for OCR text — in user notes, colons and brackets
            // carry meaning. OCR output doesn't need them, and they confuse NLLB.
            result = result.replace(Regex("""[:;\"'\[\]{}()|\\/_=<>~`^]"""), "")
        }
        result = result.replace(Regex("""\s+"""), " ")                  // collapse all whitespace
        result = result.replace(Regex("""\n{3,}"""), "\n\n")           // collapse newlines
        return result.trim()
    }

    private fun splitIntoSentences(text: String): List<String> {
        // Split text into chunks for batch translation.
        // The NLLB encoder caps at 192 tokens (~750 chars), and the decoder's
        // maxLen is adaptive (20-120) based on available memory.
        //
        // CHUNK_SIZE = 150 chars ≈ 40 input tokens, leaving ~80 output tokens
        // for Chinese translation (Chinese needs ~3x English tokens). This keeps
        // most English sentences intact (avg 100-200 chars) while staying well
        // within encoder limits.
        //
        // Strategy: split on newlines first (natural paragraph breaks), then
        // merge short segments into chunks up to CHUNK_SIZE.

        val CHUNK_SIZE = 150

        // Split on newlines (natural breaks in OCR text and notes)
        val segments = text.split(Regex("""\n+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // Merge short segments into chunks up to CHUNK_SIZE
        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()
        for (segment in segments) {
            if (currentChunk.isEmpty()) {
                currentChunk.append(segment)
            } else if (currentChunk.length + segment.length + 1 < CHUNK_SIZE) {
                currentChunk.append(" ").append(segment)
            } else {
                chunks.add(currentChunk.toString())
                currentChunk = StringBuilder(segment)
            }
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString())
        }

        // If any chunk exceeds CHUNK_SIZE, force-split on sentence boundaries,
        // then on spaces if still too long
        val result = mutableListOf<String>()
        for (chunk in chunks) {
            if (chunk.length <= CHUNK_SIZE) {
                result.add(chunk)
                continue
            }
            // Split on sentence boundaries: period/exclamation/question + space
            val parts = chunk.split(Regex("""(?<=[.!?])\s+""")).filter { it.isNotBlank() }
            var subChunk = StringBuilder()
            for (part in parts) {
                if (subChunk.isEmpty()) {
                    subChunk.append(part)
                } else if (subChunk.length + part.length + 1 < CHUNK_SIZE) {
                    subChunk.append(" ").append(part)
                } else {
                    result.add(subChunk.toString())
                    subChunk = StringBuilder(part)
                }
            }
            if (subChunk.isNotEmpty()) {
                // If still too long, force-split on spaces
                if (subChunk.length > CHUNK_SIZE) {
                    val words = subChunk.toString().split(Regex("""\s+""")).filter { it.isNotBlank() }
                    subChunk = StringBuilder()
                    for (word in words) {
                        if (subChunk.isEmpty()) {
                            subChunk.append(word)
                        } else if (subChunk.length + word.length + 1 < CHUNK_SIZE) {
                            subChunk.append(" ").append(word)
                        } else {
                            result.add(subChunk.toString())
                            subChunk = StringBuilder(word)
                        }
                    }
                }
                if (subChunk.isNotEmpty()) result.add(subChunk.toString())
            }
        }

        return result
    }

    private fun getUrlSize(urlString: String): Long {
        return try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.instanceFollowRedirects = true
            val responseCode = conn.responseCode
            val size = conn.contentLengthLong
            conn.disconnect()
            if (responseCode == HttpURLConnection.HTTP_OK && size > 0) size
            else throw Exception("HTTP $responseCode for $urlString")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get URL size: $urlString", e)
            throw Exception("Cannot reach model file: ${URL(urlString).path}")
        }
    }

    private fun downloadFile(
        urlString: String,
        targetFile: File,
        expectedSize: Long = -1L,
        onBytes: (Long) -> Unit
    ) {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        // Large models (1GB+) over a slow CDN can take many minutes; do not
        // let the socket idle-timeout kill an in-progress transfer.
        conn.readTimeout = 0
        conn.instanceFollowRedirects = true
        conn.connect()

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw Exception("HTTP $responseCode downloading $urlString")
        }

        val input = conn.inputStream
        val output = FileOutputStream(targetFile)
        val buffer = ByteArray(8192)
        var read: Int
        var total = 0L

        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            total += read
            onBytes(total)
        }

        output.close()
        input.close()
        conn.disconnect()

        if (expectedSize > 0 && total != expectedSize) {
            throw Exception("Incomplete download for ${targetFile.name}: $total/$expectedSize bytes")
        }
    }

    /**
     * Tokenizer backed by HuggingFace tokenizer.json.
     * Handles BPE encoding with correct vocab IDs matching the ONNX model.
     *
     * The ONNX model was exported with SentencePiece protobuf vocab ordering:
     *   Protobuf: <unk>=0, <s>=1, </s>=2, regular vocab, then language codes at 256000+
     *   HuggingFace: <s>=0, <pad>=1, </s>=2, <unk>=3, regular vocab, then language codes
     *
     * We encode/decode using HuggingFace vocab, but convert to protobuf IDs when
     * passing tokens to the ONNX model.
     */
    class SpTokenizer(modelPath: String) {
        private val vocab = HashMap<Int, String>()       // HF id -> piece
        private val pieceToId = HashMap<String, Int>()    // piece -> HF id
        private val bpeRanks = HashMap<Pair<String, String>, Int>()

        // Protobuf vocab: piece -> protobuf ID (SentencePiece ordering)
        private val pbPieceToId = HashMap<String, Int>()

        // Conversion maps: HF ID <-> protobuf ID
        private val hfToPbId = HashMap<Int, Int>()
        private val pbToHfId = HashMap<Int, Int>()

        // LRU cache for BPE word encodings
        private val bpeCache = object : LinkedHashMap<String, List<Int>>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Int>>): Boolean {
                return size > 2000
            }
        }

        init {
            loadModel(modelPath)
        }

        /** Direct HF token ID lookup (for special tokens like language codes). */
        fun tokenId(piece: String): Int? = pieceToId[piece]

        /** Get the piece string for a HuggingFace token ID (for logging). */
        fun pieceForId(hfId: Int): String = vocab[hfId] ?: "?hf$hfId"

        /** Convert HuggingFace token IDs to protobuf IDs for the ONNX model. */
        fun convertToProtobuf(hfIds: IntArray): IntArray {
            return IntArray(hfIds.size) { i -> hfToPbId[hfIds[i]] ?: hfIds[i] }
        }

        /** Convert protobuf IDs from ONNX model output back to HuggingFace IDs for decoding. */
        fun convertFromProtobuf(pbIds: List<Int>): IntArray {
            return IntArray(pbIds.size) { i -> pbToHfId[pbIds[i]] ?: pbIds[i] }
        }

        /** Get the piece string for a protobuf token ID (for logging). */
        fun pbPieceForId(pbId: Int): String {
            val hfId = pbToHfId[pbId] ?: return "?pb$pbId"
            return vocab[hfId] ?: "?hf$hfId"
        }

        fun encode(text: String): IntArray {
            val tokens = mutableListOf<Int>()

            // Metaspace pre-tokenizer: replace spaces with ▁, prepend ▁
            val normalized = "▁" + text.replace(" ", "▁")
            val sentences = normalized.split("\n")

            for ((si, sentence) in sentences.withIndex()) {
                if (si > 0) {
                    // Encode newline
                    val nlId = pieceToId["\n"] ?: pieceToId["<0x0A>"] ?: UNK_ID
                    tokens.add(nlId)
                }
                if (sentence.isEmpty()) continue
                val bpeTokens = bpeEncode(sentence)
                tokens.addAll(bpeTokens)
            }
            return tokens.toIntArray()
        }

        fun decode(ids: IntArray): String {
            val sb = StringBuilder()
            for (id in ids) {
                val piece = vocab[id] ?: continue
                // Replace ▁ (Metaspace) with appropriate output:
                // - Space before Latin letters/digits (word boundary)
                // - Nothing before CJK/other chars (just a token boundary)
                var i = 0
                while (i < piece.length) {
                    val ch = piece[i]
                    if (ch == '▁') {
                        val next = if (i + 1 < piece.length) piece[i + 1] else ' '
                        if (next.isLetterOrDigit() && next.code < 0x2E80) {
                            sb.append(' ')
                        }
                        // else: skip the ▁ (no space for CJK boundaries)
                    } else {
                        sb.append(ch)
                    }
                    i++
                }
            }
            return sb.toString().trim()
        }

        private fun bpeEncode(word: String): List<Int> {
            if (word.isEmpty()) return emptyList()

            // Check BPE cache first
            synchronized(bpeCache) {
                val cached = bpeCache[word]
                if (cached != null) return cached
            }

            // Split into initial characters (Unicode codepoints)
            val chars = mutableListOf<String>()
            for (c in word) {
                chars.add(c.toString())
            }

            if (chars.size == 1) {
                val result = listOf(pieceToId[chars[0]] ?: UNK_ID)
                synchronized(bpeCache) { bpeCache[word] = result }
                return result
            }

            // Apply BPE merges in priority order (lower rank = higher priority)
            while (chars.size >= 2) {
                var bestRank = Int.MAX_VALUE
                var bestIdx = -1

                // Find the highest-priority (lowest rank) adjacent pair
                for (i in 0 until chars.size - 1) {
                    val rank = bpeRanks[chars[i] to chars[i + 1]] ?: Int.MAX_VALUE
                    if (rank < bestRank) {
                        bestRank = rank
                        bestIdx = i
                    }
                }

                if (bestIdx == -1) break // No more mergeable pairs

                // Merge the best pair
                val merged = chars[bestIdx] + chars[bestIdx + 1]
                chars[bestIdx] = merged
                chars.removeAt(bestIdx + 1)
            }

            // Map final pieces to token IDs (UNK for unknown pieces)
            val result = chars.map { pieceToId[it] ?: UNK_ID }
            synchronized(bpeCache) { bpeCache[word] = result }
            return result
        }

        @Suppress("UNCHECKED_CAST")
        private fun loadModel(path: String) {
            val jsonStr = File(path).readText(Charsets.UTF_8)
            val root = org.json.JSONObject(jsonStr)
            val model = root.getJSONObject("model")

            // Load HuggingFace vocabulary (piece -> id)
            val vocabObj = model.getJSONObject("vocab")
            val keys = vocabObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = vocabObj.getInt(key)
                pieceToId[key] = id
                vocab[id] = key
            }
            Log.i("SpTokenizer", "Loaded ${pieceToId.size} vocabulary pieces from tokenizer.json")

            // Load BPE merges (array of ["pieceA", "pieceB"] pairs)
            val mergesArr = model.getJSONArray("merges")
            for (i in 0 until mergesArr.length()) {
                val pair = mergesArr.getJSONArray(i)
                val a = pair.getString(0)
                val b = pair.getString(1)
                bpeRanks[a to b] = i
            }
            Log.i("SpTokenizer", "Loaded ${bpeRanks.size} BPE merge rules")

            // Build protobuf vocab from the sentencepiece.bpe.model file.
            // The ONNX model was exported with SentencePiece protobuf vocab ordering,
            // not the HuggingFace tokenizer.json ordering. We need to convert between them.
            loadProtobufVocab(File(File(path).parent, "sentencepiece.bpe.model"))

            // Log key token IDs for verification
            Log.i("SpTokenizer", "HF: BOS(<s>)=${pieceToId["<s>"]}, EOS(</s>)=${pieceToId["</s>"]}, " +
                "UNK(<unk>)=${pieceToId["<unk>"]}, PAD(<pad>)=${pieceToId["<pad>"]}")
            Log.i("SpTokenizer", "PB: BOS(<s>)=${hfToPbId[pieceToId["<s>"]!!]}, " +
                "EOS(</s>)=${hfToPbId[pieceToId["</s>"]!!]}, UNK(<unk>)=${hfToPbId[pieceToId["<unk>"]!!]}")
            val engId = pieceToId["eng_Latn"]
            val zhoId = pieceToId["zho_Hans"]
            Log.i("SpTokenizer", "eng_Latn: HF=$engId PB=${engId?.let { hfToPbId[it] }}, " +
                "zho_Hans: HF=$zhoId PB=${zhoId?.let { hfToPbId[it] }}")
        }

        /**
         * Load protobuf vocab from sentencepiece.bpe.model and build conversion maps.
         *
         * Protobuf ordering: <unk>=0, <s>=1, </s>=2, then 255997 regular vocab pieces,
         * then language codes at 256000-256205 (206 total).
         *
         * HuggingFace ordering: <s>=0, <pad>=1, </s>=2, <unk>=3, then regular vocab
         * pieces, then language codes at 256004+.
         */
        private fun loadProtobufVocab(pbFile: File) {
            if (!pbFile.exists()) {
                Log.w("SpTokenizer", "Protobuf file not found at ${pbFile.absolutePath}, " +
                    "building conversion from known offsets")
                buildConversionFromOffsets()
                return
            }

            try {
                val pbData = pbFile.readBytes()

                // Parse protobuf ModelProto field 1 (repeated ModelPiece)
                var pos = 0
                val pbPieces = mutableListOf<String>()

                while (pos < pbData.size) {
                    if (pbData[pos] == 0.toByte()) break
                    val fieldNum = pbData[pos].toInt() shr 3
                    val wireType = pbData[pos].toInt() and 0x07
                    pos++

                    if (fieldNum == 1 && wireType == 2) {
                        val (length, newPos) = readVarint(pbData, pos)
                        pos = newPos
                        val end = pos + length.toInt()
                        var pieceStr: String? = null

                        while (pos < end) {
                            val innerField = pbData[pos].toInt() shr 3
                            val innerWire = pbData[pos].toInt() and 0x07
                            pos++
                            if (innerField == 1 && innerWire == 2) {
                                val (slenL, sp) = readVarint(pbData, pos)
                                val slen = slenL.toInt()
                                pos = sp
                                pieceStr = pbData.sliceArray(pos until pos + slen)
                                    .toString(Charsets.UTF_8)
                                pos += slen
                            } else {
                                pos = skipProtobufField(pbData, pos, innerWire)
                            }
                        }
                        pos = end
                        if (pieceStr != null) pbPieces.add(pieceStr)
                    } else {
                        pos = skipProtobufField(pbData, pos, wireType)
                    }
                }

                Log.i("SpTokenizer", "Loaded ${pbPieces.size} pieces from protobuf")

                // Build protobuf piece -> ID map
                pbPieceToId.clear()
                for ((id, piece) in pbPieces.withIndex()) {
                    pbPieceToId[piece] = id
                }

                // Build conversion maps for regular vocab (pieces in both vocabs)
                var mappedCount = 0
                for ((piece, hfId) in pieceToId) {
                    val pbId = pbPieceToId[piece]
                    if (pbId != null) {
                        hfToPbId[hfId] = pbId
                        pbToHfId[pbId] = hfId
                        mappedCount++
                    }
                }
                Log.i("SpTokenizer", "Mapped $mappedCount regular vocab tokens (HF<->protobuf)")

                // Map language code tokens: HF has them at 256004+, protobuf at 256000+
                // Language codes are NOT in protobuf vocab, they're added by the model.
                // In protobuf ordering they're at 256000+, in HF ordering at 256004+.
                var langMapped = 0
                for ((piece, hfId) in pieceToId) {
                    if (hfId >= 256000 && piece !in pbPieceToId) {
                        val pbId = hfId - 4  // HF offset of +4 for inserted <pad>
                        hfToPbId[hfId] = pbId
                        pbToHfId[pbId] = hfId
                        langMapped++
                    }
                }
                Log.i("SpTokenizer", "Mapped $langMapped language code tokens (HF->protobuf offset -4)")

                // Map remaining unmapped HF tokens (e.g., <pad>, <mask>)
                var unmapped = 0
                var nextPbId = pbPieces.size
                for ((piece, hfId) in pieceToId) {
                    if (hfId !in hfToPbId) {
                        hfToPbId[hfId] = nextPbId
                        pbToHfId[nextPbId] = hfId
                        nextPbId++
                        unmapped++
                    }
                }
                if (unmapped > 0) {
                    Log.w("SpTokenizer", "$unmapped HF tokens not in protobuf, " +
                        "mapped to IDs starting at ${nextPbId - unmapped}")
                }

                Log.i("SpTokenizer", "Conversion map: ${hfToPbId.size} HF->PB, " +
                    "${pbToHfId.size} PB->HF mappings")

            } catch (e: Exception) {
                Log.e("SpTokenizer", "Failed to parse protobuf vocab: ${e.message}", e)
                buildConversionFromOffsets()
            }
        }

        /**
         * Fallback: build conversion from known offset rules without parsing protobuf.
         * Protobuf: <unk>=0, <s>=1, </s>=2, vocab[3..255999], lang[256000..256205]
         * HF:       <s>=0, <pad>=1, </s>=2, <unk>=3, vocab[4..256003], lang[256004..256207]
         */
        private fun buildConversionFromOffsets() {
            // Special tokens
            hfToPbId[0] = 1; pbToHfId[1] = 0   // <s>
            hfToPbId[2] = 2; pbToHfId[2] = 2   // </s>
            hfToPbId[3] = 0; pbToHfId[0] = 3   // <unk>
            // <pad> (HF 1) -> protobuf has no PAD; map to protobuf 1 (= <s>)
            // This only matters if the model ever sees PAD, which it shouldn't in inference

            // Regular vocab: HF ID X -> protobuf ID (X - 1) for X >= 4
            for (hfId in 4 until 256000) {
                hfToPbId[hfId] = hfId - 1
                pbToHfId[hfId - 1] = hfId
            }
            // Language codes: HF 256004+ -> protobuf 256000+
            for (hfId in 256004..256207) {
                hfToPbId[hfId] = hfId - 4
                pbToHfId[hfId - 4] = hfId
            }
            Log.i("SpTokenizer", "Built conversion from offset rules (fallback)")
        }

        private fun readVarint(data: ByteArray, pos: Int): Pair<Long, Int> {
            var value = 0L
            var shift = 0
            var p = pos
            while (p < data.size) {
                val b = data[p].toInt() and 0xFF
                p++
                value = value or ((b.toLong() and 0x7F) shl shift)
                shift += 7
                if (b and 0x80 == 0) break
            }
            return value to p
        }

        private fun skipProtobufField(data: ByteArray, pos: Int, wireType: Int): Int {
            return when (wireType) {
                0 -> { // varint
                    var p = pos
                    while (p < data.size && data[p].toInt() and 0x80 != 0) p++
                    p + 1
                }
                2 -> { // length-delimited
                    val (length, newPos) = readVarint(data, pos)
                    newPos + length.toInt()
                }
                5 -> pos + 4  // 32-bit
                1 -> pos + 8  // 64-bit
                else -> pos
            }
        }
    }
}
