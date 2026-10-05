package com.noteflowai.app.data.tts

import kotlinx.coroutines.flow.StateFlow

/**
 * Single interface for read-aloud, prescribed verbatim by the Agentic Guide's Phase 2.
 *
 * One [TtsManager] represents one selectable speech backend (on-device Android TextToSpeech or
 * Deepgram Aura streaming). [play] is suspending so a caller can await completion if it wants,
 * but every method is safe to call from any thread; implementations coordinate their own scope.
 *
 * Contract notes:
 * - [state] is the single source of truth for what is happening. Consumers derive UI (e.g.
 *   "speaking" == `state is Playing`) from it rather than tracking a separate boolean.
 * - Backends that own an [android.media.AudioManager] focus request (Deepgram) must abandon focus
 *   in [stop] and [release]. The stock Android engine manages its own focus, so
 *   [AndroidTtsManager] does not claim any.
 * - After [release] the manager is unusable; callers must discard it and obtain a fresh instance.
 */
interface TtsManager {
    val state: StateFlow<PlaybackState>

    /** Start speaking [text], or replace any in-flight speech with it. */
    suspend fun play(itemId: String, text: String)

    /** Hold playback of the current item. Safe no-op if nothing is playing. */
    suspend fun pause()

    /** Continue a paused item from where it stopped. */
    suspend fun resume()

    /** Fully stop playback and release transient audio resources. State returns to Idle. */
    suspend fun stop()

    /** Stop and permanently release the manager and all its resources. */
    suspend fun release()
}
