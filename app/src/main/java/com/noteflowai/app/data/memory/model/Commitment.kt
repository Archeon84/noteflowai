package com.noteflowai.app.data.memory.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A commitment or promise extracted from or confirmed by the user.
 * Commitments track action items, promises, and follow-ups.
 * They follow a state machine: DETECTED -> CONFIRMED -> ACTIVE -> COMPLETED/CANCELLED/OVERDUE.
 */
@Entity(
    tableName = "commitments",
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
        Index(value = ["ownerEntityId"]),
        Index(value = ["dueAt"]),
        Index(value = ["memoryObjectId"]),
        Index(value = ["completedAt"]),
        Index(value = ["createdAt"])
    ]
)
data class Commitment(
    @PrimaryKey
    val id: String,

    /** Reference to the parent MemoryObject. */
    val memoryObjectId: String,

    /** The action to be taken. */
    val action: String,

    /** Text description of the owner/assignee (nullable). */
    val ownerText: String? = null,

    /** Reference to an Entity representing the owner (nullable). */
    val ownerEntityId: String? = null,

    /** Due date in epoch millis (nullable, only if explicitly stated). */
    val dueAt: Long? = null,

    /** Current status. */
    val status: CommitmentStatus = CommitmentStatus.DETECTED,

    /** Reference to the SourceSegment evidence. */
    val sourceSegmentId: String,

    /** Reference to a project Entity (nullable). */
    val projectEntityId: String? = null,

    /** Confidence from extraction, 0.0-1.0. */
    val confidence: Float = 0.0f,

    /** True if the user has confirmed this commitment. */
    val userConfirmed: Boolean = false,

    /** Unix epoch millis when completed (nullable). */
    val completedAt: Long? = null,

    /** Unix epoch millis when created. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Unix epoch millis when last updated. */
    val updatedAt: Long = System.currentTimeMillis()
)
