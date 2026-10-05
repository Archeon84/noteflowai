package com.noteflowai.app.data.search.eval

import android.content.Context
import com.google.gson.Gson
import com.noteflowai.app.data.search.HybridRetriever
import com.noteflowai.app.data.search.RetrievalConfig
import java.io.File

/**
 * Debug-only on-device latency measurement (guide §Phase 5). Latency is meaningless
 * on the JVM, so median/p95 retrieval latency is measured against the real embedder
 * and disk index on the device. Writes a JSON report to filesDir.
 */
data class Parameters(
    val embeddingQuery: String = "",
    val embeddingEnabled: Boolean = false,
    val embeddingModel: String = "",
    val provider: String = "",
    val apiKey: String = "",
    val baseUrl: String = ""
)

class RetrievalLatencyRunner {

    suspend fun run(
        context: Context,
        retriever: HybridRetriever,
        config: RetrievalConfig,
        parameters: Parameters,
        fixtures: List<EvalFixture>,
        warmupRuns: Int = 3
    ): File {
        val times = mutableListOf<Pair<Long, Int>>() // (elapsedMs, runIndex)
        for (run in 0 until warmupRuns + 1) {
            for ((fixtureIndex, fixture) in fixtures.withIndex()) {
                val start = System.nanoTime()
                retriever.retrieve(
                    plan = com.noteflowai.app.data.search.QueryParser().parse(fixture.question),
                    // Measure the fixture's own question through the embedding
                    // channel — the old shared (default blank) query measured
                    // BM25-only even when embeddings were enabled.
                    embeddingQuery = fixture.question,
                    embeddingEnabled = parameters.embeddingEnabled,
                    embeddingModel = parameters.embeddingModel,
                    provider = parameters.provider,
                    apiKey = parameters.apiKey,
                    baseUrl = parameters.baseUrl
                )
                val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                if (run > 0) times.add(elapsedMs.toLong() to fixtureIndex)
            }
        }

        fun median(list: List<Long>): Double {
            if (list.isEmpty()) return 0.0
            val s = list.sorted()
            val n = s.size
            return if (n % 2 == 0) (s[n/2 - 1] + s[n/2]) / 2.0 else s[n/2].toDouble()
        }
        fun p95(list: List<Long>): Double {
            if (list.isEmpty()) return 0.0
            val s = list.sorted()
            val idx = ((s.size - 1) * 95 / 100).toInt()
            return s[idx].toDouble()
        }

        val report = mapOf(
            "medianLatencyMs" to median(times.map { it.first }),
            "p95LatencyMs" to p95(times.map { it.first }),
            "fixturesCount" to fixtures.size,
            "warmupRuns" to warmupRuns
        )

        val file = File(context.filesDir, "retrieval_eval_report.json")
        file.writeText(Gson().toJson(report))
        return file
    }
}