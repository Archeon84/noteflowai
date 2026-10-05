package com.noteflowai.app.data.memory.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A canonical, stable representation of a searchable piece of content.
 * Every extracted memory, citation, and relation must trace back to a SourceSegment.
 *
 * Each source (note, recording, document, etc.) produces one or more SourceSegments
 * that preserve location metadata (timestamps, page numbers, block IDs).
 */
@Entity(
    tableName = "source_segments",
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["sourceType"]),
        Index(value = ["createdAt"]),
        Index(value = ["startMs"]),
        Index(value = ["pageNumber"]),
        Index(value = ["sourceId", "sourceType"])
        // NOTE: no index on normalizedText — it is only searched with
        // leading-wildcard LIKE, which a btree index cannot serve.
    ]
)
data class SourceSegment(
    @PrimaryKey
    val id: String,

    /** Parent note, recording, document, OCR, or YouTube source identifier. */
    val sourceId: String,

    /** Type of the parent source. */
    val sourceType: SourceType,

    /** Original text content of this segment. */
    val text: String,

    /** Normalized text for search indexing (lowercased, stripped). */
    val normalizedText: String,

    /** Start timestamp in milliseconds (nullable for non-audio sources). */
    val startMs: Long? = null,

    /** End timestamp in milliseconds (nullable for non-audio sources). */
    val endMs: Long? = null,

    /** Page number for PDF/document sources (nullable). */
    val pageNumber: Int? = null,

    /** Block/paragraph identifier within a document or OCR result (nullable). */
    val blockId: String? = null,

    /** URL for YouTube or web sources (nullable). */
    val url: String? = null,

    /** Speaker identifier for multi-speaker audio (nullable). */
    val speaker: String? = null,

    /** ISO-639 language code of the segment text (nullable). */
    val language: String? = null,

    /** Transcription engine used: "whisper", "whisper_npu", "deepgram", "ocr", "import" (nullable). */
    val transcriptionEngine: String? = null,

    /** Confidence score from the source processor, 0.0-1.0 (nullable). */
    val confidence: Float? = null,

    /** Unix epoch millis when this segment was created. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Unix epoch millis when this segment was last updated. */
    val updatedAt: Long = System.currentTimeMillis(),

    /** True if this is the original content; false if translated or transformed. */
    @ColumnInfo(defaultValue = "1")
    val isOriginalContent: Boolean = true,

    /** ID of the parent segment if this is a sub-segment (nullable). */
    val parentSegmentId: String? = null,

    /** Additional metadata as a JSON string (nullable). */
    val metadataJson: String? = null
)
