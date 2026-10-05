package com.noteflowai.app.data.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure Kotlin holder of [PlaybackState]: the single writer of the state stream and the
 * mechanism for preventing stale callbacks.
 *
 * The core problem Phase 2 addresses is that a background player/engine can keep firing
 * `onDone` / `onError` / `onCompletion` callbacks after a [stop] or a second [play] has already
 * moved playback on. Those callbacks carry no identity, so a naive implementation lets them
 * clobber the real state (the "silent stop" / "stale callback corruption" failures).
 *
 * We solve that with a monotonically increasing **generation** token: every [play] bumps it, the
 * playback coroutine captures the generation it belongs to, and every late callback is checked
 * against [isCurrent] before it is allowed to mutate state. A callback from an older generation
 * is dropped.
 *
 * This class has no Android dependencies and is exercised directly by unit tests.
 */
class PlaybackStateMachine {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Current generation; bumped once per [newPlay]. */
    @Volatile
    private var generation = 0L

    /** Read the generation that the current playback belongs to, to stamp outgoing work. */
    fun generation(): Long = generation

    /** True if [candidate] is the generation of the current play request (stale-callback guard). */
    fun isCurrent(candidate: Long): Boolean = candidate == generation

    /**
     * Start a new play request for [itemId]. Bumps the generation (invalidating every callback
     * still in flight from the previous request) and moves to [PlaybackState.Preparing].
     */
    fun newPlay(itemId: String): Long {
        generation++
        _state.value = PlaybackState.Preparing(itemId)
        return generation
    }

    /** Playback for the current request has begun. */
    fun playing(positionMs: Long = 0L, durationMs: Long? = null) {
        _state.value = PlaybackState.Playing(
            itemId = itemIdOf(_state.value),
            positionMs = positionMs,
            durationMs = durationMs
        )
    }

    /**
     * Invalidate every in-flight callback and return to [PlaybackState.Idle]. Used by a backend's
     * `stop()`: bumping the generation makes any onDone/onCompletion/onError still queued from the
     * current playback appear stale, so they cannot resurrect a Playing state after a stop.
     */
    fun cancel() {
        generation++
        _state.value = PlaybackState.Idle
    }

    /** Playback for the current request is held. */
    fun paused(positionMs: Long = 0L) {
        _state.value = PlaybackState.Paused(
            itemId = itemIdOf(_state.value),
            positionMs = positionMs
        )
    }

    /** The current request failed terminally with [code]. */
    fun failed(code: String) {
        _state.value = PlaybackState.Failed(itemId = itemIdOf(_state.value), code = code)
    }

    /** Nothing is playing anymore. */
    fun idle() {
        _state.value = PlaybackState.Idle
    }

    private fun itemIdOf(state: PlaybackState): String = when (state) {
        is PlaybackState.Playing -> state.itemId
        is PlaybackState.Paused -> state.itemId
        is PlaybackState.Preparing -> state.itemId
        is PlaybackState.Failed -> state.itemId
        // No item in flight (e.g. paused() called from Idle) — keep a stable placeholder so the
        // state type still carries an itemId. Callers only reach these transitions after
        // newPlay(), so this is a defensive default.
        PlaybackState.Idle -> "unknown"
    }
}
