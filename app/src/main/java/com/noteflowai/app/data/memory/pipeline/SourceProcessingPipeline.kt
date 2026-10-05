package com.noteflowai.app.data.memory.pipeline

import android.content.Context
import android.util.Log
import com.noteflowai.app.data.ConnectivityChecker
import com.noteflowai.app.data.NoteRepository
import com.noteflowai.app.data.memory.adapter.NoteBlockAdapter
import com.noteflowai.app.data.memory.extraction.MemoryExtractionService
import com.noteflowai.app.data.memory.model.ProcessingStage
import com.noteflowai.app.data.memory.model.ProcessingState
import com.noteflowai.app.data.memory.model.ProcessingStatus
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.ProcessingStatusRepository
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The §7 ingestion pipeline: runs a source through the configured stages with
 * persistent per-stage status, so work survives restart and retries.
 *
 * Stages (in order): SEGMENTS_CREATED → TRANSCRIBING → EXTRACTING_ENTITIES →
 * EXTRACTING_TIMELINE → BUILDING_SEMANTIC_INDEX → PREPARING_SEARCH → COMPLETE. Segment creation
 * and audio transcription already happen in the hook chain / recording path, so those stages are
 * no-op markers here; the LLM stages (EXTRACTING_ENTITIES, EXTRACTING_TIMELINE) and the
 * search-index stages (BUILDING_SEMANTIC_INDEX, PREPARING_SEARCH) run the real work.
 *
 * Deduplication: [processSource] is guarded by an in-flight set, so the same
 * source is never processed twice concurrently (mirrors the old ExtractionWorker
 * job map). Stage progress is written to `processing_status` in Room.
 *
 * Gating:
 *  - EXTRACTION and RELATION_DETECTION follow `enableMemoryExtraction`.
 *  - EMBEDDING and INDEXING follow `enableSegmentIndexing`.
 *  - Disabled stages are marked SKIPPED (not complete) so restart recovery
 *    stays resumable: enabling the flag later runs the stage instead of
 *    stranding the source as falsely complete.
 */
