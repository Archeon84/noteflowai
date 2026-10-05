package com.noteflowai.app.data.memory.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A decision extracted from or confirmed by the user.
 * Decisions track what was decided, why, and when.
 * They can be reversed or superseded over time.
 */
@Entity(
    tableName = "decisions",
    foreignKeys = [
        ForeignKey(
            entity = MemoryObject::class,
            parentColumns = ["id"],
            childColumns = ["memoryObjectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["status"]),
        Index(value = ["sourceSegmentId"]),
        Index(value = ["projectEntityId"]),
        Index(value = ["decidedAt"]),
        Index(value = ["memoryObjectId"]),
        Index(value = ["reviewAt"])
    ]
)
data class Decision(
    @PrimaryKey
    val id: String,

    /** Reference to the parent MemoryObject. */
    val memoryObjectId: String,

    /** The decision statement. */
    val statement: String,

    /** Reason for the decision (nullable). */
    val reason: String? = null,

    /** Alternatives considered as JSON array (nullable). */
    val alternativesJson: String? = null,

    /** Current status. */
    val status: DecisionStatus = DecisionStatus.DETECTED,

    /** Reference to the SourceSegment evidence. */
    val sourceSegmentId: String,

    /** When the decision was made (nullable, may be extracted date). */
    val decidedAt: Long? = null,

    /** When to review this decision (nullable). */
    val reviewAt: Long? = null,

    /** Reference to a project Entity (nullable). */
    val projectEntityId: String? = null,

    /** Confidence from extraction, 0.0-1.0. */
    val confidence: Float = 0.0f,

    /** True if the user has confirmed this decision. */
    val userConfirmed: Boolean = false,

    /** Unix epoch millis when created. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Unix epoch millis when last updated. */
    val updatedAt: Long = System.currentTimeMillis()
)
