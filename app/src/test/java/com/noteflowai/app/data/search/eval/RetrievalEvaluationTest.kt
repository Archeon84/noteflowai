package com.noteflowai.app.data.search.eval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.search.RetrievalOutcome
import com.noteflowai.app.data.search.RetrievalResult
import com.noteflowai.app.data.search.ShortCircuitReason
import com.noteflowai.app.data.memory.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetrievalEvaluationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun metricWith(
        recallAt5: Double,
        recallAt10: Double,
        mrr: Double,
        refusalAcc: Double,
        unsupported: Double = 0.0,
        citePrec: Double = 1.0
    ) = RetrievalMetrics(
        recallAt5 = recallAt5, recallAt10 = recallAt10, mrr = mrr,
        citationPrecision = citePrec, unsupportedClaimRate = unsupported,
        refusalAccuracy = refusalAcc, medianLatencyMs = 0.0, p95LatencyMs = 0.0
    )

    @Test
    fun `recall and mrr computed from expected note ids`() {
        val outcome = RetrievalOutcome(
            results = listOf(
                RetrievalResult(sourceSegmentId = "s1", sourceId = "note_a.md", text = "t", sourceType = SourceType.NOTE, score = 0.9f, rank = 0),
                RetrievalResult(sourceSegmentId = "s2", sourceId = "note_b.md", text = "t", sourceType = SourceType.NOTE, score = 0.6f, rank = 1)
            )
        )
        val metrics = RetrievalEvaluator.evaluateSingle(
            EvalFixture(question = "q", expectedNoteIds = listOf("note_b.md", "note_missing.md"), expectedKeywords = emptyList(), mustCite = false, expectNotFound = false),
            outcome
        )
        // recall@5 = 1/2 = 0.5 ; recall@10 = 1/2 = 0.5 ; MRR: first expected note (note_b) at rank 2 → 1/2 = 0.5
        assertEquals(0.5, metrics.recallAt5, 0.001)
        assertEquals(0.5, metrics.recallAt10, 0.001)
        assertEquals(0.5, metrics.mrr, 0.001)
    }

    @Test
    fun `refusal accuracy rewards correct not-found handling`() {
        val notFoundOutcome = RetrievalOutcome(results = emptyList(), shortCircuit = ShortCircuitReason.NO_CANDIDATES)
        val foundOutcome = RetrievalOutcome(results = listOf(
            RetrievalResult(sourceSegmentId = "s9", sourceId = "note_z.md", text = "z", sourceType = SourceType.NOTE, score = 0.9f, rank = 0)
        ))

        val notFoundMetrics = RetrievalEvaluator.evaluateSingle(
            EvalFixture(question = "absent", expectedNoteIds = emptyList(), expectedKeywords = emptyList(), mustCite = false, expectNotFound = true),
            notFoundOutcome
        )
        val foundMetrics = RetrievalEvaluator.evaluateSingle(
            EvalFixture(question = "present", expectedNoteIds = listOf("note_z.md"), expectedKeywords = emptyList(), mustCite = false, expectNotFound = false),
            foundOutcome
        )
        assertEquals(1.0, notFoundMetrics.refusalAccuracy, 0.001)
        assertEquals(1.0, foundMetrics.refusalAccuracy, 0.001)
    }

    @Test
    fun `fixtures load from assets`() {
        val fixtures = RetrievalEvaluator.loadFixtures(context)
        assertTrue(fixtures.isNotEmpty())
        assertTrue(fixtures.any { it.question.isNotBlank() })
        // covers 7 query classes plus edge fixtures
        assertTrue(fixtures.size >= 12)
    }
}