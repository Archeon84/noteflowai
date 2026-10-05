package com.noteflowai.app.data.search

import android.util.Log
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository

/**
 * Merges, expands, and reranks retrieval results.
 * Handles neighbor expansion and intent-specific reranking.
 */
class ResultMerger(
    private val sourceSegmentRepository: SourceSegmentRepository,
    private val gteRerankerManager: com.noteflowai.app.data.search.reranker.GteRerankerManager? = null,
    private val settingsManager: com.noteflowai.app.data.settings.SettingsManager? = null
) {
    companion object {
        private const val TAG = "ResultMerger"
        private const val NEIGHBOR_EXPAND_LIMIT = 5
        private const val NEIGHBOR_SCORE_FACTOR = 0.4f
        private const val FINAL_CONTEXT_LIMIT = 8
    }

    /**
     * Expand results with neighboring segments and apply intent-specific reranking.
     */
    suspend fun expandAndRank(
        results: List<RetrievalResult>,
        plan: QueryPlan,
        rawQuery: String? = null
    ): List<RetrievalResult> {
        if (results.isEmpty()) return emptyList()

        // 1. Neighbor expansion for top results
        val expanded = expandNeighbors(results)

        // 2. Intent-specific reranking
        val reranked = rerankByIntent(expanded, plan)

        // 3. Deduplication
        val deduped = deduplicate(reranked)

        // 4. Optional Neural Cross-Encoder Reranking (Alibaba GTE Multilingual)
        val finalRanked = if (settingsManager?.rerankerEnabledBlocking == true &&
            gteRerankerManager?.isModelReady?.value == true &&
            !rawQuery.isNullOrBlank()
        ) {
            try {
                Log.d(TAG, "Applying GTE Multilingual Reranker on ${deduped.size} candidate passages...")
                gteRerankerManager.rerank(rawQuery, deduped, topK = FINAL_CONTEXT_LIMIT)
            } catch (e: Exception) {
                Log.w(TAG, "GTE Reranker failed, falling back to heuristic ranking: ${e.message}")
                deduped.take(FINAL_CONTEXT_LIMIT)
            }
        } else {
            deduped.take(FINAL_CONTEXT_LIMIT)
        }

        return finalRanked
    }

    /**
     * Expand results with adjacent segments from the same source.
     */
    private suspend fun expandNeighbors(results: List<RetrievalResult>): List<RetrievalResult> {
        val expanded = mutableListOf<RetrievalResult>()

        for ((index, result) in results.withIndex()) {
            expanded.add(result)

            // Only expand top results
            if (index >= NEIGHBOR_EXPAND_LIMIT) continue

            // Skip note-level results (they don't have segment IDs)
            if (result.sourceSegmentId.startsWith("note_")) continue

            try {
                val neighbors = sourceSegmentRepository.getNeighbors(
                    result.sourceId,
                    result.sourceSegmentId
                )

                for (neighbor in neighbors.take(2)) {
                    expanded.add(
                        RetrievalResult(
                            sourceSegmentId = neighbor.id,
                            sourceId = neighbor.sourceId,
                            text = neighbor.text,
                            sourceType = neighbor.sourceType,
                            score = result.score * NEIGHBOR_SCORE_FACTOR,
                            rank = result.rank + 1, // Slightly lower rank
                            startMs = neighbor.startMs,
                            endMs = neighbor.endMs,
                            pageNumber = neighbor.pageNumber,
                            metadata = (result.metadata ?: emptyMap()) + mapOf(
                                "isNeighbor" to "true",
                                "parentSegmentId" to result.sourceSegmentId
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Neighbor expansion failed for ${result.sourceSegmentId}: ${e.message}")
            }
        }

        return expanded
    }

    /**
     * Apply intent-specific reranking boosts.
     */
    private fun rerankByIntent(
        results: List<RetrievalResult>,
        plan: QueryPlan
    ): List<RetrievalResult> {
        return results.map { result ->
            val boost = when (plan.intent) {
                QueryIntent.DECISION_LOOKUP -> {
                    val status = result.metadata?.get("status") ?: ""
                    when {
                        status in listOf("CONFIRMED", "ACTIVE") -> 1.3f
                        status == "DETECTED" -> 0.8f
                        else -> 1.0f
                    }
                }
                QueryIntent.COMMITMENT_LOOKUP -> {
                    val isOverdue = result.metadata?.get("isOverdue") == "true"
                    val status = result.metadata?.get("status") ?: ""
                    when {
                        isOverdue -> 1.4f
                        status in listOf("CONFIRMED", "ACTIVE") -> 1.2f
                        else -> 1.0f
                    }
                }
                QueryIntent.CHANGE_ANALYSIS -> {
                    // Chronological ordering handled separately
                    1.0f
                }
                else -> 1.0f
            }

            result.copy(score = result.score * boost)
        }.let { reranked ->
            // For CHANGE_ANALYSIS, sort chronologically. decidedAt may be
            // epoch-millis text or an ISO string depending on the producer;
            // unparseable values sort as oldest (0L) instead of crashing.
            if (plan.intent == QueryIntent.CHANGE_ANALYSIS) {
                reranked.sortedBy { parseChronoMillis(it.metadata?.get("decidedAt")) }
            } else {
                reranked.sortedByDescending { it.score }
            }
        }
    }

    private fun parseChronoMillis(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        value.toLongOrNull()?.let { return it }
        return try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }

    /**
     * Deduplicate results by source segment ID.
     */
    private fun deduplicate(results: List<RetrievalResult>): List<RetrievalResult> {
        val seen = mutableSetOf<String>()
        return results.filter { result ->
            val key = result.sourceSegmentId
            if (key in seen) false else {
                seen.add(key)
                true
            }
        }
    }
}
