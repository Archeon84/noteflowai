package com.noteflowai.app.data.search

/**
 * Configurable retrieval pipeline knobs (guide §Phase 5).
 *
 * Weights must sum to 1.0 so the weighted fusion stays in [0,1] after per-source
 * min-max normalization. [topK] caps the candidate set before the Filter & Eval
 * Gate; [minimumScore] is the post-fusion rejection threshold; [maxContextTokens]
 * replaces PromptAssembler's hardcoded context budget.
 */
data class RetrievalConfig(
    val bm25Weight: Float = 0.4f,
    val vectorWeight: Float = 0.4f,
    val entityWeight: Float = 0.12f,
    val recencyWeight: Float = 0.08f,
    val topK: Int = 40,
    val minimumScore: Float = 0.28f,
    val maxContextTokens: Int = 8000
)