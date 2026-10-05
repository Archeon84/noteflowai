package com.noteflowai.app.data.search.eval

import android.content.Context
import com.google.gson.Gson
import com.noteflowai.app.data.search.RetrievalOutcome
import com.noteflowai.app.data.search.RetrievalResult
import com.noteflowai.app.data.search.ShortCircuitReason

/**
 * Evaluation fixture (guide §Phase 5). [expectedNoteIds] seed Recall/MRR;
 * [expectedKeywords] seed the unsupported-claim heuristic; [mustCite] gates
 * citation precision; [expectNotFound] gates refusal accuracy.
 */
data class EvalFixture(
    val question: String,
    val expectedNoteIds: List<String> = emptyList(),
    val expectedKeywords: List<String> = emptyList(),
    val mustCite: Boolean = false,
    val expectNotFound: Boolean = false
)

/**
 * The 8 retrieval metrics (guide §Phase 5). Latency fields are populated only by
 * the on-device runner; the JVM gate ignores them.
 */
data class RetrievalMetrics(
    val recallAt5: Double,
    val recallAt10: Double,
    val mrr: Double,
    val citationPrecision: Double,
    val unsupportedClaimRate: Double,
    val refusalAccuracy: Double,
    val medianLatencyMs: Double,
    val p95LatencyMs: Double
)

/**
 * Deterministic evaluation over fixtures. Pure functions shared by the JVM CI
 * gate and the on-device latency run (Option C).
 */
object RetrievalEvaluator {

    fun loadFixtures(context: Context): List<EvalFixture> {
        val json = context.assets.open("retrieval_fixtures.json")
            .bufferedReader().use { it.readText() }
        return Gson().fromJson(json, Array<EvalFixture>::class.java).toList()
    }

    fun evaluateSingle(fixture: EvalFixture, outcome: RetrievalOutcome): RetrievalMetrics {
        val results = outcome.results
        val sourceIds = results.map { it.sourceId }

        // Recall@k
        val expected = fixture.expectedNoteIds
        val recall5 = if (expected.isEmpty()) 1.0 else {
            expected.count { it in sourceIds.take(5) }.toDouble() / expected.size
        }
        val recall10 = if (expected.isEmpty()) 1.0 else {
            expected.count { it in sourceIds.take(10) }.toDouble() / expected.size
        }

        // MRR: reciprocal rank of first expected hit
        val mrr = if (expected.isEmpty()) 1.0 else {
            val firstHit = expected.map { expectedId ->
                val idx = sourceIds.indexOf(expectedId)
                if (idx >= 0) 1.0 / (idx + 1) else 0.0
            }.maxOrNull() ?: 0.0
            firstHit
        }

        // Refusal accuracy. An empty result with no short-circuit (e.g. an
        // intent path returning a bare empty list, or the catch-all empty
        // outcome) is still a refusal — the old check required a non-null
        // shortCircuit and scored correct empties as refusal failures.
        val shouldRefuse = fixture.expectNotFound
        val didRefuse = outcome.results.isEmpty()
        val refusalAccuracy = if (shouldRefuse == didRefuse) 1.0 else 0.0

        // Citation precision (proxy): for mustCite fixtures, expected notes
        // found / total notes RETURNED. The old denominator (expected count)
        // measured recall, letting irrelevant extras pass unpenalized.
        val citePrecision = if (fixture.mustCite) {
            if (sourceIds.isEmpty()) 0.0 else {
                expected.count { it in sourceIds }.toDouble() / sourceIds.size
            }
        } else 1.0

        // Unsupported claim heuristic (deterministic proxy): the proportion of
        // expectedKeywords absent from the retrieved text. Full LLM pass is manual.
        val retrievedText = results.joinToString(" ") { it.text }
        val covered = fixture.expectedKeywords.count { kw ->
            retrievedText.contains(kw, ignoreCase = true)
        }
        val unsupported = if (fixture.expectedKeywords.isEmpty()) 0.0 else {
            (fixture.expectedKeywords.size - covered).toDouble() / fixture.expectedKeywords.size
        }

        // Latency is not measured deterministically here (on-device runner only).
        return RetrievalMetrics(
            recallAt5 = recall5,
            recallAt10 = recall10,
            mrr = mrr,
            citationPrecision = citePrecision,
            unsupportedClaimRate = unsupported,
            refusalAccuracy = refusalAccuracy,
            medianLatencyMs = 0.0,
            p95LatencyMs = 0.0
        )
    }

    fun aggregate(metrics: List<RetrievalMetrics>): RetrievalMetrics {
        fun mean(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.average()
        return RetrievalMetrics(
            recallAt5 = mean(metrics.map { it.recallAt5 }),
            recallAt10 = mean(metrics.map { it.recallAt10 }),
            mrr = mean(metrics.map { it.mrr }),
            citationPrecision = mean(metrics.map { it.citationPrecision }),
            unsupportedClaimRate = mean(metrics.map { it.unsupportedClaimRate }),
            refusalAccuracy = mean(metrics.map { it.refusalAccuracy }),
            medianLatencyMs = metrics.map { it.medianLatencyMs }.median(),
            p95LatencyMs = metrics.map { it.p95LatencyMs }.percentile(95.0)
        )
    }

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val s = sorted()
        val n = s.size
        return if (n % 2 == 0) (s[n/2 - 1] + s[n/2]) / 2.0 else s[n/2]
    }

    private fun List<Double>.percentile(p: Double): Double {
        if (isEmpty()) return 0.0
        val s = sorted()
        val idx = ((s.size - 1) * p / 100.0).toInt()
        return s[idx]
    }
}