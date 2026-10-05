package com.noteflowai.app.data.memory.model

/**
 * Types of memory objects that can be extracted from content.
 */
enum class MemoryType {
    DECISION,
    COMMITMENT,
    QUESTION,
    IDEA,
    FACT,
    OPINION,
    /**
     * Corrupt or future-unknown value read from disk. Never silently
     * treated as FACT.
     */
    UNKNOWN
}
