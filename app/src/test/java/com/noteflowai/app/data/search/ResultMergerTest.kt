package com.noteflowai.app.data.search

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ResultMerger (personal memory layer, Phase 4).
 *
 * Uses MockK to mock SourceSegmentRepository. Robolectric stubs
 * android.util.Log so the neighbor-expansion error path runs on the host JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResultMergerTest {

    private lateinit var repo: SourceSegmentRepository
    private lateinit var merger: ResultMerger

    private fun segment(
        id: String,
        sourceId: String = "src_1",
        text: String = "",
        type: SourceType = SourceType.NOTE
    ) = SourceSegment(
        id = id,
        sourceId = sourceId,
        sourceType = type,
        text = text,
        normalizedText = text.lowercase()
    )

    private fun result(
        sourceSegmentId: String,
        sourceId: String = "src_1",
        text: String = "",
        score: Float = 1.0f,
        rank: Int = 0,
        metadata: Map<String, String>? = null
    ) = RetrievalResult(
        sourceSegmentId = sourceSegmentId,
        sourceId = sourceId,
        text = text,
        sourceType = SourceType.NOTE,
        score = score,
        rank = rank,
        metadata = metadata
    )

    @Before
    fun setUp() {
        repo = mockk()
        merger = ResultMerger(repo)
    }

    // ── Empty input ───────────────────────────────────────────────────

    @Test
    fun `empty results returns empty`() = runTest {
        val plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "q")
        val out = merger.expandAndRank(emptyList(), plan)
        assertTrue(out.isEmpty())
    }

    // ── Neighbor expansion ────────────────────────────────────────────

    @Test
    fun `neighbors are appended for segment results`() = runTest {
        coEvery {
            repo.getNeighbors("src_1", "seg_1")
        } returns listOf(
            segment("seg_1b", text = "neighbor text"),
            segment("seg_1c", text = "second neighbor")
        )

        val plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "q")
        val out = merger.expandAndRank(
            listOf(result("seg_1", text = "main")),
            plan
        )

        assertEquals(3, out.size)
        // Original first, then the two neighbors
        assertEquals("seg_1", out[0].sourceSegmentId)
        assertTrue(out.any { it.sourceSegmentId == "seg_1b" })
        assertTrue(out.any { it.sourceSegmentId == "seg_1c" })
        // Neighbors get the isNeighbor metadata marker
        val neighbor = out.first { it.sourceSegmentId == "seg_1b" }
        assertEquals("true", neighbor.metadata?.get("isNeighbor"))
        assertEquals("seg_1", neighbor.metadata?.get("parentSegmentId"))
    }

    @Test
    fun `note-level results skip neighbor expansion`() = runTest {
        val plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "q")
        val out = merger.expandAndRank(
            listOf(result("note_abc", text = "note hit")),
            plan
        )
        // getNeighbors must never be called for note_ results
        assertEquals(1, out.size)
        assertEquals("note_abc", out[0].sourceSegmentId)
    }

    @Test
    fun `neighbor expansion failure is swallowed`() = runTest {
        coEvery { repo.getNeighbors("src_1", "seg_1") } throws RuntimeException("db down")

        val plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "q")
        val out = merger.expandAndRank(listOf(result("seg_1")), plan)

        // Original result survives despite expansion error
        assertEquals(1, out.size)
        assertEquals("seg_1", out[0].sourceSegmentId)
    }

    // ── Reranking ─────────────────────────────────────────────────────

    @Test
    fun `confirmed decisions rank above detected`() = runTest {
        coEvery { repo.getNeighbors(any(), any()) } returns emptyList()

        val plan = QueryPlan(intent = QueryIntent.DECISION_LOOKUP, queryText = "decisions")
        val out = merger.expandAndRank(
            listOf(
                result("seg_detected", score = 1.0f, metadata = mapOf("status" to "DETECTED")),
                result("seg_confirmed", score = 1.0f, metadata = mapOf("status" to "CONFIRMED"))
            ),
            plan
        )

        assertEquals(2, out.size)
        // CONFIRMED gets 1.3x boost -> should sort first
        assertEquals("seg_confirmed", out[0].sourceSegmentId)
    }

    @Test
    fun `overdue commitments rank above confirmed`() = runTest {
        coEvery { repo.getNeighbors(any(), any()) } returns emptyList()

        val plan = QueryPlan(intent = QueryIntent.COMMITMENT_LOOKUP, queryText = "commitments")
        val out = merger.expandAndRank(
            listOf(
                result("seg_confirmed", score = 1.0f, metadata = mapOf("status" to "CONFIRMED", "isOverdue" to "false")),
                result("seg_overdue", score = 1.0f, metadata = mapOf("status" to "CONFIRMED", "isOverdue" to "true"))
            ),
            plan
        )

        // Overdue (1.4x) beats confirmed (1.2x)
        assertEquals("seg_overdue", out[0].sourceSegmentId)
    }

    @Test
    fun `change analysis sorts chronologically by decidedAt`() = runTest {
        coEvery { repo.getNeighbors(any(), any()) } returns emptyList()

        val plan = QueryPlan(intent = QueryIntent.CHANGE_ANALYSIS, queryText = "changes")
        val out = merger.expandAndRank(
            listOf(
                result("seg_later", score = 5.0f, metadata = mapOf("decidedAt" to "2000")),
                result("seg_earlier", score = 1.0f, metadata = mapOf("decidedAt" to "1000"))
            ),
            plan
        )

        assertEquals(2, out.size)
        // Chronological order regardless of score
        assertEquals("seg_earlier", out[0].sourceSegmentId)
        assertEquals("seg_later", out[1].sourceSegmentId)
    }

    // ── Deduplication and truncation ──────────────────────────────────

    @Test
    fun `duplicate segment ids are deduplicated`() = runTest {
        coEvery { repo.getNeighbors(any(), any()) } returns emptyList()

        val plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "q")
        val out = merger.expandAndRank(
            listOf(result("seg_1", score = 1.0f), result("seg_1", score = 0.9f)),
            plan
        )

        assertEquals(1, out.size)
    }

    @Test
    fun `results are truncated to final context limit`() = runTest {
        coEvery { repo.getNeighbors(any(), any()) } returns emptyList()

        val plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "q")
        val many = (0 until 12).map { result("seg_$it") }
        val out = merger.expandAndRank(many, plan)

        assertTrue(out.size <= 8)
    }
}
