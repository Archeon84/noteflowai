package com.noteflowai.app.data.memory.model

/**
 * Status values for memory objects (MemoryObject base status).
 */
enum class MemoryObjectStatus {
    DETECTED,
    CONFIRMED,
    ACTIVE,
    COMPLETED,
    CANCELLED,
    SUPERSEDED,
    UNCERTAIN,
    EXPIRED
}

/**
 * Status values for decisions.
 */
enum class DecisionStatus {
    DETECTED,
    CONFIRMED,
    ACTIVE,
    REVERSED,
    SUPERSEDED,
    COMPLETED,
    UNCERTAIN
}

/**
 * Status values for commitments.
 */
enum class CommitmentStatus {
    DETECTED,
    CONFIRMED,
    ACTIVE,
    COMPLETED,
    CANCELLED,
    OVERDUE,
    UNCERTAIN
}

/**
 * Status values for review items.
 */
enum class ReviewItemStatus {
    PENDING,
    ACCEPTED,
    EDITED,
    IGNORED
}

/**
 * Type of review items.
 */
enum class ReviewItemType {
    DECISION,
    COMMITMENT,
    ENTITY,
    CONFLICT,
    QUESTION
}
