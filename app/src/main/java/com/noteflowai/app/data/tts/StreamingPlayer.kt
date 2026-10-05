package com.noteflowai.app.data.tts

import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Looper
import java.io.InputStream

/**
 * Seam between [DeepgramTtsManager] and the Android [MediaPlayer]. Deepgram streams synthesized
 * audio over HTTP, so the player pulls frames from an [InputStream] as they arrive instead of
 * playing a fully-downloaded file. Wrapping the player behind this interface lets unit tests
 * drive stop/pause/error/focus scenarios without a real MediaPlayer.
 */
interface StreamingPlayer {

    /** Hand this player a source to pull frames from (called once, before [prepare]). */
    fun setDataSource(stream: InputStream): ErrorCode?

    /** Populate audio frames without starting playback. */
    fun prepare()

    /** Begin (or resume) audible playback. */
    fun start()

    /** Hold playback at the current position. */
    fun pause()

    /** Stop, reset, and release all native resources. Safe to call more than once. */
    fun release()

    /** Elapsed position in ms; 0 when unknown (e.g. for live streams). */
    val currentPosition: Long

    /** Duration in ms, or null when unknown (live/streamed sources). */
    val duration: Long?

    /** Fired once audio is ready to start. */
    var onPrepared: (() -> Unit)?

    /** Fired when playback reached the end of the source. */
    var onCompletion: (() -> Unit)?

    /** Fired on a playback error; [code] is a short label such as "error_1_100". */
    var onError: ((code: String) -> Unit)?

    val isPlaying: Boolean
}

/**
 * Default [StreamingPlayer] backed by [MediaPlayer] + [MediaDataSource], mirroring the streaming
 * approach Deepgram previously used directly (main-thread preparation, unknown size -> streamed
 * non-seekable playback).
 */
class MediaPlayerStreamingPlayer : StreamingPlayer {

    private var player: MediaPlayer? = null

    override var onPrepared: (() -> Unit)? = null
    override var onCompletion: (() -> Unit)? = null
    override var onError: ((code: String) -> Unit)? = null

    override val currentPosition: Long
        get() = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)

    override val duration: Long?
        get() = runCatching { player?.duration?.takeIf { it > 0 }?.toLong() }.getOrDefault(null)

    override val isPlaying: Boolean
        get() = runCatching { player?.isPlaying ?: false }.getOrDefault(false)

    override fun setDataSource(stream: InputStream): ErrorCode? {
        val prepared = MediaPlayer()
        try {
            // Unknown size -> MediaPlayer treats it as a live, non-seekable stream and pulls
            // frames sequentially as they arrive (our BlockingByteStream feeds it).
            prepared.setDataSource(StreamMediaDataSource(stream))
            prepared.setOnPreparedListener { onPrepared?.invoke() }
            prepared.setOnCompletionListener { onCompletion?.invoke() }
            prepared.setOnErrorListener { _, what, extra ->
                onError?.invoke("error_${what}_$extra")
                true
            }
            player = prepared
            return null
        } catch (e: Exception) {
            runCatching { prepared.release() }
            return ErrorCode.SETUP_FAILED
        }
    }

    override fun prepare() {
        player?.prepareAsync()
    }

    override fun start() {
        player?.start()
    }

    override fun pause() {
        player?.pause()
    }

    override fun release() {
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        player = null
    }

    /** Bridges a stream into MediaPlayer's data-source API. */
    private class StreamMediaDataSource(private val stream: InputStream) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offsetInBuffer: Int, size: Int): Int =
            stream.read(buffer, offsetInBuffer, size)

        override fun getSize(): Long = -1L // unknown duration -> stream, not seek

        override fun close() = stream.close()
    }

}

/** Reason a [StreamingPlayer.setDataSource] call failed, if any. */
enum class ErrorCode { SETUP_FAILED }

/**
 * Creates [MediaPlayerStreamingPlayer] on the main thread. MediaPlayer must be created on a Looper
 * thread; this helper lets [DeepgramTtsManager]'s default player factory stay simple and correct
 * while tests inject their own.
 */
fun newMainThreadStreamingPlayer(): StreamingPlayer {
    requireNotNull(Looper.myLooper()) { "StreamingPlayer must be created on a Looper thread" }
    return MediaPlayerStreamingPlayer()
}