class SourceProcessingPipeline(
    private val processingStatusRepository: ProcessingStatusRepository,
    private val sourceSegmentRepository: SourceSegmentRepository,
    private val extractionService: MemoryExtractionService,
    private val segmentEmbeddingService: SegmentEmbeddingService,
    private val segmentIndexService: SegmentIndexService,
    private val relationDetectionService: RelationDetectionService,
    private val settingsManager: SettingsManager,
    private val connectivityChecker: ConnectivityChecker,
    private val noteRepository: NoteRepository? = null,
    private val conflictDetectionService: com.noteflowai.app.data.memory.analysis.ConflictDetectionService? = null
) {

    companion object {
        private const val TAG = "SourceProcessingPipeline"

        /** Cap on total RUNNING transitions before a source is given up as
         *  permanently failed. Bounds the infinite re-enqueue loop across
         *  app restarts for a source that can never complete. */
        internal const val MAX_ATTEMPTS = 12

        /** Minimum time between recovery retries of a FAILED_RETRYABLE source.
         *  Prevents hot-looping a failing source across rapid app restarts. */
        internal const val RETRY_BACKOFF_MS = 10 * 60_000L

        /** Stages that call the LLM over HTTP and therefore must wait for a
         *  network. Offline, they fail fast instead of blocking on a timeout. */
        private val NETWORK_STAGES = setOf(ProcessingStage.EXTRACTING_ENTITIES)

        private val STAGE_ORDER = listOf(
            ProcessingStage.SEGMENTS_CREATED,
            ProcessingStage.TRANSCRIBING,
            ProcessingStage.EXTRACTING_ENTITIES,
            ProcessingStage.EXTRACTING_TIMELINE,
            ProcessingStage.DETECTING_CONFLICTS,
            ProcessingStage.BUILDING_SEMANTIC_INDEX,
            ProcessingStage.PREPARING_SEARCH,
            ProcessingStage.COMPLETE
        )

        @Volatile
        private var INSTANCE: SourceProcessingPipeline? = null

        fun getInstance(context: Context): SourceProcessingPipeline {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SourceProcessingPipeline(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    constructor(context: Context) : this(
        ProcessingStatusRepository(context),
        SourceSegmentRepository(context),
        MemoryExtractionService.getInstance(context),
        SegmentEmbeddingService.getInstance(context),
        SegmentIndexService.getInstance(context),
        RelationDetectionService.getInstance(context),
        SettingsManager.getInstance(context),
        ConnectivityChecker(context.applicationContext),
        NoteRepository(context),
        com.noteflowai.app.data.memory.analysis.ConflictDetectionService.getInstance(context)
    )

    private enum class StageResult {
        SUCCESS,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    /** Sources currently being processed. Guards against duplicate concurrent runs. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** The connectivity-resume watcher is installed once; every recoverPending
     *  call used to stack another watcher, multiplying recovery storms. */
    private val watcherInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Scope for the connectivity-resume watcher (recovery runs off the
     *  caller's coroutine when a network becomes available). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Run [sourceId] through all pending stages. No-op if the source is already
     * complete, cancelled, or currently being processed.
     */
    suspend fun processSource(sourceId: String, sourceType: SourceType) {
        if (!inFlight.add(sourceId)) {
            Log.d(TAG, "Already processing source: $sourceId")
            return
        }
        try {
            val existing = processingStatusRepository.getBySourceId(sourceId)
            if (existing?.status == ProcessingState.COMPLETED ||
                existing?.status == ProcessingState.CANCELLED
            ) {
                return
            }
            // A retryable source that has exhausted its attempts is given up so
            // it does not re-enqueue on every app restart.
            if (existing?.status == ProcessingState.FAILED_RETRYABLE &&
                existing.attempts >= MAX_ATTEMPTS
            ) {
                transition(sourceId, sourceType, existing.currentStage, ProcessingState.FAILED_PERMANENT, "Max attempts reached")
                return
            }

            var idx = existing?.let { STAGE_ORDER.indexOf(it.currentStage).coerceAtLeast(0) } ?: 0

            // Flag reads are cached for the whole pass: per-stage DataStore
            // hits multiplied I/O by stages x sources for values that cannot
            // change mid-pass.
            val extractionEnabled = settingsManager.enableMemoryExtraction.first()
            val indexingEnabled = settingsManager.enableSegmentIndexing.first()

            while (idx < STAGE_ORDER.size) {
                val stage = STAGE_ORDER[idx]
                if (stage == ProcessingStage.COMPLETE) {
                    transition(sourceId, sourceType, stage, ProcessingState.COMPLETED, completed = true)
                    return
                }
                if (!isStageEnabled(stage, sourceType, extractionEnabled, indexingEnabled)) {
                    transition(sourceId, sourceType, stage, ProcessingState.SKIPPED)
                    idx++
                    continue
                }
                when (runStage(sourceId, sourceType, stage)) {
                    StageResult.SUCCESS -> idx++
                    StageResult.RETRYABLE_FAILURE, StageResult.PERMANENT_FAILURE -> return
                }
            }
        } finally {
            inFlight.remove(sourceId)
        }
    }

    /**
     * Re-enqueue every non-COMPLETE source after app start. Stale RUNNING rows
     * (interrupted by a crash) are flipped to FAILED_RETRYABLE first.
     *
     * FAILED_RETRYABLE rows updated within [RETRY_BACKOFF_MS] are left alone
     * (they resume on a later recovery, e.g. when connectivity returns), so a
     * failing source is not hot-looped across rapid app restarts. The first
     * call also installs a connectivity watcher that re-runs recovery whenever
     * a network becomes available, so sources deferred while offline resume
     * without an app restart.
     */
    suspend fun recoverPending() {
        if (watcherInstalled.compareAndSet(false, true)) {
            connectivityChecker.startWatching { scope.launch { recoverPending() } }
        }
        val now = System.currentTimeMillis()
        val incomplete = processingStatusRepository.getIncomplete()
        if (incomplete.isEmpty()) return
        Log.i(TAG, "Recovering ${incomplete.size} pending sources")
        for (row in incomplete) {
            if (row.status == ProcessingState.RUNNING) {
                processingStatusRepository.upsert(
                    row.copy(
                        status = ProcessingState.FAILED_RETRYABLE,
                        updatedAt = now
                    )
                )
            }
            if (row.status == ProcessingState.FAILED_RETRYABLE &&
                now - row.updatedAt < RETRY_BACKOFF_MS
            ) {
                Log.d(TAG, "Deferring retry for ${row.sourceId} (backoff)")
                continue
            }
            processSource(row.sourceId, row.sourceType)
        }
    }

    /**
     * Retry a single failed stage. Resets the row to [stage] as FAILED_RETRYABLE
     * and re-enqueues the source — unless a pass is already running, in which
     * case the reset would corrupt the running pass's row (its transition()
     * overwrites currentStage) and the re-enqueue would silently no-op. The
     * running pass IS the retry; the request is logged, not dropped silently.
     */
    suspend fun retryStage(sourceId: String, stage: ProcessingStage) {
        val existing = processingStatusRepository.getBySourceId(sourceId) ?: return
        if (isProcessing(sourceId)) {
            Log.i(TAG, "Retry requested while already processing $sourceId at stage $stage — running pass continues")
            return
        }
        processingStatusRepository.upsert(
            existing.copy(
                currentStage = stage,
                status = ProcessingState.FAILED_RETRYABLE,
                error = null,
                updatedAt = System.currentTimeMillis(),
                completedAt = null
            )
        )
        processSource(sourceId, existing.sourceType)
    }

    /** True when a processing pass currently holds [sourceId]. */
    fun isProcessing(sourceId: String): Boolean = sourceId in inFlight

    /** Cancel processing for a source. The row keeps its place so the source can be resumed. */
    suspend fun cancel(sourceId: String) {
        val existing = processingStatusRepository.getBySourceId(sourceId) ?: return
        processingStatusRepository.upsert(
            existing.copy(
                status = ProcessingState.CANCELLED,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** Drop the persisted status row when a source is deleted. */
    suspend fun deleteSource(sourceId: String) {
        processingStatusRepository.deleteBySourceId(sourceId)
    }

    /** Current per-source status, for observation by the UI. */
    suspend fun getStatus(sourceId: String): ProcessingStatus? =
        processingStatusRepository.getBySourceId(sourceId)

    private fun isStageEnabled(
        stage: ProcessingStage,
        sourceType: SourceType,
        extractionEnabled: Boolean,
        indexingEnabled: Boolean
    ): Boolean = when (stage) {
        // Extraction, timeline, and conflict detection follow the memory-extraction feature flag.
        ProcessingStage.EXTRACTING_ENTITIES,
        ProcessingStage.EXTRACTING_TIMELINE -> extractionEnabled
        ProcessingStage.DETECTING_CONFLICTS -> extractionEnabled && conflictDetectionService != null
        // Embedding + indexing both follow the segment-indexing feature flag.
        ProcessingStage.BUILDING_SEMANTIC_INDEX,
        ProcessingStage.PREPARING_SEARCH -> indexingEnabled
        // "Transcribing" is meaningful only for audio sources; the transcript (and thus the
        // segments) already exist when the pipeline runs, so it is a marker for audio and a
        // no-op (skipped) for every other source type.
        ProcessingStage.TRANSCRIBING -> sourceType == SourceType.AUDIO
        else -> true
    }

    private suspend fun runStage(
        sourceId: String,
        sourceType: SourceType,
        stage: ProcessingStage
    ): StageResult {
        transition(sourceId, sourceType, stage, ProcessingState.RUNNING)
        // Fail fast when a network stage cannot reach the LLM, instead of
        // blocking on an HTTP timeout. The source stays retryable and resumes
        // when connectivity returns (see recoverPending watcher).
        if (stage in NETWORK_STAGES && !connectivityChecker.hasNetwork()) {
            transition(sourceId, sourceType, stage, ProcessingState.FAILED_RETRYABLE, "No network connectivity")
            return StageResult.RETRYABLE_FAILURE
        }
        return try {
            when (stage) {
                // Segment creation already happens in the hook chain, and real audio
                // transcription happens pre-pipeline in RecordingRepository. Both stages are
                // durable markers so the UI can surface "Transcribing" / "Preparing" progress.
                ProcessingStage.SEGMENTS_CREATED,
                ProcessingStage.TRANSCRIBING -> StageResult.SUCCESS

                ProcessingStage.EXTRACTING_ENTITIES ->
                    runExtractingEntities(sourceId, sourceType, stage)

                ProcessingStage.BUILDING_SEMANTIC_INDEX ->
                    runBuildingSemanticIndex(sourceId, sourceType, stage)

                ProcessingStage.PREPARING_SEARCH -> {
                    val ok = segmentIndexService.indexSource(sourceId)
                    if (ok) {
                        transition(sourceId, sourceType, stage, ProcessingState.COMPLETED)
                        StageResult.SUCCESS
                    } else {
                        transition(
                            sourceId, sourceType, stage,
                            ProcessingState.FAILED_RETRYABLE, "Segment indexing failed"
                        )
                        StageResult.RETRYABLE_FAILURE
                    }
                }

                ProcessingStage.EXTRACTING_TIMELINE -> {
                    relationDetectionService.detectForSource(sourceId)
                    transition(sourceId, sourceType, stage, ProcessingState.COMPLETED)
                    StageResult.SUCCESS
                }

                ProcessingStage.DETECTING_CONFLICTS -> {
                    conflictDetectionService?.detectForSource(sourceId)
                    transition(sourceId, sourceType, stage, ProcessingState.COMPLETED)
                    StageResult.SUCCESS
                }

                ProcessingStage.COMPLETE -> {
                    transition(sourceId, sourceType, stage, ProcessingState.COMPLETED, completed = true)
                    StageResult.SUCCESS
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stage $stage failed for $sourceId: ${e.message}", e)
            transition(sourceId, sourceType, stage, ProcessingState.FAILED_RETRYABLE, e.message)
            StageResult.RETRYABLE_FAILURE
        }
    }

    /**
     * EXTRACTING_ENTITIES stage body. Extracted here so we can use early `return`
     * without hitting the "return is prohibited inside a when expression branch" restriction.
     */
    private suspend fun runExtractingEntities(
        sourceId: String,
        sourceType: SourceType,
        stage: ProcessingStage
    ): StageResult {
        // One LLM pass extracts entities + memories + timeline rows;
        // EXTRACTING_TIMELINE runs the local relation pass.
        var segments = sourceSegmentRepository.getBySourceId(sourceId)

        // Self-heal: if no segments exist yet for a note, generate them on-demand.
        if (segments.isEmpty() && sourceType == SourceType.NOTE && noteRepository != null) {
            val note = noteRepository.getNoteFile(sourceId)
            if (note == null) {
                transition(sourceId, sourceType, stage, ProcessingState.FAILED_PERMANENT, "Note $sourceId not found")
                return StageResult.PERMANENT_FAILURE
            }
            if (note.content.isBlank()) {
                // Blank note: nothing to extract — complete gracefully.
                transition(sourceId, sourceType, stage, ProcessingState.COMPLETED, null)
                return StageResult.SUCCESS
            }
            val adapted = NoteBlockAdapter.adapt(note)
            if (adapted.isNotEmpty()) {
                Log.i(TAG, "Bootstrapping ${adapted.size} segments on-demand for note $sourceId")
                sourceSegmentRepository.replaceSourceSegments(sourceId, adapted)
                segments = sourceSegmentRepository.getBySourceId(sourceId)
            }
        }

        if (segments.isEmpty()) {
            // Still no segments — check for blank note via a second lookup before giving up.
            val isBlank = sourceType == SourceType.NOTE && noteRepository != null &&
                noteRepository.getNoteFile(sourceId)?.content?.isBlank() == true
            if (isBlank) {
                transition(sourceId, sourceType, stage, ProcessingState.COMPLETED, null)
                return StageResult.SUCCESS
            }
            transition(sourceId, sourceType, stage, ProcessingState.FAILED_PERMANENT, "No source segments found")
            return StageResult.PERMANENT_FAILURE
        }

        val result = extractionService.extract(segments)
        val created = result.entitiesCreated + result.decisionsCreated +
            result.commitmentsCreated + result.otherMemoryCreated + result.timelineCreated
        return if (result.errors.isEmpty() || created > 0) {
            transition(sourceId, sourceType, stage, ProcessingState.COMPLETED)
            StageResult.SUCCESS
        } else {
            transition(sourceId, sourceType, stage, ProcessingState.FAILED_RETRYABLE, result.errors.firstOrNull())
            StageResult.RETRYABLE_FAILURE
        }
    }

    /**
     * BUILDING_SEMANTIC_INDEX stage body. Extracted for the same early-return reason.
     * Best-effort: an unavailable embedder must not block indexing.
     */
    private suspend fun runBuildingSemanticIndex(
        sourceId: String,
        sourceType: SourceType,
        stage: ProcessingStage
    ): StageResult {
        val segments = sourceSegmentRepository.getBySourceId(sourceId)
        if (segments.isEmpty() && sourceType == SourceType.NOTE && noteRepository != null) {
            val note = noteRepository.getNoteFile(sourceId)
            if (note != null && note.content.isNotBlank()) {
                val adapted = NoteBlockAdapter.adapt(note)
                if (adapted.isNotEmpty()) {
                    sourceSegmentRepository.replaceSourceSegments(sourceId, adapted)
                }
            }
        }
        segmentEmbeddingService.embedSource(sourceId)
        transition(sourceId, sourceType, stage, ProcessingState.COMPLETED)
        return StageResult.SUCCESS
    }

    /**
     * Persist a status transition for [sourceId], preserving the row's history
     * (startedAt, attempts) when it already exists.
     */
    private suspend fun transition(
        sourceId: String,
        sourceType: SourceType,
        stage: ProcessingStage,
        state: ProcessingState,
        error: String? = null,
        completed: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val existing = processingStatusRepository.getBySourceId(sourceId)
        val base = existing ?: ProcessingStatus(
            sourceId = sourceId,
            sourceType = sourceType,
            currentStage = stage,
            status = state,
            startedAt = now
        )
        val attempts = if (state == ProcessingState.RUNNING) base.attempts + 1 else base.attempts
        processingStatusRepository.upsert(
            base.copy(
                sourceType = sourceType,
                currentStage = stage,
                status = state,
                error = error,
                attempts = attempts,
                updatedAt = now,
                // completedAt marks true end-to-end completion only. Stamping it
                // on every intermediate COMPLETED stage made "completed" metrics
                // measure first-stage completion instead of pipeline completion.
                completedAt = if (completed) now else base.completedAt
            )
        )
    }
}
