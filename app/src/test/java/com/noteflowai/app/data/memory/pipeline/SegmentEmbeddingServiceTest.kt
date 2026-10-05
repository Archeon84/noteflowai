package com.noteflowai.app.data.memory.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.ConnectivityChecker
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.search.OnDeviceEmbedder
import com.noteflowai.app.data.search.RemoteEmbeddingClient
import com.noteflowai.app.data.settings.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for SegmentEmbeddingService (§7 EMBEDDING stage).
 *
 * Covers the offline-first path (on-device embedder ready), the remote fallback
 * path, and the no-backend case where embedding is unavailable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SegmentEmbeddingServiceTest {

    private lateinit var context: Context
    private lateinit var sourceSegmentRepository: SourceSegmentRepository
    private lateinit var onDeviceEmbedder: OnDeviceEmbedder
    private lateinit var remoteEmbeddingClient: RemoteEmbeddingClient
    private lateinit var settingsManager: SettingsManager
    private lateinit var connectivityChecker: ConnectivityChecker
    private lateinit var service: SegmentEmbeddingService

    private val sourceId = "src_1"

    private fun segment(id: String = "seg_1", text: String = "the quick brown fox") = SourceSegment(
        id = id,
        sourceId = sourceId,
        sourceType = SourceType.NOTE,
        text = text,
        normalizedText = text.lowercase()
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sourceSegmentRepository = mockk()
        onDeviceEmbedder = mockk()
        remoteEmbeddingClient = mockk()
        settingsManager = mockk()
        connectivityChecker = mockk()
        every { connectivityChecker.hasNetwork() } returns true
        service = SegmentEmbeddingService(
            context,
            sourceSegmentRepository,
            onDeviceEmbedder,
            remoteEmbeddingClient,
            settingsManager,
            connectivityChecker
        )
    }

    private fun stubRemoteSettings(baseUrl: String = "http://localhost:11434/") {
        every { settingsManager.aiBaseUrl } returns flowOf(baseUrl)
        every { settingsManager.aiApiKey } returns flowOf("key")
        every { settingsManager.embeddingModel } returns flowOf("")
        every { settingsManager.aiProvider } returns flowOf("OpenAI")
        every { settingsManager.isLocalOnlyMode } returns flowOf(false)
    }

    @Test
    fun `uses the on-device embedder when ready and never calls the remote client`() = runTest {
        every { onDeviceEmbedder.isReady() } returns true
        coEvery { onDeviceEmbedder.embed(any()) } returns FloatArray(384)
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())

        val ok = service.embedSource(sourceId)

        assertTrue(ok)
        assertEquals(1, service.size())
        coVerify(exactly = 1) { onDeviceEmbedder.embed(any()) }
        coVerify(exactly = 0) { remoteEmbeddingClient.embed(any<String>(), any(), any(), any(), any()) }
    }

    @Test
    fun `initializes the on-device embedder when not yet ready`() = runTest {
        every { onDeviceEmbedder.isReady() } returns false
        coEvery { onDeviceEmbedder.initialize(any()) } returns true
        coEvery { onDeviceEmbedder.embed(any()) } returns FloatArray(384)
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())

        val ok = service.embedSource(sourceId)

        assertTrue(ok)
        coVerify(exactly = 1) { onDeviceEmbedder.initialize(any()) }
        coVerify(exactly = 1) { onDeviceEmbedder.embed(any()) }
    }

    @Test
    fun `falls back to the remote provider when no on-device model is available`() = runTest {
        every { onDeviceEmbedder.isReady() } returns false
        coEvery { onDeviceEmbedder.initialize(any()) } returns false
        stubRemoteSettings()
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        // Batched remote call: one request for all segment texts.
        coEvery {
            remoteEmbeddingClient.embed(any<List<String>>(), any(), any(), any(), any())
        } returns RemoteEmbeddingClient.EmbeddingResult(listOf(FloatArray(8)), 8, "test")

        val ok = service.embedSource(sourceId)

        assertTrue(ok)
        coVerify(exactly = 1) { remoteEmbeddingClient.embed(any<List<String>>(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips remote embedding while offline instead of paying timeouts`() = runTest {
        every { connectivityChecker.hasNetwork() } returns false
        every { onDeviceEmbedder.isReady() } returns false
        coEvery { onDeviceEmbedder.initialize(any()) } returns false
        stubRemoteSettings()
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())

        val ok = service.embedSource(sourceId)

        assertFalse(ok)
        coVerify(exactly = 0) { remoteEmbeddingClient.embed(any<String>(), any(), any(), any(), any()) }
    }

    @Test
    fun `returns false when no embedding backend is configured`() = runTest {
        every { onDeviceEmbedder.isReady() } returns false
        coEvery { onDeviceEmbedder.initialize(any()) } returns false
        stubRemoteSettings(baseUrl = "   ")
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())

        val ok = service.embedSource(sourceId)

        assertFalse(ok)
        coVerify(exactly = 0) { remoteEmbeddingClient.embed(any<String>(), any(), any(), any(), any()) }
    }

    @Test
    fun `returns true when the source has no segments`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns emptyList()

        val ok = service.embedSource(sourceId)

        assertTrue(ok)
        assertEquals(0, service.size())
    }

    @Test
    fun `swallows repository errors and returns false`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } throws RuntimeException("db locked")

        val ok = service.embedSource(sourceId)

        assertFalse(ok)
    }

    @Test
    fun `local-only mode skips remote embedding for non-loopback endpoints`() = runTest {
        every { onDeviceEmbedder.isReady() } returns false
        coEvery { onDeviceEmbedder.initialize(any()) } returns false
        stubRemoteSettings(baseUrl = "https://api.openai.com/")
        every { settingsManager.isLocalOnlyMode } returns flowOf(true)
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())

        val ok = service.embedSource(sourceId)

        assertFalse(ok)
        coVerify(exactly = 0) { remoteEmbeddingClient.embed(any<List<String>>(), any(), any(), any(), any()) }
    }
}
