package com.noteflowai.app.data.memory.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A dated / time-bounded event extracted from a source and surfaced on the timeline
 * (guide §Phase 4).
 *
 * Retains every field the guide requires: Title, Date/range ([startMs]/[endMs]),
 * [TemporalPrecision], Source note ID ([sourceNoteId]), [confidence],
 * [confirmation], and the raw extraction ([originalExtraction]).
 */
@Entity(
    tableName = "timeline_entries",
    indices = [
        Index(value = ["startMs"]),
        Index(value = ["sourceNoteId"]),
        Index(value = ["confirmation"]),
        Index(value = ["sourceSegmentId"])
    ]
)
data class TimelineEntry(
    @PrimaryKey
    val id: String,

    /** The event title as extracted (or as edited by the user). */
    val title: String,

    /** Range lower bound in epoch millis (null for RELATIVE / UNKNOWN). */
    val startMs: Long? = null,

    /** Range upper bound in epoch millis; == [startMs] for [TemporalPrecision.EXACT]. */
    val endMs: Long? = null,

    /** Granularity of the date/range. */
    val precision: TemporalPrecision = TemporalPrecision.UNKNOWN,

    /** Source segment this entry was extracted from. */
    val sourceSegmentId: String,

    /** Note fileName (== segment.sourceId) the entry originated from. */
    val sourceNoteId: String,

    /** Extraction confidence, 0.0-1.0. */
    val confidence: Float = 0.5f,

    /** User confirmation state (SUGGESTED until reviewed). */
    val confirmation: ConfirmationState = ConfirmationState.SUGGESTED,

    /** Raw LLM JSON of the extracted entry, for reverting an edit. */
    val originalExtraction: String? = null,

    val createdAt: Long = System.currentTimeMillis(),

    val updatedAt: Long = System.currentTimeMillis()
)