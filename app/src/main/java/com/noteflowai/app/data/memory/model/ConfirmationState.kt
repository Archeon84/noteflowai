package com.noteflowai.app.data.memory.model

/**
 * User-facing confirmation state for an entity or timeline entry (guide §Phase 4).
 *
 * Stored as `.name` strings via [com.noteflowai.app.data.memory.db.Converters].
 * A REJECTED entity or mention is excluded from retrieval and never resurrected by
 * a rebuild; a REJECTED entity also stops new mentions from being attached to it.
 */
enum class ConfirmationState {
    /** Extracted automatically, awaiting user review. */
    SUGGESTED,

    /** User explicitly confirmed the extraction. */
    CONFIRMED,

    /** User explicitly rejected the extraction; it stays durable but is hidden. */
    REJECTED,

    /** The value was merged into another entity (reserved for future use). */
    MERGED
}