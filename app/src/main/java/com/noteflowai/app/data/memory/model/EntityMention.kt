package com.noteflowai.app.data.memory.model

import androidx.room.Entity
import androidx.room.Index

/**
 * A mention of an entity within a specific source segment.
 * Links entities to the evidence where they appear.
 */
@Entity(
    tableName = "entity_mentions",
    primaryKeys = ["entityId", "sourceSegmentId"],
    indices = [
        Index(value = ["entityId"]),
        Index(value = ["sourceSegmentId"]),
        Index(value = ["confirmation"])
    ]
)
data class EntityMention(
    /** Reference to the Entity. */
    val entityId: String,

    /** Reference to the SourceSegment where this entity is mentioned. */
    val sourceSegmentId: String,

    /** The exact text used to mention this entity in the source. */
    val mentionText: String,

    /** Confidence that this mention refers to the entity, 0.0-1.0. */
    val confidence: Float = 0.0f,

    /** Unix epoch millis when this mention was recorded. */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * User confirmation state (guide §Phase 4). A REJECTED mention keeps its row
     * (so a rebuild cannot re-insert over the same primary key) but is excluded from
     * retrieval and note-entity linking ("Remove link without deleting source note").
     */
    val confirmation: ConfirmationState = ConfirmationState.SUGGESTED
)
