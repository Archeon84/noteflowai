package com.noteflowai.app.data.memory.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Status of a detected conflict.
 */
enum class ConflictStatus {
    PENDING,
    CONFIRMED,
    DISMISSED,
    RESOLVED
}

/**
 * A detected conflict between memory objects, decisions, commitments, or dates.
 * Conflicts are detected by [com.noteflowai.app.data.memory.analysis.ConflictDetectionService]
 * and presented to the user for resolution.
 *
 * User-facing language must use conservative phrasing:
 * - "Possible conflict"
 * - "These sources differ"
 * - "Please confirm which is current"
 */
@Entity(
    tableName = "conflicts",
    indices = [
        Index(value = ["status"]),
        Index(value = ["conflictType"]),
        Index(value = ["createdAt"]),
        Index(value = ["objectIds"])
    ]
)
data class Conflict(
    @PrimaryKey
    val id: String,

    /** Type of conflict: DATE_CONFLICT, DECISION_CONFLICT, DEADLINE_CONFLICT, STATUS_CONFLICT. */
    val conflictType: String,

    /** JSON array of IDs of the conflicting objects. */
    val objectIds: String,

    /** JSON array of source segment IDs providing evidence for this conflict. */
    val sourceSegmentIds: String,

    /** Epoch millis of the earliest conflicting observation. */
    val firstObservedAt: Long,

    /** Epoch millis of the latest conflicting observation. */
    val latestObservedAt: Long,

    /** Confidence in this conflict detection, 0.0-1.0. */
    val confidence: Float = 0.0f,

    /** Current resolution status. */
    val status: ConflictStatus = ConflictStatus.PENDING,

    /** User-provided resolution text (nullable, set when resolved). */
    val resolution: String? = null,

    /** Unix epoch millis when created. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Unix epoch millis when resolved (nullable). */
    val resolvedAt: Long? = null
)
