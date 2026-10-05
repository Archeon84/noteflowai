package com.noteflowai.app.whisper

import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WhisperNpuEngine(private val context: Context) {

    companion object {
        private const val TAG = "WhisperNpu"
        private const val TOKEN_EOT = 50256
        private const val TOKEN_SOT = 50258
        private const val TOKEN_EN = 50259
        private const val TOKEN_ZH = 50260
        private const val TOKEN_JA = 50266
        private const val TOKEN_KO = 50264
        private const val TOKEN_MS = 50282
        private const val TOKEN_TRANSLATE = 50358
        private const val TOKEN_TRANSCRIBE = 50359
        private const val TOKEN_NOTIMESTAMPS = 50363

        const val CHUNK_DURATION_SEC = 30
        const val SAMPLE_RATE = 16000
        const val OVERLAP_SEC = 1

        private var cachedVocabulary: Array<String>? = null
    }

    private var bridge: WhisperQnnBridge? = null
    private var vocabulary: Array<String> = emptyArray()
    private var isInitialized = false
    private var draftLoadLatch: CountDownLatch? = null
    @Volatile private var isReleasing = false
    val isSpeculativeDecodingEnabled: Boolean
        get() = bridge?.speculativeDecodingEnabled == true

    fun waitForDraftModel(timeoutMs: Long = 10_000L) {
        draftLoadLatch?.await(timeoutMs, TimeUnit.MILLISECONDS)
        draftLoadLatch = null
    }

    fun initialize(encoderPath: String, decoderPath: String, nativeLibDir: String, vocabPath: String, draftEncoderPath: String? = null, draftDecoderPath: String? = null): Boolean {
        try {
            Log.i(TAG, "Initializing NPU engine via Java API...")
            Log.i(TAG, "Encoder: $encoderPath")
            Log.i(TAG, "Decoder: $decoderPath")
            Log.i(TAG, "Native lib dir: $nativeLibDir")
            Log.i(TAG, "Vocab: $vocabPath")
            if (draftEncoderPath != null) Log.i(TAG, "Draft encoder: $draftEncoderPath")
            if (draftDecoderPath != null) Log.i(TAG, "Draft decoder: $draftDecoderPath")

            vocabulary = loadVocabulary(vocabPath)
            Log.i(TAG, "Vocabulary loaded: ${vocabulary.size} tokens")

            val cacheDir = java.io.File(context.filesDir, "onnx_cache").also { it.mkdirs() }

            bridge = WhisperQnnBridge(context)
            val ok = bridge!!.initialize(encoderPath, decoderPath, nativeLibDir, cacheDir, draftEncoderPath, draftDecoderPath)
            if (!ok) {
                Log.e(TAG, "Java API bridge init failed")
                bridge = null
                return false
            }

            isInitialized = true
            Log.i(TAG, "NPU engine initialized successfully via Java API")

            // Load draft model asynchronously — don't block main model readiness
            if (draftEncoderPath != null && draftDecoderPath != null &&
                java.io.File(draftEncoderPath).exists() && java.io.File(draftDecoderPath).exists()) {
                draftLoadLatch = CountDownLatch(1)
                Thread {
                    try {
                        // Use safe call — release() may null bridge from another thread
                        val b = bridge
                        if (b != null && !isReleasing) {
                            b.loadDraftModel(cacheDir, draftEncoderPath, draftDecoderPath)
                            Log.i(TAG, "Draft model loaded successfully, speculative=${b.speculativeDecodingEnabled}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Draft model async load failed: ${e.message}")
                    } finally {
                        draftLoadLatch?.countDown()
                    }
                }.start()
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed: ${e.javaClass.simpleName}: ${e.message}", e)
            release()
            return false
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    fun transcribe(wavPath: String, language: String = ""): String {
        if (isReleasing || !isInitialized || bridge == null) return ""

        // Memory guard: check free heap before attempting transcription
        // The whisper model + audio buffer + mel spectrogram can exceed device limits
        val runtime = Runtime.getRuntime()
        runtime.gc()
        val freeMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
        Log.i(TAG, "Free heap before transcription: ${freeMB}MB")
        if (freeMB < 200) {
            Log.e(TAG, "Insufficient memory for transcription (${freeMB}MB free, need ~200MB). Aborting.")
            return "Error: Insufficient memory for transcription. Close other apps and try again."
        }

        // Logging-only battery verification - tracks if device stays awake during transcription
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wasInteractive = pm.isInteractive
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "Battery verify: interactive=$wasInteractive screenOn=$wasInteractive")

        // Acquire wake lock for long-running NPU inference to prevent device sleep
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NoteFlowAI::NpuTranscription")
        wakeLock?.acquire()
        Log.i(TAG, "Acquired wake lock for NPU transcription")

        try {
            val pcmf = loadWav(wavPath) ?: return ""

            val peak = pcmf.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
            val rms = kotlin.math.sqrt(pcmf.map { it * it }.average()).toFloat()
            val duration = pcmf.size.toFloat() / SAMPLE_RATE
            val nonZero = pcmf.count { kotlin.math.abs(it) > 0.001f }
            Log.i(TAG, "PCM: samples=${pcmf.size} duration=${String.format("%.1f", duration)}s peak=$peak rms=$rms nonZeroRatio=${String.format("%.2f", nonZero.toFloat() / pcmf.size)}")

            val langToken = getLanguageToken(language)

            if (duration <= CHUNK_DURATION_SEC + 2f) {
                return transcribeChunk(pcmf, duration, langToken)
            }

            Log.i(TAG, "Audio ${String.format("%.1f", duration)}s exceeds ${CHUNK_DURATION_SEC}s, splitting into chunks...")
            return transcribeChunked(pcmf, duration, langToken)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            return ""
        } finally {
            val elapsed = System.currentTimeMillis() - startTime
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val stillInteractive = pm.isInteractive
            Log.i(TAG, "Battery verify: elapsed=${elapsed}ms interactive=$stillInteractive (was $wasInteractive)")

            // Release wake lock
            wakeLock?.release()
            wakeLock = null
            Log.i(TAG, "Released wake lock after NPU transcription")
        }
    }

    private fun transcribeChunked(pcmf: FloatArray, totalDuration: Float, langToken: Int): String {
        val totalStartTime = System.currentTimeMillis()
        // Adaptive chunk duration based on device capability
        val chunkDurationSec = adaptChunkDuration()
        val chunkSamples = (chunkDurationSec * SAMPLE_RATE).toInt()
        val overlapSamples = OVERLAP_SEC * SAMPLE_RATE

        // Find silence boundaries for better chunk edges
        val silenceBoundaries = findSilenceBoundaries(pcmf, chunkSamples, overlapSamples)

        Log.i(TAG, "Splitting into chunks: ${chunkDurationSec}s each, ${OVERLAP_SEC}s overlap, ${silenceBoundaries.size} silence boundaries found")

        val chunkResults = mutableListOf<String>()

        for (i in silenceBoundaries.indices) {
            val startSample = silenceBoundaries[i].first
            val endSample = silenceBoundaries[i].second
            if (startSample >= endSample || startSample >= pcmf.size) continue

            val chunkPcm = pcmf.copyOfRange(startSample, endSample.coerceAtMost(pcmf.size))
            val chunkDuration = chunkPcm.size.toFloat() / SAMPLE_RATE

            if (chunkPcm.all { kotlin.math.abs(it) < 0.001f }) {
                Log.i(TAG, "Chunk $i: all silence, skipping")
                continue
            }

            val chunkStartTime = System.currentTimeMillis()
            Log.i(TAG, "Chunk $i/${silenceBoundaries.size}: ${String.format("%.1f", startSample.toFloat() / SAMPLE_RATE)}s - ${String.format("%.1f", endSample.toFloat() / SAMPLE_RATE)}s (${String.format("%.1f", chunkDuration)}s)")
            val chunkText = transcribeChunk(chunkPcm, chunkDuration, langToken)
            val chunkElapsed = System.currentTimeMillis() - chunkStartTime
            if (chunkText.isNotBlank()) {
                chunkResults.add(chunkText)
                val rtf = if (chunkDuration > 0) String.format("%.2f", chunkElapsed.toFloat() / (chunkDuration * 1000)) else "N/A"
                Log.i(TAG, "Chunk $i result (${chunkElapsed}ms, RTF=$rtf): '${chunkText.take(100)}...'")
            }
        }

        if (chunkResults.isEmpty()) return ""

        val merged = mergeChunkResults(chunkResults)
        val totalElapsed = System.currentTimeMillis() - totalStartTime
        val rtf = if (totalDuration > 0) String.format("%.2f", totalElapsed.toFloat() / (totalDuration * 1000)) else "N/A"
        Log.i(TAG, "Merged ${chunkResults.size} chunks (${totalElapsed}ms total, RTF=$rtf): '${merged.take(200)}...'")
        return merged
    }

    private fun adaptChunkDuration(): Float {
        val runtime = Runtime.getRuntime()
        val availableMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
        val numCores = runtime.availableProcessors()

        return when {
            numCores >= 8 && availableMB > 500 -> 45f   // High-end: larger chunks
            numCores >= 6 && availableMB > 300 -> 35f   // Mid-range: slightly larger
            else -> 25f                                  // Low-end: smaller to avoid OOM
        }
    }

    private fun findSilenceBoundaries(pcmf: FloatArray, chunkSamples: Int, overlapSamples: Int): List<Pair<Int, Int>> {
        val boundaries = mutableListOf<Pair<Int, Int>>()
        val strideSamples = chunkSamples - overlapSamples
        val silenceThreshold = 0.002f  // RMS energy below this = silence
        val minSilenceDuration = 0.15f // 150ms of silence to consider as boundary
        val minSilenceSamples = (minSilenceDuration * SAMPLE_RATE).toInt()

        // Pre-scan for silence regions
        val isSilent = BooleanArray(pcmf.size)
        val windowSize = 1600 // 100ms window
        for (i in pcmf.indices step windowSize) {
            val end = (i + windowSize).coerceAtMost(pcmf.size)
            var sumSq = 0.0
            for (j in i until end) {
                sumSq += pcmf[j].toDouble() * pcmf[j].toDouble()
            }
            val rms = kotlin.math.sqrt(sumSq / (end - i)).toFloat()
            val silent = rms < silenceThreshold
            for (j in i until end) {
                isSilent[j] = silent
            }
        }

        // Find chunk boundaries aligned to silence gaps
        var pos = 0
        while (pos < pcmf.size) {
            var chunkEnd = (pos + chunkSamples).coerceAtMost(pcmf.size)

            // Try to extend or trim to nearest silence boundary
            val searchWindow = (strideSamples * 0.3).toInt() // Search ±30% of stride
            val idealEnd = (pos + chunkSamples).coerceAtMost(pcmf.size)

            // Look for silence near the end of the chunk
            var bestEnd = chunkEnd
            var bestSilenceLen = 0
            val searchStart = (idealEnd - searchWindow).coerceAtLeast(pos + strideSamples / 2)
            val searchEnd = (idealEnd + searchWindow).coerceAtMost(pcmf.size)

            for (s in searchStart until searchEnd - minSilenceSamples) {
                // Check if there's a silence gap starting here
                var silenceLen = 0
                for (j in s until (s + minSilenceSamples * 2).coerceAtMost(pcmf.size)) {
                    if (isSilent[j]) silenceLen++ else break
                }
                if (silenceLen >= minSilenceSamples && silenceLen > bestSilenceLen) {
                    bestEnd = s + silenceLen / 2  // Split in middle of silence
                    bestSilenceLen = silenceLen
                }
            }

            chunkEnd = bestEnd.coerceAtMost(pcmf.size)

            // If we're near the end and the remaining audio is very short, merge into this chunk
            val remainingAfter = pcmf.size - chunkEnd
            if (remainingAfter > 0 && remainingAfter < strideSamples / 2) {
                chunkEnd = pcmf.size
            }

            boundaries.add(pos to chunkEnd)
            pos = chunkEnd - overlapSamples
            if (pos < 0) pos = 0
            if (pos >= pcmf.size - overlapSamples) break
        }

        if (boundaries.isEmpty()) {
            boundaries.add(0 to pcmf.size.coerceAtMost(chunkSamples))
        }

        return boundaries
    }

    private fun mergeChunkResults(chunks: List<String>): String {
        if (chunks.size == 1) return chunks[0]

        val sb = StringBuilder()
        for (i in chunks.indices) {
            var text = chunks[i].trim()
            if (text.isEmpty()) continue

            if (i > 0 && sb.isNotEmpty()) {
                // Check up to 8 words for overlap (covers 25% overlap with 32-word chunks)
                val prevWords = sb.toString().trim().split(Regex("\\s+")).takeLast(8)
                val currWords = text.split(Regex("\\s+"))
                val overlapWords = findOverlapWords(prevWords, currWords)
                if (overlapWords > 0) {
                    text = currWords.drop(overlapWords).joinToString(" ")
                }
                if (text.isNotBlank()) {
                    sb.append(" ")
                }
            }
            sb.append(text)
        }
        return sb.toString().trim()
    }

    private fun findOverlapWords(prev: List<String>, curr: List<String>): Int {
        var maxOverlap = 0
        for (len in 1..minOf(prev.size, curr.size, 8)) {
            val prevTail = prev.takeLast(len).map { it.lowercase().trim().replace(Regex("[^a-z0-9]"), "") }
            val currHead = curr.take(len).map { it.lowercase().trim().replace(Regex("[^a-z0-9]"), "") }
            if (prevTail == currHead) {
                maxOverlap = len
            }
        }
        return maxOverlap
    }

    private fun transcribeChunk(pcmf: FloatArray, duration: Float, langToken: Int): String {
        val mel = try {
            WhisperMelSpectrogram.compute(pcmf)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM computing mel spectrogram", e)
            return ""
        }
        Log.i(TAG, "Transcribing chunk via ONNX NNAPI... mel size=${mel.size}, lang=$langToken, duration=${String.format("%.1f", duration)}s")

        // Silence detection: check mel energy to skip silent chunks
        var melEnergy = 0f
        for (v in mel) { melEnergy += v * v }
        melEnergy /= mel.size.coerceAtLeast(1)
        Log.i(TAG, "Mel energy: ${String.format("%.6f", melEnergy)}")
        if (melEnergy < 0.0001f) {
            Log.i(TAG, "Chunk is silent (energy=$melEnergy < 0.0001), skipping")
            return ""
        }

        val b = bridge ?: run {
            Log.e(TAG, "Bridge is null — engine was released during transcription")
            return ""
        }
        val tokenIds = try {
            b.transcribe(mel, langToken, duration)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during ONNX inference", e)
            System.gc()
            null
        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference failed", e)
            null
        }

        if (tokenIds == null || tokenIds.isEmpty()) {
            Log.e(TAG, "Transcription returned null/empty")
            return ""
        }

        Log.i(TAG, "Token IDs (first 20): ${tokenIds.take(20).toList()}")
        val text = decodeTokens(tokenIds.toList())
        val cleaned = cleanTranscription(text)
        Log.i(TAG, "ONNX result (${tokenIds.size} tokens): '${cleaned.take(200)}'")

        // Confidence check: filter out likely hallucinated short results
        if (cleaned.isNotBlank() && tokenIds.size <= 3) {
            val words = cleaned.trim().split(Regex("\\s+"))
            val uniqueWords = words.map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }.filter { it.isNotEmpty() }.toSet()
            // If very few unique words and short, likely hallucination
            if (uniqueWords.size <= 1 && words.size <= 3) {
                Log.w(TAG, "Low confidence result (${tokenIds.size} tokens, ${uniqueWords.size} unique words), treating as hallucination: '$cleaned'")
                return ""
            }
        }
        return cleaned
    }

    private fun decodeTokens(tokens: List<Int>): String {
        val sb = StringBuilder()
        for (token in tokens) {
            if (token == TOKEN_EOT) break
            if (token < vocabulary.size) {
                var text = vocabulary[token]
                // Whisper BPE uses Ä  (U+0120) to represent leading space
                text = text.replace("\u0120", " ")
                // Skip tokens that are only non-ASCII special chars (hallucination artifacts)
                val stripped = text.trim()
                if (stripped.isNotEmpty() && stripped.all { it.code > 127 || it == ' ' || it == '\t' }) {
                    continue
                }
                sb.append(text)
            }
        }
        return sb.toString().trim()
    }

    private fun cleanTranscription(text: String): String {
        // Strip Whisper hallucination tokens from end of transcription
        // Common hallucinations: [Music], (music), (I'm not sure..., etc.
        var cleaned = text
        // Strip complete bracket/parenthesis tokens from the end, iteratively
        val endPattern = Regex("""\s*[\[\(][A-Za-z\s'\.,!?]+[\]\)]\s*$""")
        var prev = ""
        while (cleaned != prev) {
            prev = cleaned
            cleaned = endPattern.replace(cleaned, "")
        }
        // Strip incomplete trailing bracket/parenthesis (no closing bracket)
        val openPattern = Regex("""\s*[\[\(][A-Za-z\s'\.,!?]*\s*$""")
        prev = ""
        while (cleaned != prev) {
            prev = cleaned
            cleaned = openPattern.replace(cleaned, "")
        }
        // Strip trailing comma/period
        cleaned = cleaned.trimEnd(',', '.', ' ')

        // Short-phrase repetition detection (e.g. "I... I... I... I..." or "oh baby, oh baby, oh baby")
        val words = cleaned.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size >= 6) {
            for (phraseLen in 1..(words.size / 3)) {
                var repeats = 0
                var start = 0
                while (start + phraseLen * 2 <= words.size) {
                    val phrase = words.subList(start, start + phraseLen).joinToString(" ").lowercase().replace(Regex("[^a-z0-9]"), "")
                    val next = words.subList(start + phraseLen, minOf(start + phraseLen * 2, words.size)).joinToString(" ").lowercase().replace(Regex("[^a-z0-9]"), "")
                    if (phrase == next && phrase.length >= 2) {
                        repeats++
                        start += phraseLen
                    } else {
                        break
                    }
                }
                if (repeats >= 2) {
                    val cutoff = start
                    Log.i(TAG, "Short-phrase repetition detected: phrase_len=$phraseLen repeats=$repeats, cutting at word $cutoff/${words.size}")
                    cleaned = words.take(cutoff).joinToString(" ")
                    break
                }
            }
        }

        return cleaned.trim()
    }

    private fun getLanguageToken(lang: String): Int {
        return when (lang.lowercase()) {
            "en", "english" -> TOKEN_EN
            "zh", "chinese", "mandarin" -> TOKEN_ZH
            "ja", "japanese" -> TOKEN_JA
            "ko", "korean" -> TOKEN_KO
            "ms", "malay" -> TOKEN_MS
            else -> TOKEN_EN
        }
    }

    private fun loadVocabulary(path: String): Array<String> {
        cachedVocabulary?.let {
            Log.i(TAG, "Using cached vocabulary (${it.size} tokens)")
            return it
        }
        try {
            val jsonStr = try {
                context.assets.open(path).bufferedReader().readText()
            } catch (e: Exception) {
                java.io.File(path).readText()
            }
            val jsonObj = org.json.JSONObject(jsonStr)

            var maxId = 0
            for (key in jsonObj.keys()) {
                val id = try { jsonObj.getInt(key) } catch (e: Exception) { continue }
                if (id > maxId) maxId = id
            }

            val vocab = Array(maxId + 1) { "" }
            var loaded = 0
            for (key in jsonObj.keys()) {
                val id = try { jsonObj.getInt(key) } catch (e: Exception) { continue }
                if (id in vocab.indices) {
                    vocab[id] = key
                    loaded++
                }
            }
            // Extend vocabulary to include special tokens beyond vocab.json range
            val specialTokens = mapOf(
                50256 to "<|endoftext|>",
                50258 to "<|startoftranscript|>",
                50259 to "<|en|>",
                50260 to "<|zh|>",
                50264 to "<|ko|>",
                50266 to "<|ja|>",
                50282 to "<|ms|>",
                50358 to "<|translate|>",
                50359 to "<|transcribe|>",
                50360 to "<|transcribe|>",
                50361 to "<|transcribe|>",
                50362 to "<|transcribe|>",
                50363 to "<|notimestamps|>",
                50364 to "<|0.00|>",
                50365 to "<|0.02|>"
            )
            val needed = specialTokens.keys.maxOrNull() ?: 0
            if (vocab.size <= needed) {
                val extended = Array(needed + 1) { i -> specialTokens[i] ?: vocab.getOrElse(i) { "" } }
                Log.i(TAG, "Vocabulary extended: ${vocab.size} -> ${extended.size} (added special tokens)")
                cachedVocabulary = extended
                return extended
            }

            Log.i(TAG, "Vocabulary: $loaded tokens mapped, max_id=$maxId")
            cachedVocabulary = vocab
            return vocab
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vocabulary from $path", e)
            return emptyArray()
        }
    }

    private fun loadWav(path: String): FloatArray? {
        try {
            val file = java.io.File(path)
            if (!file.exists()) return null

            val bytes = file.readBytes()
            if (bytes.size < 44) return null

            val channels = java.nio.ByteBuffer.wrap(bytes, 22, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
            val sampleRate = java.nio.ByteBuffer.wrap(bytes, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            val bitsPerSample = java.nio.ByteBuffer.wrap(bytes, 34, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()

            if (channels != 1 || sampleRate != 16000 || bitsPerSample != 16) {
                Log.e(TAG, "WAV must be 16kHz mono 16-bit. Got: ${sampleRate}Hz ${channels}ch ${bitsPerSample}bit")
                return null
            }

            val dataOffset = findDataChunk(bytes)
            if (dataOffset < 0) return null

            // Direct byte→float conversion: no intermediate ShortArray needed
            val dataLen = bytes.size - dataOffset
            val numSamples = dataLen / 2
            val buf = java.nio.ByteBuffer.wrap(bytes, dataOffset, dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)

            // First pass: find max amplitude for normalization
            var maxAmp = 1
            val pcmf = FloatArray(numSamples)
            for (i in 0 until numSamples) {
                val s = buf.short.toInt()
                val abs = kotlin.math.abs(s)
                if (abs > maxAmp) maxAmp = abs
                pcmf[i] = s.toFloat()
            }
            buf.clear() // help GC

            val scale = if (maxAmp < 16384) 0.7f * 32768f / maxAmp.coerceAtLeast(1) else 1f
            for (i in pcmf.indices) {
                pcmf[i] = (pcmf[i] / 32768f * scale).coerceIn(-1f, 1f)
            }
            return pcmf
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load WAV", e)
            return null
        }
    }

    private fun findDataChunk(bytes: ByteArray): Int {
        var offset = 12
        while (offset < bytes.size - 8) {
            val chunkId = String(bytes, offset, 4)
            val chunkSize = java.nio.ByteBuffer.wrap(bytes, offset + 4, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "data") return offset + 8
            offset += 8 + chunkSize
        }
        return -1
    }

    fun release() {
        // Signal releasing first so concurrent transcribe() calls bail out
        isReleasing = true
        // Capture locally — transcribe() may still hold a reference
        val b = bridge
        try {
            b?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Release error", e)
        }
        bridge = null
        isInitialized = false
    }
}