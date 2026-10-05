package com.noteflowai.app.data.tts

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.noteflowai.app.data.settings.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Deepgram Aura text-to-speech backed by [TtsManager].
 *
 * POSTs text to api.deepgram.com/v1/speak and streams the returned MP3 straight into a
 * [StreamingPlayer] as it arrives, so the first words are audible within a second or two instead
 * of after the whole utterance has been synthesized and downloaded. Used in place of Android's
 * on-device TextToSpeech when a Deepgram API key is configured.
 *
 * Playback state is managed by a [PlaybackStateMachine] and every player/network callback is
 * validated against the play-request generation so a [stop] or a second [play] can never be
 * clobbered by a stale callback. [stop] satisfies the Guide's requirements: cancel the playback
 * coroutine and the network call, close the response body, release the player, clear media
 * sources, abandon audio focus, prevent stale callbacks, and return state to Idle.
 */
class DeepgramTtsManager(
    private val context: Context,
    private val client: OkHttpClient = sharedDeepgramClient(),
    private val playerFactory: () -> StreamingPlayer = { MediaPlayerStreamingPlayer() },
    private val focus: AudioFocusController? = null,
    keyProvider: () -> String = {
        SettingsManager.getInstance(context).deepgramApiKeyBlocking
    },
    modelProvider: () -> String = {
        SettingsManager.getInstance(context).ttsVoiceBlocking
    }
) : TtsManager {

    data class VoiceOption(val model: String, val label: String)

    companion object {
        private const val TAG = "DeepgramTts"
        private const val ENDPOINT = "https://api.deepgram.com/v1/speak"
        const val DEFAULT_MODEL = "aura-2-thalia-en"
        // Aura caps input at 2000 chars; leave margin and split on sentence boundaries.
        private const val MAX_CHUNK_CHARS = 1800

        val VOICES = listOf(
            VoiceOption("aura-2-thalia-en", "Thalia — Female, US"),
            VoiceOption("aura-2-athena-en", "Athena — Female, US"),
            VoiceOption("aura-2-andromeda-en", "Andromeda — Female, US"),
            VoiceOption("aura-2-hera-en", "Hera — Female, US"),
            VoiceOption("aura-2-luna-en", "Luna — Female, US"),
            VoiceOption("aura-2-perseus-en", "Perseus — Male, US"),
            VoiceOption("aura-2-zeus-en", "Zeus — Male, US"),
            VoiceOption("aura-2-orpheus-en", "Orpheus — Male, US"),
            VoiceOption("aura-2-helios-en", "Helios — Male, UK"),
            VoiceOption("aura-2-angus-en", "Angus — Male, Ireland"),
            VoiceOption("aura-2-arcas-en", "Arcas — Male, US"),
        )

        /** Dedicated client, HTTP/1.1 only. Reusing the shared connection pool can hand this
         *  request a connection that previously carried the Deepgram transcription WebSocket, and
         *  HTTP/2 streamed MP3 downloads get reset by network middleboxes (observed as "stream was
         *  reset: CANCEL"). HTTP/1.1 with chunked transfer avoids both. */
        fun sharedDeepgramClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .apply {
                // Enforce Local-Only Mode even on this dedicated transport.
                com.noteflowai.app.data.NetworkModule.firewallInterceptor()?.let { addInterceptor(it) }
            }
            .build()
    }

    private data class SpeakRequest(val text: String)

    private val gson = Gson()
    private val machine = PlaybackStateMachine()
    override val state: StateFlow<PlaybackState> = machine.state

    private val keyProvider = keyProvider
    private val modelProvider = modelProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playReqJob: Job? = null
    private var player: StreamingPlayer? = null

    @Volatile private var activeCall: Call? = null
    @Volatile private var isPausedRequested = false

    init {
        focus?.let { f ->
            f.onLossTransient = { scope.launch { pause() } }
            f.onLoss = { scope.launch { stop() } }
            f.onGain = { if (isPausedRequested) scope.launch { resume() } }
        }
    }

    override suspend fun play(itemId: String, text: String) {
        val clean = text.trim()
        val gen = machine.newPlay(itemId)
        val key = keyProvider().trim()
        if (clean.isBlank()) {
            machine.idle()
            return
        }
        if (key.isBlank()) {
            machine.failed("no_api_key")
            return
        }
        focus?.requestFocus()
        isPausedRequested = false
        playReqJob?.cancel()
        playReqJob = scope.launch {
            val chunks = splitIntoChunks(clean)
            var ok = true
            try {
                for (chunk in chunks) {
                    if (!stillCurrent(gen)) break
                    awaitResumeIfPaused(gen)
                    if (!stillCurrent(gen)) break
                    if (!playChunk(chunk, key, safeModel(modelProvider().trim()), gen)) {
                        // A chunk failed; playChunk already recorded a Failed state. Do NOT idle on
                        // completion so the failure stays visible (no silent revert to Idle).
                        ok = false
                        break
                    }
                }
                if (ok && machine.isCurrent(gen)) machine.idle()
            } catch (e: CancellationException) {
                // stop() cancelled us; state was already resolved by stop().
            } catch (e: Exception) {
                if (machine.isCurrent(gen)) machine.failed("playback_error")
            }
        }
    }

    override suspend fun pause() {
        val p = player
        if (p != null && p.isPlaying) {
            p.pause()
        }
        isPausedRequested = true
        machine.paused(positionMs = p?.currentPosition ?: 0L)
    }

    override suspend fun resume() {
        if (!isPausedRequested) return
        isPausedRequested = false
        val p = player
        if (p != null && !p.isPlaying) {
            p.start()
            machine.playing(positionMs = p.currentPosition, durationMs = p.duration)
        } else {
            machine.playing()
        }
    }

    override suspend fun stop() {
        playReqJob?.cancel()
        playReqJob = null
        activeCall?.cancel()
        activeCall = null
        releasePlayer()
        focus?.abandonFocus()
        isPausedRequested = false
        // Bump the generation and go Idle so any in-flight player/network callback goes stale.
        machine.cancel()
    }

    override suspend fun release() {
        stop()
    }

    private fun safeModel(model: String): String =
        if (VOICES.any { it.model == model }) model else DEFAULT_MODEL

    private suspend fun stillCurrent(gen: Long): Boolean =
        machine.isCurrent(gen) && currentCoroutineContext().isActive

    /** Block while paused so a new chunk does not start until the user resumes. */
    private suspend fun awaitResumeIfPaused(gen: Long) {
        while (isPausedRequested && machine.isCurrent(gen) && currentCoroutineContext().isActive) {
            delay(50)
        }
    }

    private fun splitIntoChunks(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.length > MAX_CHUNK_CHARS) {
            var cut = remaining.lastIndexOf('.', MAX_CHUNK_CHARS)
            if (cut < MAX_CHUNK_CHARS / 2) cut = remaining.lastIndexOf(' ', MAX_CHUNK_CHARS)
            if (cut <= 0) cut = MAX_CHUNK_CHARS
            chunks.add(remaining.substring(0, cut + 1).trim())
            remaining = remaining.substring(cut + 1).trimStart()
        }
        if (remaining.isNotBlank()) chunks.add(remaining)
        return chunks
    }

    /**
     * Request one chunk of speech and play it as the audio arrives. The HTTP body is streamed
     * (the server sends frames progressively) into a [BlockingByteStream], which the
     * [StreamingPlayer] pulls through its streamed data source. Only a small amount of audio is
     * buffered, so the first words can be heard before the whole chunk has been downloaded.
     */
    /** Returns true when the chunk finished/none played, false when it failed. */
    private suspend fun playChunk(text: String, apiKey: String, model: String, gen: Long): Boolean =
        coroutineScope {
            val payload = gson.toJson(SpeakRequest(text))
            val request = Request.Builder()
                .url("$ENDPOINT?model=$model")
                .addHeader("Authorization", "Token $apiKey")
                .addHeader("Accept", "audio/mpeg")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(request)
            activeCall = call
            val response = try {
                withContext(Dispatchers.IO) { call.execute() }
            } catch (e: IOException) {
                activeCall = null
                if (machine.isCurrent(gen)) machine.failed("network_error")
                return@coroutineScope false
            }
            activeCall = null

            try {
                if (!response.isSuccessful) {
                    if (machine.isCurrent(gen)) machine.failed("http_${response.code}")
                    return@coroutineScope false
                }
                val input = response.body?.byteStream() ?: run {
                    if (machine.isCurrent(gen)) machine.failed("empty_response")
                    return@coroutineScope false
                }
                val stream = BlockingByteStream()

                // Writer: copy the network body into the blocking stream. Runs concurrently with
                // playback so the player is fed while it's already speaking.
                val writer = launch(Dispatchers.IO) {
                    try {
                        val buf = ByteArray(16 * 1024)
                        while (isActive) {
                            val n = input.read(buf)
                            if (n == -1) break
                            stream.write(buf, 0, n)
                        }
                    } catch (e: Exception) {
                        // Body closed by stop(); ignore.
                    } finally {
                        stream.finish()
                    }
                }

                val played = withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        val p = playerFactory()
                        player = p
                        cont.invokeOnCancellation {
                            // Stop the player AND tear down the network body so the writer's
                            // blocking read() is interrupted promptly instead of hanging until the
                            // whole chunk has been downloaded.
                            p.release()
                            stream.finish()
                            runCatching { response.body?.close() }
                        }
                        p.onPrepared = {
                            p.start()
                            if (machine.isCurrent(gen)) machine.playing(p.currentPosition, p.duration)
                        }
                        p.onCompletion = {
                            if (p == player) player = null
                            p.release()
                            stream.finish()
                            if (cont.isActive) cont.resume(true)
                        }
                        p.onError = { code ->
                            if (p == player) player = null
                            p.release()
                            stream.finish()
                            if (machine.isCurrent(gen)) machine.failed(code)
                            if (cont.isActive) cont.resume(false)
                        }
                        if (p.setDataSource(stream) != null) {
                            if (p == player) player = null
                            p.release()
                            stream.finish()
                            if (machine.isCurrent(gen)) machine.failed("setup_failed")
                            if (cont.isActive) cont.resume(false)
                        } else {
                            p.prepare()
                        }
                    }
                }
                writer.cancel()
                return@coroutineScope played
            } finally {
                runCatching { response.body?.close() }
            }
        }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.release() }
        }
        player = null
    }

    /** A blocking byte stream for the play-and-write pipeline. Bounded so fast networks don't
     *  buffer the whole chunk; the writer waits when playback is behind. */
    private class BlockingByteStream(
        private val maxBuffered: Long = 4L * 1024 * 1024
    ) : InputStream() {
        private val lock = Object()
        private val chunks = ArrayDeque<ByteArray>()
        private var total = 0L
        private var closed = false

        fun write(bytes: ByteArray, off: Int, len: Int) {
            val chunk = bytes.copyOfRange(off, off + len)
            synchronized(lock) {
                while (total >= maxBuffered && !closed) lock.wait()
                if (closed) return
                chunks.addLast(chunk)
                total += chunk.size
                lock.notifyAll()
            }
        }

        fun finish() {
            synchronized(lock) {
                closed = true
                lock.notifyAll()
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
            while (chunks.isEmpty() && !closed) lock.wait()
            if (chunks.isEmpty()) return -1
            val head = chunks.first()
            val n = minOf(len, head.size)
            System.arraycopy(head, 0, b, off, n)
            if (n == head.size) chunks.removeFirst()
            else chunks[0] = head.copyOfRange(n, head.size)
            total -= n
            lock.notifyAll()
            n
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun close() = finish()
    }
}
