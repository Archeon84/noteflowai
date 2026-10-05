package com.noteflowai.app.data.memory.model

/**
 * Types of entities that can be extracted from content.
 */
enum class EntityType {
    PERSON,
    PROJECT,
    COMPANY,
    PLACE,
    TOPIC,
    PRODUCT,
    ORGANIZATION,
    /**
     * Corrupt or future-unknown value read from disk. Never silently
     * treated as PERSON.
     */
    UNKNOWN
}
