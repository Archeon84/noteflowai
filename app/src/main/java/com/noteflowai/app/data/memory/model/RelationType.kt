package com.noteflowai.app.data.memory.model

/**
 * Types of relations between memory objects, entities, and source segments.
 */
enum class RelationType {
    RELATED_TO,
    SUPPORTS,
    CONTRADICTS,
    FOLLOWS_UP,
    BELONGS_TO,
    SUPERSEDES,
    MENTIONS
}
