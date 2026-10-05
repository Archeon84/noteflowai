package com.noteflowai.app.data.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [AndroidTtsManager] against the shared [TtsManager] contract. The real engine-init and
 * utterance listener transitions are covered by [PlaybackStateMachineTest]; here we assert the
 * public contract that does not depend on the on-device engine actually speaking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTtsManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `new manager starts idle`() {
        val m = AndroidTtsManager(context)
        assertEquals(PlaybackState.Idle, m.state.value)
    }

    @Test
    fun `play moves to preparing while engine init is settling`() = runBlocking {
        val m = AndroidTtsManager(context)
        m.play("item-a", "hello world")
        // Whether or not the engine finished initializing, playback is at most Preparing
        // (queued / about to speak); it must never be a lingering Playing before audio starts.
        assertTrue(m.state.value is PlaybackState.Preparing)
    }

    @Test
    fun `stop returns to idle and is safe when nothing is playing`() = runBlocking {
        val m = AndroidTtsManager(context)
        m.stop()
        assertEquals(PlaybackState.Idle, m.state.value)
        m.stop()
        assertEquals(PlaybackState.Idle, m.state.value)
    }

    @Test
    fun `release is safe and returns to idle`() = runBlocking {
        val m = AndroidTtsManager(context)
        m.release()
        assertEquals(PlaybackState.Idle, m.state.value)
        m.release()
        assertEquals(PlaybackState.Idle, m.state.value)
    }
}
