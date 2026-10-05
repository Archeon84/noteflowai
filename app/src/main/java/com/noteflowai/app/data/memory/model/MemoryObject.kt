package com.noteflowai.app.data.memory.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Base memory object extracted from content. Can be a decision, commitment,
 * question, idea, fact, or opinion. Each must reference a SourceSegment.
 *
 * All extracted objects start as DETECTED and require user confirmation
 * before becoming ACTIVE.
 */
@Entity(
    tableName = "memory_objects",
    indices = [
        Index(value = ["type"]),
        Index(value = ["status"]),
        Index(value = ["sourceSegmentId"]),
        Index(value = ["sourceId"]),
        Index(value = ["projectEntityId"]),
        Index(value = ["ownerEntityId"]),
        Index(value = ["extractedAt"]),
        Index(value = ["sourceSegmentId", "normalizedStatement"])
    ]
)
data class MemoryObject(
    @PrimaryKey
    val id: String,

    /** Type of memory object. */
    val type: MemoryType,

    /** The extracted statement or assertion. */
    val statement: String,

    /** Normalized statement for deduplication (lowercase, trimmed). */
    val normalizedStatement: String,

    /** Current status of this memory object. */
    val status: MemoryObjectStatus = MemoryObjectStatus.DETECTED,

    /** Reference to the SourceSegment that contains this memory. */
    val sourceSegmentId: String,

    /** Reference to the parent source (note, recording, etc.). */
    val sourceId: String,

    /** Reference to an Entity representing the project (nullable). */
    val projectEntityId: String? = null,

    /** Reference to an Entity representing the owner/assignee (nullable). */
    val ownerEntityId: String? = null,

    /** Confidence score from extraction, 0.0-1.0. */
    val confidence: Float = 0.0f,

    /** Model used for extraction (nullable). */
    val extractionModel: String? = null,

    /** Unix epoch millis when this was extracted. */
    val extractedAt: Long = System.currentTimeMillis(),

    /** Unix epoch millis when the user confirmed this (nullable). */
    val confirmedAt: Long? = null,

    /** Due date in epoch millis (nullable, only if explicitly stated). */
    val dueAt: Long? = null,

    /** Review date in epoch millis (nullable). */
    val reviewAt: Long? = null,

    /** Additional metadata as JSON string (nullable). */
    val metadataJson: String? = null
)
