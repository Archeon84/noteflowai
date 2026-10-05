package com.noteflowai.app.data.search

import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.model.SourceType

/**
 * Intent classification for user queries.
 * Used by QueryParser to determine retrieval strategy.
 */
enum class QueryIntent {
    NOTE_SEARCH,
    SOURCE_SUMMARY,
    DECISION_LOOKUP,
    COMMITMENT_LOOKUP,
    CHANGE_ANALYSIS,
    CONFLICT_ANALYSIS,
    ENTITY_LOOKUP,
    PROJECT_LOOKUP,
    GENERAL_RAG,
    CREATE_NOTE,
    CREATE_TASK,
    CREATE_DECISION
}

/**
 * Structured query plan produced by QueryParser.
 * Drives retrieval strategy in HybridRetriever.
 */
data class QueryPlan(
    val intent: QueryIntent,
    val queryText: String,
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val entities: List<String> = emptyList(),
    val projectIds: List<String> = emptyList(),
    val sourceTypes: List<SourceType> = emptyList(),
    val memoryTypes: List<MemoryType> = emptyList(),
    val includeUnconfirmed: Boolean = false,
    val requiresTimeline: Boolean = false,
    val requiresComparison: Boolean = false,
    val requiresActionConfirmation: Boolean = false,
    val isComprehensive: Boolean = false
)
