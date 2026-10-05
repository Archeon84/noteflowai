package com.noteflowai.app.data.memory.rebuild

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.NoteRepository
import com.noteflowai.app.data.logging.SafeLogger
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.pipeline.SourceProcessingPipeline
import com.noteflowai.app.data.memory.repository.MemoryRepository
import com.noteflowai.app.data.memory.timeline.TimelineBackfillService
import com.noteflowai.app.data.memory.timeline.TimelineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Durable WorkManager rebuild of the whole memory layer (guide §Phase 4).
 *
 * NOTE sources only: recordings, documents, and YouTube have no content
 * loaders in this worker yet, so their derived rows are left untouched and
 * the skip is logged (see `rebuild_skips_non_note_sources`).
 *
 * Rebuilds derived data only — raw notes are the untouched source of truth. For
 * every note:
 *
 *  1. wipes all derived rows for the source via
 *     [MemoryRepository.resetSourceForRebuild] (segments, memories, decisions,
 *     commitments, relations, mentions, citations, timeline, reviews,
 *     conflicts, status) so [SourceProcessingPipeline.processSource] treats it
 *     as fresh and re-extraction cannot stack duplicates;
 *  2. runs the pipeline, which re-extracts entities + timeline + memory objects.
 *
 * The pipeline is transactional per stage and resumable per process lifecycle;
 * this worker adds the OS-scheduled wrapper (unique work, `isStopped` checks,
 * retry on interruption) exactly like [CaptureProcessingWorker]. User corrections
 * survive because:
 *
 *  - entity rows are never deleted — [EntityRepository.insertWithMention] links to
 *    the existing entity (keeping CONFIRMED/REJECTED state) and refuses to re-insert
 *    a mention over a REJECTED row or onto a REJECTED entity;
 *  - timeline rows are never deleted here — [TimelineRepository.insertFromExtraction]
 *    skips an existing (title, segment, startMs) row regardless of its confirmation,
 *    so a user-confirmed or rejected entry is not overwritten back to SUGGESTED.
 *
 * Progress is surfaced via `setProgress(done/total)`; the run finishes with an
 * optional one-time backfill for legacy sources that never had timeline extraction
 * ([TimelineBackfillService]), and the terminal report is written to the output data
 * (notes processed, timeline entries present, rejected entities/links preserved,
 * backfill counts, errors).
 */
class MemoryRebuildWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "MemoryRebuildWorker"

        // Progress keys (setProgress).
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"

        // Output-data keys (final report).
        const val KEY_NOTES_PROCESSED = "notesProcessed"
        const val KEY_ERRORS = "errors"
        const val KEY_TIMELINE_TOTAL = "timelineTotal"
        const val KEY_REJECTED_ENTITIES = "rejectedEntities"
        const val KEY_REJECTED_MENTIONS = "rejectedMentions"
        const val KEY_BACKFILL_SOURCES = "backfillSources"
        const val KEY_BACKFILL_ENTRIES = "backfillEntries"
        const val KEY_REBUILD_START_TIME = "rebuildStartTime"

        /**
         * Build the terminal WorkManager output report. Errors are joined with newlines so the
         * whole list survives as one Data field; the [MemoryRebuildScheduler]-observed WorkInfo
         * splits them back on the ViewModel side.
         */
        internal fun buildReport(
            notesProcessed: Int,
            errors: List<String>,
            timelineTotal: Int,
            rejectedEntities: Int,
            rejectedMentions: Int,
            backfillSources: Int,
            backfillEntries: Int
        ): androidx.work.Data = androidx.work.workDataOf(
            KEY_NOTES_PROCESSED to notesProcessed,
            KEY_ERRORS to errors.joinToString("\n"),
            KEY_TIMELINE_TOTAL to timelineTotal,
            KEY_REJECTED_ENTITIES to rejectedEntities,
            KEY_REJECTED_MENTIONS to rejectedMentions,
            KEY_BACKFILL_SOURCES to backfillSources,
            KEY_BACKFILL_ENTRIES to backfillEntries
        )

        /**
         * Orchestrates the rebuild over an injected dependency set, so the per-note loop,
         * interruption handling, and final report can be unit-tested without a WorkManager
         * runtime (the same pattern as [CaptureProcessingWorker.outcomeFor]).
         */
        internal suspend fun runRebuild(
            notes: List<NoteFile>,
            pipeline: SourceProcessingPipeline,
            timelineRepo: TimelineRepository,
            backfillService: TimelineBackfillService,
            db: MemoryDatabase,
            memoryRepository: MemoryRepository = MemoryRepository(db),
            isStopped: () -> Boolean = { false },
            setProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
            rebuildStartTime: Long = 0L
        ): Result {
        if (notes.isEmpty()) {
            setProgress(0, 0)
            SafeLogger.i(TAG, "no_notes_to_rebuild", emptyMap())
            return Result.success(buildReport(0, emptyList(), 0, 0, 0, 0, 0))
        }

        val errors = mutableListOf<String>()
        val total = notes.size

        notes.forEachIndexed { index, note ->
            if (isStopped()) {
                SafeLogger.d(TAG, "worker_stopped_mid_rebuild", mapOf("noteIndex" to index))
                return Result.retry()
            }
            // Report completed count so the UI reaches total/total on the last note.
            setProgress(index + 1, total)

            // Resumption: if this note already completed successfully during this rebuild session
            // (e.g. before an interruption or WorkManager retry), skip wiping and re-processing.
            val existingStatus = pipeline.getStatus(note.fileName)
            val alreadyProcessedInThisRun = rebuildStartTime > 0L &&
                existingStatus?.status == com.noteflowai.app.data.memory.model.ProcessingState.COMPLETED &&
                (existingStatus.completedAt ?: 0L) >= rebuildStartTime

            if (alreadyProcessedInThisRun) {
                SafeLogger.d(TAG, "note_already_rebuilt_in_this_run", mapOf("note" to note.fileName))
                return@forEachIndexed
            }

            try {
                // Force a fresh pipeline run: wipe all derived rows for this
                // source (a COMPLETED status row would short-circuit it, and
                // re-extraction without a wipe would stack duplicate decisions
                // and review items). Attempt history is preserved so a poison
                // source still trips MAX_ATTEMPTS instead of looping forever.
                memoryRepository.resetSourceForRebuild(note.fileName, SourceType.NOTE)
                pipeline.processSource(note.fileName, SourceType.NOTE)
            } catch (t: Throwable) {
                SafeLogger.w(
                    TAG,
                    "note_rebuild_failed",
                    mapOf("note" to note.fileName, "errorCode" to (t::class.java.simpleName), "message" to (t.message ?: ""))
                )
                errors.add(note.fileName)
            }
        }

        // The rebuild covers NOTE sources only (recordings/documents/YouTube
        // have no content loaders here yet). Non-note segments keep their
        // derived rows untouched — say so loudly instead of silently implying
        // a whole-memory rebuild.
        val nonNoteTypes = SourceType.values()
            .filter { it != SourceType.NOTE && it != SourceType.UNKNOWN }
            .associateWith { type ->
                runCatching { db.sourceSegmentDao().getBySourceType(type).size }.getOrDefault(0)
            }
            .filterValues { it > 0 }
        if (nonNoteTypes.isNotEmpty()) {
            SafeLogger.w(
                TAG,
                "rebuild_skips_non_note_sources",
                mapOf("types" to nonNoteTypes.map { "${it.key.name}:${it.value}" }.joinToString(","))
            )
        }

        // One-time backfill for legacy sources that never had timeline extraction.
        val backfill = backfillService.backfillFromExisting()

        if (isStopped()) return Result.retry()

        val report = withContext(Dispatchers.IO) {
            val rejectedEntities = db.entityDao().countRejected()
            val rejectedMentions = db.entityMentionDao().countRejected()
            val timelineTotal = db.timelineEntryDao().totalCount()
            buildReport(total, errors, timelineTotal, rejectedEntities, rejectedMentions, backfill.sourcesProcessed, backfill.entriesAdded)
        }

        SafeLogger.i(
            TAG,
            "rebuild_completed",
            mapOf(
                "notesProcessed" to total,
                "errors" to errors.size,
                "timelineTotal" to report.getLong(KEY_TIMELINE_TOTAL, 0)
            )
        )
        return Result.success(report)
    }
    }

    override suspend fun doWork(): Result {
        return try {
            val notes = loadNotes()
            val pipeline = SourceProcessingPipeline.getInstance(applicationContext)
            val timelineRepo = TimelineRepository(applicationContext)
            val backfillService = TimelineBackfillService(applicationContext)
            val db = MemoryDatabase.getInstance(applicationContext)

            val rebuildStartTime = inputData.getLong(KEY_REBUILD_START_TIME, 0L)
            runRebuild(
                notes = notes,
                pipeline = pipeline,
                timelineRepo = timelineRepo,
                backfillService = backfillService,
                db = db,
                isStopped = { isStopped },
                setProgress = { done, total -> setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total)) },
                rebuildStartTime = rebuildStartTime
            )
        } catch (t: Throwable) {
            SafeLogger.e(
                TAG,
                "worker_unhandled_failure",
                mapOf("error" to t::class.java.simpleName, "message" to (t.message ?: ""))
            )
            Result.failure(
                workDataOf(KEY_ERRORS to "${t::class.java.simpleName}: ${t.message}")
            )
        }
    }

    /**
     * Load all note file names from [NoteRepository]. [init] populates the flow
     * before [first] returns, so an empty list means a truly empty library and
     * [runRebuild] succeeds immediately with an empty report (no retry loop).
     */
    private suspend fun loadNotes(): List<NoteFile> = withContext(Dispatchers.IO) {
        val repo = NoteRepository(applicationContext)
        repo.init()
        repo.notesFlow.first()
    }
}