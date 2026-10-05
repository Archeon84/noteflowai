package com.noteflowai.app.data.memory.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
 * Unit tests for SegmentIndexService (§7 INDEXING stage).
 *
 * Uses Robolectric so the BM25 index can persist to a real filesDir. The
 * SourceSegmentRepository is mocked; indexing/search operate on in-memory data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SegmentIndexServiceTest {

    private lateinit var context: Context
    private lateinit var sourceSegmentRepository: SourceSegmentRepository
    private lateinit var service: SegmentIndexService

    private val sourceId = "src_1"

    private fun segment(id: String, text: String) = SourceSegment(
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
        service = SegmentIndexService(context, sourceSegmentRepository)
    }

    @Test
    fun `indexes segments and returns them from search`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(
            segment("seg_1", "the quick brown fox jumps over the lazy dog"),
            segment("seg_2", "postgres migration strategy for the team")
        )

        val ok = service.indexSource(sourceId)

        assertTrue(ok)
        assertEquals(2, service.size())

        val hits = service.search("postgres migration")
        assertEquals(1, hits.size)
        assertEquals("seg_2", hits[0].segmentId)
        assertEquals(sourceId, hits[0].sourceId)
        assertTrue(hits[0].score > 0f)
        assertTrue(hits[0].excerpt.contains("postgres"))
    }

    @Test
    fun `search is case-insensitive and stop-word tolerant`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(
            segment("seg_1", "Decision: adopt Kotlin for backend services")
        )

        service.indexSource(sourceId)

        val hits = service.search("KOTLIN backend")
        assertEquals(1, hits.size)
        assertEquals("seg_1", hits[0].segmentId)
    }

    @Test
    fun `unmatched query returns no hits`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(
            segment("seg_1", "the quick brown fox")
        )
        service.indexSource(sourceId)

        val hits = service.search("quantum computing")

        assertTrue(hits.isEmpty())
    }

    @Test
    fun `empty source indexes nothing and is not an error`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns emptyList()

        val ok = service.indexSource(sourceId)

        assertTrue(ok)
        assertEquals(0, service.size())
    }

    @Test
    fun `remove source drops its entries`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(
            segment("seg_1", "the quick brown fox")
        )
        service.indexSource(sourceId)
        assertEquals(1, service.size())

        service.removeSource(sourceId)

        assertEquals(0, service.size())
        assertTrue(service.search("fox").isEmpty())
    }

    @Test
    fun `blank query returns no hits`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(
            segment("seg_1", "the quick brown fox")
        )
        service.indexSource(sourceId)

        assertTrue(service.search("  ").isEmpty())
        assertFalse(service.search("fox").isEmpty())
    }

    @Test
    fun `reindexing a source replaces prior entries`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(
            segment("seg_1", "first version of the note content")
        )
        service.indexSource(sourceId)
        assertEquals(1, service.size())

        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(
            segment("seg_1", "updated note content after edits"),
            segment("seg_2", "a brand new paragraph added later")
        )
        service.indexSource(sourceId)

        assertEquals(2, service.size())
        coVerify(exactly = 2) { sourceSegmentRepository.getBySourceId(sourceId) }
    }

    @Test
    fun `removing a source refreshes cached stats so surviving docs still match`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId("src_a") } returns listOf(
            segmentOf("seg_a1", "src_a", "alpha migration plan")
        )
        coEvery { sourceSegmentRepository.getBySourceId("src_b") } returns listOf(
            segmentOf("seg_b1", "src_b", "beta migration plan")
        )
        service.indexSource("src_a")
        service.indexSource("src_b")
        assertEquals(2, service.size())

        service.removeSource("src_a")

        // The surviving source still matches the shared term after the corpus
        // statistics refresh on removal.
        assertEquals(1, service.size())
        val hits = service.search("migration")
        assertEquals(1, hits.size)
        assertEquals("seg_b1", hits[0].segmentId)
    }

    private fun segmentOf(id: String, srcId: String, text: String) = SourceSegment(
        id = id,
        sourceId = srcId,
        sourceType = SourceType.NOTE,
        text = text,
        normalizedText = text.lowercase()
    )
}
