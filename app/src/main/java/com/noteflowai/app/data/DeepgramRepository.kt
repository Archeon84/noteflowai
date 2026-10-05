package com.noteflowai.app.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import java.io.File
import kotlin.math.pow
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.resume

class DeepgramRepository {
    private val client = NetworkModule.newClientBuilder()
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // File transcription result cache: MD5(file bytes) -> transcript
    private val fileTranscriptionCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 100
        }
    }

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isWebSocketActive = false
    private var reconnectJob: kotlinx.coroutines.Job? = null
    @Volatile private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private var lastApiKey: String? = null
    private var lastLanguage: String = "multi"
    
    // Shared flow to emit transcript chunks with finality flag
    private val _transcriptFlow = MutableSharedFlow<DeepgramResult>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val transcriptFlow: Flow<DeepgramResult> = _transcriptFlow

    // Connection state flow for UI
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val slangKeywords = listOf(
        "lah", "je", "jom", "gila", "kantoi",
        "cincai", "tak", "nak", "dah", "ke", "eh"
    )

    enum class ConnectionState {
        Disconnected,
        Connecting,
        Connected,
        Reconnecting,
        Error
    }

    suspend fun transcribeFile(file: File, apiKey: String, language: String = "en"): String {
        if (!file.exists()) {
            throw java.io.FileNotFoundException("Audio file not found: ${file.absolutePath}")
        }
        if (file.length() == 0L) {
            throw java.io.IOException("Audio file is empty: ${file.absolutePath}")
        }
        // Check file transcription cache (by streaming MD5 — avoids loading entire file into memory)
        val fileHash = file.inputStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val cacheKey = "$fileHash|$language"
        synchronized(fileTranscriptionCache) {
            val cached = fileTranscriptionCache[cacheKey]
            if (cached != null) {
                android.util.Log.i("Deepgram", "File transcription cache HIT for ${file.name}")
                return cached
            }
        }

        val contentType = when {
            file.name.endsWith(".mp3", true) -> "audio/mp3"
            file.name.endsWith(".ogg", true) -> "audio/ogg"
            file.name.endsWith(".opus", true) -> "audio/ogg"
            file.name.endsWith(".flac", true) -> "audio/flac"
            else -> "audio/wav"
        }

        // Preprocess WAV files: trim silence + normalize amplitude
        val fileToSend = if (contentType == "audio/wav") {
            preprocessWav(file) ?: file
        } else {
            file
        }

        val requestBody = fileToSend.asRequestBody(contentType.toMediaType())
        // Use nova-2 for single-language (faster), nova-3 for multi-language
        val model = if (language == "multi" || language.isEmpty()) "nova-3" else "nova-2"
        val urlBuilder = "https://api.deepgram.com/v1/listen".toHttpUrl().newBuilder()
            .addQueryParameter("model", model)
            .addQueryParameter("language", language)
            .addQueryParameter("smart_format", "true")

        // nova-3 uses "keyterm", nova-2 uses "keywords"
        val keywordParam = if (model == "nova-3") "keyterm" else "keywords"
        slangKeywords.forEach { urlBuilder.addQueryParameter(keywordParam, "$it:3") }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .addHeader("Authorization", "Token $apiKey")
            .post(requestBody)
            .build()

        android.util.Log.i("Deepgram", "Starting file transcription: ${file.name} (${file.length()} bytes, $contentType)")

        // Adaptive timeout: larger files need longer read timeout
        // ~30s base + 1s per 100KB of audio, capped at 10 minutes
        val fileSizeMB = file.length() / (1024 * 1024)
        val readTimeoutSec = (30 + fileSizeMB * 10).coerceIn(60, 600).toInt()
        val adaptiveClient = client.newBuilder()
            .readTimeout(readTimeoutSec.toLong(), TimeUnit.SECONDS)
            .build()
        android.util.Log.i("Deepgram", "Adaptive timeout: ${readTimeoutSec}s for ${fileSizeMB}MB file")

        val startTime = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            retryWithBackoff(maxAttempts = 3, baseDelayMs = 1000) {
                val call = adaptiveClient.newCall(request)
                try {
                    val response = call.execute()
                    val elapsed = System.currentTimeMillis() - startTime
                    android.util.Log.i("Deepgram", "Response: HTTP ${response.code} (${elapsed}ms)")
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "no body"
                        android.util.Log.e("Deepgram", "Error body: $errorBody")
                        val code = response.code
                        if (code in 400..499 && code != 429) {
                            throw java.io.IOException("HTTP $code: ${response.message} (not retriable)")
                        }
                        throw java.io.IOException("HTTP ${response.code}: ${response.message}")
                    }
                    val json = response.body?.string() ?: ""
                    android.util.Log.i("Deepgram", "Response body: ${json.length} chars")
                    val res = gson.fromJson(json, DeepgramResponse::class.java)
                    val alt = res.results.channels.firstOrNull()?.alternatives?.firstOrNull()
                    val transcript = alt?.paragraphs?.transcript
                        ?: alt?.transcript
                        ?: ""
                    val totalElapsed = System.currentTimeMillis() - startTime
                    val wpm = if (totalElapsed > 0 && transcript.isNotBlank()) String.format("%.0f", transcript.split(Regex("\\s+")).size * 60000.0 / totalElapsed) else "N/A"
                    android.util.Log.i("Deepgram", "Transcript length: ${transcript.length} chars, ${elapsed}ms, ~${wpm} wpm equivalent")
                    // Store in file transcription cache
                    if (transcript.isNotBlank()) {
                        synchronized(fileTranscriptionCache) {
                            fileTranscriptionCache[cacheKey] = transcript
                        }
                    }
                    transcript
                } finally {
                    // Clean up preprocessed temp file
                    if (fileToSend != file && fileToSend.exists()) {
                        fileToSend.delete()
                    }
                }
            }
        }
    }

    private fun preprocessWav(file: File): File? {
        try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return null

            // Parse WAV header
            val channels = java.nio.ByteBuffer.wrap(bytes, 22, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
            val sampleRate = java.nio.ByteBuffer.wrap(bytes, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            val bitsPerSample = java.nio.ByteBuffer.wrap(bytes, 34, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()

            if (channels != 1 || sampleRate != 16000 || bitsPerSample != 16) {
                android.util.Log.d("Deepgram", "Preprocessing skipped: ${sampleRate}Hz ${channels}ch ${bitsPerSample}bit (need 16kHz mono 16-bit)")
                return null
            }

            // Find data chunk
            var dataOffset = 12
            var dataSize = 0
            while (dataOffset < bytes.size - 8) {
                val chunkId = String(bytes, dataOffset, 4)
                val chunkSize = java.nio.ByteBuffer.wrap(bytes, dataOffset + 4, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                if (chunkId == "data") {
                    dataSize = chunkSize
                    dataOffset += 8
                    break
                }
                dataOffset += 8 + chunkSize
            }
            if (dataSize == 0) return null

            // Read PCM samples
            val numSamples = dataSize / 2
            val samples = ShortArray(numSamples)
            java.nio.ByteBuffer.wrap(bytes, dataOffset, dataSize)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer().get(samples)

            // 1. Normalize amplitude to -1 dBFS
            var maxAmp = 0
            for (s in samples) {
                val abs = kotlin.math.abs(s.toInt())
                if (abs > maxAmp) maxAmp = abs
            }
            val targetLevel = (32767 * 0.89).toInt() // -1 dBFS
            val normalized = if (maxAmp > 0 && maxAmp < targetLevel) {
                val scale = targetLevel.toFloat() / maxAmp
                ShortArray(samples.size) { i ->
                    (samples[i].toFloat() * scale).toInt().coerceIn(-32768, 32767).toShort()
                }
            } else {
                samples
            }

            // 2. Trim silence from start and end (VAD)
            val silenceThreshold: Short = 500 // ~35 dB below full scale
            val minSpeechSamples = (0.05f * sampleRate).toInt() // 50ms minimum speech

            var startIdx = 0
            for (i in normalized.indices) {
                if (kotlin.math.abs(normalized[i].toInt()) > silenceThreshold) {
                    startIdx = (i - minSpeechSamples).coerceAtLeast(0)
                    break
                }
                if (i == normalized.lastIndex) {
                    // All silence
                    android.util.Log.d("Deepgram", "Audio is all silence, skipping preprocess")
                    return null
                }
            }

            var endIdx = normalized.size
            for (i in normalized.indices.reversed()) {
                if (kotlin.math.abs(normalized[i].toInt()) > silenceThreshold) {
                    endIdx = (i + minSpeechSamples).coerceAtMost(normalized.size)
                    break
                }
            }

            if (startIdx == 0 && endIdx == normalized.size) {
                android.util.Log.d("Deepgram", "No trimming needed")
                return null // No changes needed
            }

            val trimmed = normalized.copyOfRange(startIdx, endIdx)
            android.util.Log.i("Deepgram", "Preprocessed: trimmed ${startIdx} samples from start, ${normalized.size - endIdx} from end, normalized ${maxAmp}/${targetLevel}")

            // Write preprocessed WAV
            val preprocessedFile = File(file.parent, "preprocessed_${file.name}")
            val header = ByteArray(44)
            // RIFF header
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            val dataSizeBytes = trimmed.size * 2
            val fileSize = 36 + dataSizeBytes
            java.nio.ByteBuffer.wrap(header, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(fileSize)
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            // fmt chunk
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            java.nio.ByteBuffer.wrap(header, 16, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(16)
            java.nio.ByteBuffer.wrap(header, 20, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(1) // PCM
            java.nio.ByteBuffer.wrap(header, 22, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(1) // mono
            java.nio.ByteBuffer.wrap(header, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(sampleRate)
            java.nio.ByteBuffer.wrap(header, 28, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(sampleRate * 2) // byte rate
            java.nio.ByteBuffer.wrap(header, 32, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(2) // block align
            java.nio.ByteBuffer.wrap(header, 34, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(16) // bits per sample
            // data chunk
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            java.nio.ByteBuffer.wrap(header, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(dataSizeBytes)

            preprocessedFile.writeBytes(header)
            java.io.FileOutputStream(preprocessedFile, true).use { fos ->
                val buffer = java.nio.ByteBuffer.allocate(dataSizeBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (s in trimmed) buffer.putShort(s)
                fos.write(buffer.array())
            }

            return preprocessedFile
        } catch (e: Exception) {
            android.util.Log.w("Deepgram", "Audio preprocessing failed: ${e.message}")
            return null
        }
    }

    private suspend fun <T> retryWithBackoff(maxAttempts: Int, baseDelayMs: Long, block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (e.message?.contains("not retriable") == true) {
                    throw e
                }
                lastException = e
                if (attempt < maxAttempts - 1) {
                    val delayMs = baseDelayMs * (2.0).pow(attempt).toLong()
                    val jitter = (0..500).random()
                    delay(delayMs + jitter)
                }
            }
        }
        throw lastException ?: RuntimeException("Unknown error")
    }

    fun startRealTime(apiKey: String, language: String = "multi") {
        lastApiKey = apiKey
        lastLanguage = language
        reconnectAttempts = 0
        _connectionState.value = ConnectionState.Connecting
        isWebSocketActive = true
        startAudioDrain()

        try {
            val streamModel = if (language == "multi" || language.isEmpty()) "nova-3" else "nova-2"
            val httpUrl = "https://api.deepgram.com/v1/listen".toHttpUrl().newBuilder()
                .addQueryParameter("model", streamModel)
                .addQueryParameter("language", language)
                .addQueryParameter("encoding", "linear16")
                .addQueryParameter("sample_rate", "16000")
                .addQueryParameter("channels", "1")
                .addQueryParameter("interim_results", "true")
                .addQueryParameter("endpointing", "300")
                .addQueryParameter("utterance_end_ms", "1000")
            
            // nova-3 uses "keyterm", nova-2 uses "keywords"
            val keywordParam = if (streamModel == "nova-3") "keyterm" else "keywords"
            slangKeywords.forEach { kw -> httpUrl.addQueryParameter(keywordParam, kw) }
            
            val wssUrl = httpUrl.build().toString().replace("https://", "wss://")

            val request = Request.Builder()
                .url(wssUrl)
                .addHeader("Authorization", "Token $apiKey")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectAttempts = 0
                    _connectionState.value = ConnectionState.Connected
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val response = gson.fromJson(text, DeepgramStreamResponse::class.java)
                        val transcript = response.channel?.alternatives?.firstOrNull()?.transcript
                        if (!transcript.isNullOrEmpty()) {
                            _transcriptFlow.tryEmit(DeepgramResult(transcript, response.isFinal == true))
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("Deepgram", "Parse error: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val errorBody = try { response?.body?.string() } catch (_: Exception) { null }
                    val errorMsg = response?.let { "HTTP ${it.code}: ${it.message}" } ?: t.message
                    val fullError = if (!errorBody.isNullOrBlank()) "$errorMsg — $errorBody" else errorMsg
                    android.util.Log.e("Deepgram", "WebSocket failed: $fullError")
                    _transcriptFlow.tryEmit(DeepgramResult("Error: $fullError", true))
                    _connectionState.value = ConnectionState.Error

                    if (isWebSocketActive) {
                        scheduleReconnect()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (isWebSocketActive && code != 1000) {
                        _connectionState.value = ConnectionState.Reconnecting
                        scheduleReconnect()
                    } else {
                        isWebSocketActive = false
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            })
        } catch (e: Exception) {
            _transcriptFlow.tryEmit(DeepgramResult("Setup Error: ${e.message}", true))
            _connectionState.value = ConnectionState.Error
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        if (reconnectAttempts >= maxReconnectAttempts) {
            _transcriptFlow.tryEmit(DeepgramResult("Error: Max reconnection attempts reached", true))
            _connectionState.value = ConnectionState.Error
            isWebSocketActive = false
            return
        }
        
        reconnectAttempts++
        _connectionState.value = ConnectionState.Reconnecting
        
        val delay = (2.0 * reconnectAttempts).coerceAtMost(30.0).toLong()
        reconnectJob = scope.launch {
            delay(delay * 1000)
            if (isWebSocketActive) {
                lastApiKey?.let { startRealTime(it, lastLanguage) }
            }
        }
    }

    // Audio batch buffer: fixed-size buffer avoids reallocation on every chunk
    private val AUDIO_BATCH_SIZE_BYTES = 6400 // Send every 200ms at 16kHz 16-bit mono (6400 bytes)
    private val audioBatchBuffer = ByteArray(AUDIO_BATCH_SIZE_BYTES)
    private var audioBatchPos = 0

    // Non-blocking audio send channel: audio callback enqueues here, background coroutine drains to WebSocket.
    // Prevents webSocket.send() from blocking the audio recorder thread.
    // Buffer of 64 batches (~400KB) handles burst without backpressure on audio thread.
    // Recreated on each startAudioDrain() because Channel.close() is one-shot.
    @Volatile private var audioSendChannel = Channel<ByteArray>(64)
    @Volatile private var audioDrainJob: kotlinx.coroutines.Job? = null

    private fun startAudioDrain() {
        audioDrainJob?.cancel()
        audioSendChannel.close() // close previous channel if any
        audioSendChannel = Channel(64)
        audioDrainJob = scope.launch {
            for (batch in audioSendChannel) {
                try {
                    webSocket?.send(batch.toByteString())
                } catch (e: Exception) {
                    android.util.Log.w("Deepgram", "Audio drain send error: ${e.message}")
                }
            }
        }
    }

    private fun stopAudioDrain() {
        audioDrainJob?.cancel()
        audioDrainJob = null
    }

    fun sendAudioChunk(data: ByteArray) {
        if (data.isEmpty()) return

        // Batching still happens here (cheap arraycopy on audio thread).
        // Only the WebSocket send is moved to the background drain coroutine.
        synchronized(audioBatchBuffer) {
            var offset = 0
            var remaining = data.size
            while (remaining > 0) {
                val space = AUDIO_BATCH_SIZE_BYTES - audioBatchPos
                val copyLen = minOf(space, remaining)
                if (copyLen > 0) {
                    System.arraycopy(data, offset, audioBatchBuffer, audioBatchPos, copyLen)
                    audioBatchPos += copyLen
                    offset += copyLen
                    remaining -= copyLen
                }
                if (audioBatchPos >= AUDIO_BATCH_SIZE_BYTES) {
                    audioSendChannel.trySend(audioBatchBuffer.copyOf())
                    audioBatchPos = 0
                }
            }
        }
    }

    fun flushAudioBatch() {
        synchronized(audioBatchBuffer) {
            if (audioBatchPos > 0) {
                audioSendChannel.trySend(audioBatchBuffer.copyOf(audioBatchPos))
                audioBatchPos = 0
            }
        }
    }

    fun stopRealTime() {
        isWebSocketActive = false
        reconnectJob?.cancel()
        flushAudioBatch()
        // Cancel drain immediately — remaining channel data is stale audio
        // from a stopped recording, not worth blocking the main thread to send.
        stopAudioDrain()
        audioSendChannel.close()
        webSocket?.close(1000, "Done")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun getConnectionState(): ConnectionState = _connectionState.value
}

data class DeepgramResult(val text: String, val isFinal: Boolean)

data class DeepgramResponse(val results: DeepgramResults)
data class DeepgramResults(val channels: List<DeepgramChannel>)
data class DeepgramChannel(val alternatives: List<DeepgramAlternative>)
data class DeepgramAlternative(
    val transcript: String,
    val paragraphs: DeepgramParagraphs? = null
)

data class DeepgramParagraphs(
    val transcript: String? = null
)

data class DeepgramStreamResponse(
    val channel: DeepgramChannel? = null,
    @SerializedName("is_final") val isFinal: Boolean? = false
)