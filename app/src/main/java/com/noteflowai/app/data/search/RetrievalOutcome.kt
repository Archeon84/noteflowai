package com.noteflowai.app.data.search

/**
 * Why the retrieval pipeline stopped early (guide §Phase 5 short-circuit).
 * [NO_CANDIDATES]: candidate generation produced nothing. [ALL_PRUNED]: every
 * candidate failed the metadata Filter & Eval Gate. [BELOW_THRESHOLD]: every
 * fused score was below RetrievalConfig.minimumScore.
 */
enum class ShortCircuitReason { NO_CANDIDATES, ALL_PRUNED, BELOW_THRESHOLD }

/**
 * Per-stage accounting for the Filter & Eval Gate, consumed by RetrievalEvaluator.
 */
data class RetrievalTrace(
    val candidatesGenerated: Int = 0,
    val prunedByDate: Int = 0,
    val prunedBySourceType: Int = 0,
    val prunedByRejectedEntity: Int = 0,
    val prunedByTimeline: Int = 0,
    val survivedPrune: Int = 0,
    val belowThreshold: Int = 0,
    val finalCount: Int = 0
)

/**
 * Everything `HybridRetriever.retrieve` now returns: the final ranked list, the
 * short-circuit reason (null = normal), and the Filter & Eval Gate telemetry.
 */
data class RetrievalOutcome(
    val results: List<RetrievalResult>,
    val shortCircuit: ShortCircuitReason? = null,
    val trace: RetrievalTrace? = null
)