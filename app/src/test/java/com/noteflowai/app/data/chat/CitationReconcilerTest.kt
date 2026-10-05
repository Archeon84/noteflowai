package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.search.RetrievalResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CitationReconcilerTest {

    private val segmentRepo = mockk<SourceSegmentRepository>(relaxed = true)

    private fun citation(id: String, chunkId: String, sourceId: String, quoteStart: Int? = null, quoteEnd: Int? = null) =
        Citation(id = id, sourceType = SourceType.NOTE, sourceId = sourceId, chunkId = chunkId, quoteStart = quoteStart, quoteEnd = quoteEnd)

    private fun retrieval(chunkId: String, sourceId: String, text: String, score: Float = 0.9f) =
        RetrievalResult(sourceSegmentId = chunkId, sourceId = sourceId, text = text, score = score, rank = 0)

    private val response = GroundedChatResponse("answer", citations = listOf(
        citation("cite-1", "seg-1", "note.md", 0, 7),
        citation("cite-2", "seg-missing", "note.md"),
        citation("cite-3", "seg-wrong-source", "note.md"),  // citation.sourceId = note.md
        citation("cite-4", "note_note.md_block2_abc", "note.md"),
        citation("cite-5", "seg-1", "note.md", 900, 5) // inverted advisory range
    ))

    @Test
    fun `authoritative when chunk retrieved, source matches, and segment row exists`() = runTest {
        coEvery { segmentRepo.getById("seg-1") } returns SourceSegment(id = "seg-1", sourceId = "note.md", text = "abcdefg", normalizedText = "abcdefg", sourceType = SourceType.NOTE)
        val rec = CitationReconciler(segmentRepo).reconcile(response, listOf(retrieval("seg-1", "note.md", "abcdefg")))
            .first { it.citation.id == "cite-1" }
        assertTrue(rec.authoritative)
        assertEquals("seg-1", rec.resolved?.sourceSegmentId)
        assertNotNull(rec.segment)
        assertEquals(0, rec.computedQuoteRange?.start)
        assertEquals(7, rec.computedQuoteRange?.end)
        assertNull(rec.conflictNote)
    }

    @Test
    fun `chunk missing from retrieval is not authoritative`() = runTest {
        val rec = CitationReconciler(segmentRepo).reconcile(response, listOf(retrieval("seg-1", "note.md", "abcdefg")))
            .first { it.citation.id == "cite-2" }
        assertFalse(rec.authoritative)
        assertNull(rec.resolved)
        assertNull(rec.segment)
        assertNull(rec.computedQuoteRange)
    }

    @Test
    fun `source id mismatch is not authoritative`() = runTest {
        coEvery { segmentRepo.getById("seg-wrong-source") } returns SourceSegment(id = "seg-wrong-source", sourceId = "other.md", text = "abcdefg", normalizedText = "abcdefg", sourceType = SourceType.NOTE)
        val rec = CitationReconciler(segmentRepo).reconcile(response, listOf(retrieval("seg-wrong-source", "other.md", "abcdefg")))
            .first { it.citation.id == "cite-3" }
        assertFalse(rec.authoritative)
    }

    @Test
    fun `note block id without segment row stays authoritative via block mapping`() = runTest {
        coEvery { segmentRepo.getById("note_note.md_block2_abc") } returns null
        val rec = CitationReconciler(segmentRepo).reconcile(response, listOf(retrieval("note_note.md_block2_abc", "note.md", "abcdefg")))
            .first { it.citation.id == "cite-4" }
        assertTrue(rec.authoritative)
        assertNull(rec.segment)
    }

    @Test
    fun `inverted advisory offsets produce null quote range`() = runTest {
        coEvery { segmentRepo.getById("seg-1") } returns SourceSegment(id = "seg-1", sourceId = "note.md", text = "abcdefg", normalizedText = "abcdefg", sourceType = SourceType.NOTE)
        val rec = CitationReconciler(segmentRepo).reconcile(response, listOf(retrieval("seg-1", "note.md", "abcdefg")))
            .first { it.citation.id == "cite-5" }
        assertNull(rec.computedQuoteRange)
    }
}