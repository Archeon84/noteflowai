package com.noteflowai.app.data.chat

import android.content.Context
import com.noteflowai.app.data.search.ConflictDetector
import com.noteflowai.app.data.search.RetrievalConfig
import com.noteflowai.app.data.search.RetrievalResult
import com.noteflowai.app.data.memory.model.SourceType
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

// PreCallDecision is a sealed class inside PreCallRefuser
import com.noteflowai.app.data.chat.PreCallRefuser.PreCallDecision

/**
 * Unit tests for PreCallRefuser (Phase 6 Task 10).
 *
 * Tests the three pre-call triggers:
 * - T1: empty retrieval
 * - T2: all results below minimumScore
 * - T3: conflicting sources
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreCallRefuserTest {

    private val conflictDetector: ConflictDetector = mockk()
    private val context: Context = RuntimeEnvironment.application
    private val refuser = PreCallRefuser(context, conflictDetector)

    private fun retrieval(
        sourceSegmentId: String,
        score: Float = 0.9f,
        sourceId: String = "note.md",
        sourceType: SourceType = SourceType.NOTE
    ) = RetrievalResult(
        sourceSegmentId = sourceSegmentId,
        sourceId = sourceId,
        text = "Sample text",
        sourceType = sourceType,
        score = score,
        rank = 0
    )

    @Test
    fun `T1 empty retrieval returns REFUSE with empty_retrieval template`() {
        val results = emptyList<RetrievalResult>()
        val decision = refuser.decide(results)

        assertTrue(decision is PreCallDecision.REFUSE)
        val refuse = decision as PreCallDecision.REFUSE
        assertEquals(PreCallDecision.RefusalReason.T1_EMPTY_RETRIEVAL, refuse.reason)
        assertTrue(refuse.message.contains("don't have any notes covering this"))
    }

    @Test
    fun `T2 all results below minimumScore returns REFUSE with below_floor template`() {
        val config = RetrievalConfig(minimumScore = 0.5f)
        val results = listOf(
            retrieval("seg-1", score = 0.2f),
            retrieval("seg-2", score = 0.1f)
        )
        val decision = refuser.decide(results, config)

        assertTrue(decision is PreCallDecision.REFUSE)
        val refuse = decision as PreCallDecision.REFUSE
        assertEquals(PreCallDecision.RefusalReason.T2_BELOW_FLOOR, refuse.reason)
        assertTrue(refuse.message.contains("none of them cover this confidently"))
    }

    @Test
    fun `T2 at least one result above floor returns PROCEED`() {
        val config = RetrievalConfig(minimumScore = 0.5f)
        val results = listOf(
            retrieval("seg-1", score = 0.2f),
            retrieval("seg-2", score = 0.8f)
        )
        coEvery { conflictDetector.findConflicts(maxResults = 3) } returns emptyList()
        val decision = refuser.decide(results, config)

        assertTrue(decision is PreCallDecision.PROCEED)
    }

    @Test
    fun `T3 conflict detector returns conflicts returns REFUSE with conflict template`() {
        val config = RetrievalConfig()
        val results = listOf(retrieval("seg-1", score = 0.9f))
        val conflict = ConflictDetector.ConflictResult(
            concept = "supplier",
            noteA = "note1.md",
            noteB = "note2.md",
            contextA = "positive context",
            contextB = "negative context",
            reason = "Positive in note1, negative in note2"
        )
        coEvery { conflictDetector.findConflicts(maxResults = 3) } returns listOf(conflict)

        val decision = refuser.decide(results, config)

        assertTrue(decision is PreCallDecision.REFUSE)
        val refuse = decision as PreCallDecision.REFUSE
        assertEquals(PreCallDecision.RefusalReason.T3_CONFLICT, refuse.reason)
        assertTrue(refuse.message.contains("1 notes mentioning this, but they conflict"))
    }

    @Test
    fun `T3 conflict detector returns empty returns PROCEED`() {
        val config = RetrievalConfig()
        val results = listOf(retrieval("seg-1", score = 0.9f))
        coEvery { conflictDetector.findConflicts(maxResults = 3) } returns emptyList()

        val decision = refuser.decide(results, config)

        assertTrue(decision is PreCallDecision.PROCEED)
    }

    @Test
    fun `decide uses default config when not provided`() {
        val results = listOf(
            retrieval("seg-1", score = 0.2f),
            retrieval("seg-2", score = 0.1f)
        )
        // default minimumScore = 0.28f
        val decision = refuser.decide(results)

        assertTrue(decision is PreCallDecision.REFUSE)
        val refuse = decision as PreCallDecision.REFUSE
        assertEquals(PreCallDecision.RefusalReason.T2_BELOW_FLOOR, refuse.reason)
    }

    @Test
    fun `conflict template includes correct conflict count`() {
        val config = RetrievalConfig()
        val results = listOf(retrieval("seg-1", score = 0.9f))
        val conflicts = listOf(
            ConflictDetector.ConflictResult("c", "a", "b", "ca", "cb", "r"),
            ConflictDetector.ConflictResult("c", "a", "b", "ca", "cb", "r")
        )
        coEvery { conflictDetector.findConflicts(maxResults = 3) } returns conflicts

        val decision = refuser.decide(results, config)

        assertTrue(decision is PreCallDecision.REFUSE)
        val refuse = decision as PreCallDecision.REFUSE
        assertTrue(refuse.message.contains("2 notes mentioning this, but they conflict"))
    }
}