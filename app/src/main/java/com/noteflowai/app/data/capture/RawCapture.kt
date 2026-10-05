package com.noteflowai.app.data.capture

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.noteflowai.app.data.memory.model.SourceType

/**
 * Lifecycle state of a raw capture, independent of its derived AI processing.
 */
enum class CaptureStatus {
    /** The capture was persisted but no processing job has run yet. */
    CAPTURED,

    /** A processing job is or has been scheduled/running for this capture. */
    PROCESSING,

    /** Processing completed; the capture and its derived data are ready. */
    READY,

    /** Processing failed permanently; the raw capture remains intact. */
    FAILED
}

/**
 * A durable record of a raw capture (note, recording, document, OCR, ...).
 *
 * Written to the [com.noteflowai.app.data.memory.db.MemoryDatabase] at capture time — *before* any
 * AI processing — so the raw content survives app force-close and is independent of the processing
 * pipeline. `sourceId` mirrors [com.noteflowai.app.data.memory.model.ProcessingStatus.sourceId] so
 * the two rows stay in sync.
 *
 * Raw bytes live in their existing locations (note JSON files, WAV recordings); this row is the
 * durable "what was captured, and where" record plus a coarse lifecycle status.
 *
 * Phase 1 of the Agentic Guide.
 */
@Entity(
    tableName = "raw_captures",
    indices = [Index(value = ["status"])]
)
data class RawCapture(
    @PrimaryKey
    val sourceId: String,

    /** Display title of the capture, if any. */
    val title: String? = null,

    /** Unix epoch millis when the capture was persisted. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Unix epoch millis of the last status update. */
    val updatedAt: Long = System.currentTimeMillis(),

    /** Type of the source this capture came from. */
    val sourceType: SourceType,

    /** Raw text content for text/document/OCR/YouTube sources (null for audio). */
    val rawText: String? = null,

    /** URI/path for audio sources (null for text sources). */
    val audioUri: String? = null,

    /** Coarse lifecycle status of this capture. */
    val status: CaptureStatus = CaptureStatus.CAPTURED,

    /** Unix epoch millis when the capture was deleted (soft delete; null = active). */
    val deletedAt: Long? = null
)