package com.noteflowai.app.data.memory.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The pipeline stage a source is currently in, or has completed.
 *
 * Granular, user-facing stages (Phase 3 of the Agentic Guide). Stored as the enum `.name`
 * string via [Converters], so these are the exact strings written to `processing_status`.
 * [com.noteflowai.app.data.memory.db.Converters] maps legacy pre-Phase-3 stage names
 * (EXTRACTION, EMBEDDING, INDEXING, RELATION_DETECTION) onto their granular equivalents so
 * already-persisted sources resume at the correct step rather than restarting.
 */
enum class ProcessingStage {
    /** Source segments have been created by the hook chain (before the pipeline runs). */
    SEGMENTS_CREATED,

    /** On-device audio transcription (whisper). A marker here: the real transcription
     *  happens pre-pipeline in RecordingRepository; the pipeline just records the step. */
    TRANSCRIBING,

    /** LLM extraction of entities/decisions/commitments (was EXTRACTION). */
    EXTRACTING_ENTITIES,

    /** LLM-driven timeline / relation detection (was RELATION_DETECTION). */
    EXTRACTING_TIMELINE,

    /** Rule-based conflict detection across decisions, commitments, and memory objects. */
    DETECTING_CONFLICTS,

    /** Embedding generation for semantic search (was EMBEDDING). */
    BUILDING_SEMANTIC_INDEX,

    /** Full-text index preparation for search (was INDEXING). */
    PREPARING_SEARCH,

    COMPLETE
}

/**
 * Overall processing state for a source, mirroring the ExtractionWorker lifecycle.
 */
enum class ProcessingState {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    CANCELLED,
    /**
     * A stage was disabled by feature flag / source type when the pipeline
     * reached it. Unlike [COMPLETED], a SKIPPED row stays resumable: recovery
     * re-runs the pipeline from the skipped stage, so enabling the flag later
     * actually runs the stage instead of stranding the source as falsely
     * complete.
     */
    SKIPPED
}

/**
 * Persistent per-source processing status for the ingestion pipeline (§7).
 *
 * One row per source. Survives app restart so pending/interrupted sources can
 * be re-enqueued on launch. `currentStage` is the stage being processed (or the
 * last completed stage); `status` is the overall state of the source.
 */
@Entity(
    tableName = "processing_status",
    indices = [
        Index(value = ["status"]),
        Index(value = ["sourceType"]),
        Index(value = ["updatedAt"])
    ]
)
data class ProcessingStatus(
    @PrimaryKey
    val sourceId: String,

    val sourceType: SourceType,

    val currentStage: ProcessingStage,

    val status: ProcessingState,

    val error: String? = null,

    /** Number of attempts across all stages for this source. */
    val attempts: Int = 0,

    val startedAt: Long = System.currentTimeMillis(),

    val updatedAt: Long = System.currentTimeMillis(),

    val completedAt: Long? = null
)
