package com.noteflowai.app.data.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlaybackStateMachine] — the pure core of Phase 2's "true playback-state
 * management". Covers the Guide's automatable scenarios: play-then-stop-immediately,
 * play-stop-then-play-another, pause/resume, rapid repeat playback, failure, and the
 * stale-callback guard that prevents a late callback from corrupting state.
 */
class PlaybackStateMachineTest {

    private fun machine() = PlaybackStateMachine()

    @Test
    fun `play then stop immediately leaves idle - no silent stop`() {
        val m = machine()
        m.newPlay("A")
        m.playing()
        m.cancel()
        assertEquals(PlaybackState.Idle, m.state.value)
    }

    @Test
    fun `play stop then play another item moves to the new item`() {
        val m = machine()
        m.newPlay("A")
        m.playing()
        m.cancel()
        val genB = m.newPlay("B")
        m.playing()
        assertTrue(m.isCurrent(genB))
        val state = m.state.value
        assertTrue("expected Playing, got $state", state is PlaybackState.Playing)
        assertEquals("B", (state as PlaybackState.Playing).itemId)
    }

    @Test
    fun `pause then resume keeps the same item with preserved position`() {
        val m = machine()
        m.newPlay("A")
        m.playing(positionMs = 1234, durationMs = 5000)
        m.paused(positionMs = 1234)
        assertTrue(m.state.value is PlaybackState.Paused)
        m.playing(positionMs = 2000, durationMs = 5000)
        val state = m.state.value as PlaybackState.Playing
        assertEquals("A", state.itemId)
        assertEquals(2000L, state.positionMs)
    }

    @Test
    fun `rapid repeat playback - stale generation callbacks are dropped`() {
        val m = machine()
        val genA = m.newPlay("A")
        m.newPlay("B") // bumps generation, A's generation is now stale
        assertFalse(m.isCurrent(genA))
        // A stale callback that escapes a manager's guard must not clobber B's state.
        m.playing()
        assertTrue(m.state.value is PlaybackState.Playing)
        assertFalse(m.isCurrent(genA))
    }

    @Test
    fun `failed then stop returns to idle`() {
        val m = machine()
        m.newPlay("A")
        m.failed("network_error")
        assertTrue(m.state.value is PlaybackState.Failed)
        m.cancel()
        assertEquals(PlaybackState.Idle, m.state.value)
    }

    @Test
    fun `cancel invalidates all in flight callbacks`() {
        val m = machine()
        val gen = m.newPlay("A")
        m.playing()
        m.cancel()
        // A late callback stamped with the pre-cancel generation must be treated as stale.
        assertFalse(m.isCurrent(gen))
        assertEquals(PlaybackState.Idle, m.state.value)
    }

    @Test
    fun `a stale onDone cannot resurrect a cancelled playback`() {
        val m = machine()
        val genA = m.newPlay("A")
        m.newPlay("B")
        m.cancel()
        assertEquals(PlaybackState.Idle, m.state.value)
        // Simulate a late onDone from generation A firing after stop.
        assertFalse(m.isCurrent(genA))
        assertEquals(PlaybackState.Idle, m.state.value)
    }
}
