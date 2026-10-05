package com.noteflowai.app.data.tts

/**
 * The current playback state of a [TtsManager], as prescribed by the Agentic Guide's Phase 2
 * ("TTS Playback State Machine"). Replaces the old single boolean with the true state of the
 * playing item so the UI and the player can never silently disagree.
 *
 * Only [PlaybackStateMachine] writes these values; managers and view models read them.
 */
sealed interface PlaybackState {
    /** Nothing is queued or playing. */
    data object Idle : PlaybackState

    /** A [play] request has been accepted but audio has not started yet. */
    data class Preparing(val itemId: String) : PlaybackState

    /** Audio is currently audible. */
    data class Playing(
        val itemId: String,
        val positionMs: Long,
        val durationMs: Long?
    ) : PlaybackState

    /** Playback is held; it can be resumed for the same [itemId]. */
    data class Paused(
        val itemId: String,
        val positionMs: Long
    ) : PlaybackState

    /** Playback failed terminally. [code] is a short, UI-showable failure code. */
    data class Failed(
        val itemId: String,
        val code: String
    ) : PlaybackState
}
