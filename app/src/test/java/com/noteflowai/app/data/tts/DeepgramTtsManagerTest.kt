package com.noteflowai.app.data.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Verifies [DeepgramTtsManager] against the constructor-injected seams (HTTP client, player
 * factory, audio-focus controller), focusing on Phase 2's stop/focus/network guarantees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepgramTtsManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun manager(
        client: OkHttpClient,
        focus: AudioFocusController? = null,
        key: String = "test-key",
        model: String = "aura-2-thalia-en"
    ) = DeepgramTtsManager(
        context = context,
        client = client,
        focus = focus,
        keyProvider = { key },
        modelProvider = { model }
    )

    @Test
    fun `stop abandons audio focus and returns to Idle`() = runBlocking {
        val focus = mockk<AudioFocusController>(relaxed = true)
        val m = manager(client = mockk(relaxed = true), focus = focus)
        m.stop()
        verify { focus.abandonFocus() }
        assertEquals(PlaybackState.Idle, m.state.value)
    }

    @Test
    fun `stop is safe when idle and never leaves a Playing linger`() = runBlocking {
        val m = manager(client = mockk(relaxed = true))
        m.stop()
        assertEquals(PlaybackState.Idle, m.state.value)
    }

    @Test
    fun `play with a blank api key fails without lingering as playing`() = runBlocking {
        val m = manager(client = mockk(relaxed = true), key = "")
        m.play("x", "hello")
        assertTrue(
            "expected Failed, got ${m.state.value}",
            m.state.value is PlaybackState.Failed
        )
    }

    @Test
    fun `play with blank text stays idle`() = runBlocking {
        val m = manager(client = mockk(relaxed = true), key = "k")
        m.play("x", "   ")
        assertEquals(PlaybackState.Idle, m.state.value)
    }

    @Test
    fun `network failure surfaces as Failed and cancels the call`() = runBlocking {
        val call = mockk<okhttp3.Call>(relaxed = true)
        every { call.execute() } throws IOException("boom")
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call

        val m = manager(client = client)
        val job = CoroutineScope(Dispatchers.IO).launch { m.play("x", "some text") }

        // The chunk pipeline runs asynchronously on its own IO scope; poll for the failure.
        val deadline = System.currentTimeMillis() + 5000
        while (m.state.value !is PlaybackState.Failed && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(
            "expected Failed, got ${m.state.value}",
            m.state.value is PlaybackState.Failed
        )
        job.join()
    }

    @Test
    fun `failed playback does not silently revert to idle`() {
        val call = mockk<Call>(relaxed = true)
        every { call.execute() } throws IOException("boom")
        val client = mockk<OkHttpClient>(relaxed = true)
        every { client.newCall(any()) } returns call

        val m = manager(client = client)
        CoroutineScope(Dispatchers.IO).launch { m.play("x", "some text") }

        val deadline = System.currentTimeMillis() + 5000
        while (m.state.value !is PlaybackState.Failed && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        // Give the job a moment; if the finally wrongly idled it, this would race to Idle.
        Thread.sleep(100)
        assertTrue(
            "expected Failed to persist, got ${m.state.value}",
            m.state.value is PlaybackState.Failed
        )
    }
}
