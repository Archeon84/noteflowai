package com.noteflowai.app.data.memory.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A citation linking an AI answer to its supporting source evidence.
 * Used in Phase 5 for grounded chat, but the entity is defined here
 * so the database schema is complete.
 */
@Entity(
    tableName = "answer_citations",
    indices = [
        Index(value = ["answerId"]),
        Index(value = ["sourceSegmentId"]),
        Index(value = ["supportStatus"]),
        Index(value = ["answerId", "claimIndex"])
    ]
)
data class AnswerCitation(
    @PrimaryKey
    val id: String,

    /** Identifier of the answer this citation supports. */
    val answerId: String,

    /** Reference to the SourceSegment evidence. */
    val sourceSegmentId: String,

    /** Index of the claim this citation supports in the answer. */
    val claimIndex: Int,

    /** Type of source location. */
    val locationType: String,

    /** Audio start timestamp in millis (nullable). */
    val startMs: Long? = null,

    /** Audio end timestamp in millis (nullable). */
    val endMs: Long? = null,

    /** PDF page number (nullable). */
    val pageNumber: Int? = null,

    /** YouTube/video URL (nullable). */
    val url: String? = null,

    /** Whether this citation was validated. */
    val supportStatus: String = "PENDING",

    /** The quote text from the source segment (Phase 6 v8). */
    val quoteText: String? = null,

    /** Unix epoch millis when created. */
    val createdAt: Long = System.currentTimeMillis()
)
