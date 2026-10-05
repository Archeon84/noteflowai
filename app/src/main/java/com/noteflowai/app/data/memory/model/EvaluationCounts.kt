package com.noteflowai.app.data.memory.model

/**
 * Shared result type for Room GROUP BY / COUNT(*) aggregate queries
 * used by the Phase 8d Evaluation dashboard.
 *
 * Room maps the query result columns (`status`/`cnt`) onto the
 * constructor parameters by name. `status` carries any column value —
 * a ProcessingState name, an AnswerCitation supportStatus, a ReviewItemStatus,
 * a MemoryObjectStatus, or a locationType — depending on which DAO returns it.
 */
data class StatusCount(
    val status: String,
    val cnt: Int
)
