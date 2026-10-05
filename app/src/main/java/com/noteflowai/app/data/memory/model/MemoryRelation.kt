package com.noteflowai.app.data.memory.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A typed relation between two memory objects, entities, or source segments.
 * Relations support the knowledge graph and multi-hop reasoning.
 */
@Entity(
    tableName = "memory_relations",
    indices = [
        Index(value = ["fromType", "fromId"]),
        Index(value = ["toType", "toId"]),
        Index(value = ["relationType"]),
        Index(value = ["sourceSegmentId"]),
        Index(value = ["fromId"]),
        Index(value = ["toId"])
    ]
)
data class MemoryRelation(
    @PrimaryKey
    val id: String,

    /** Type of the source object (e.g., "DECISION", "COMMITMENT", "ENTITY", "SOURCE_SEGMENT"). */
    val fromType: String,

    /** ID of the source object. */
    val fromId: String,

    /** Type of the target object. */
    val toType: String,

    /** ID of the target object. */
    val toId: String,

    /** The type of relationship. */
    val relationType: RelationType,

    /** Reference to the SourceSegment where this relation was observed (nullable). */
    val sourceSegmentId: String? = null,

    /** Confidence in this relation, 0.0-1.0. */
    val confidence: Float = 0.0f,

    /** Unix epoch millis when this relation was created. */
    val createdAt: Long = System.currentTimeMillis()
)
