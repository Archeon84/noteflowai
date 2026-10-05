package com.noteflowai.app.data.search

import com.noteflowai.app.data.memory.model.SourceType

/**
 * Unified result type from the Query Planner pipeline.
 * Bridges note search results and memory object retrieval.
 *
 * [score] is the post-fusion score the caller reads. The nullable per-channel fields
 * ([bm25Score], [vectorScore], [entityBoost], [recencyBoost]) carry the raw values
 * BEFORE fusion so HybridRetriever's weighted fuse can weight each channel genuinely;
 * they are additive (null = channel not exercised for this result).
 */
data class RetrievalResult(
    val sourceSegmentId: String,
    val memoryObjectId: String? = null,
    val sourceId: String,
    val text: String,
    val sourceType: SourceType? = null,
    val score: Float,
    val rank: Int,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val pageNumber: Int? = null,
    val metadata: Map<String, String>? = null,
    val bm25Score: Float? = null,
    val vectorScore: Float? = null,
    val entityBoost: Float? = null,
    val recencyBoost: Float? = null
)
