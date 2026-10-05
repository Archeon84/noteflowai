package com.noteflowai.app.data.memory.rebuild

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.pipeline.SourceProcessingPipeline
import com.noteflowai.app.data.memory.repository.MemoryRepository
import com.noteflowai.app.data.memory.timeline.TimelineBackfillService
import com.noteflowai.app.data.memory.timeline.TimelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rebuild orchestration for guide §Phase 4, tested on the extracted [MemoryRebuildWorker.runRebuild]
 * with injected fakes (the same isolated-decision pattern as [CaptureProcessingWorkerTest]):
 *
 *  - the worker runs one pipeline pass per note (delete stale status, then process);
 *  - a stopped worker returns `Result.retry()` so WorkManager re-runs it later (survives
 *    process death / interruption);
 *  - the terminal report carries the per-note counts plus the preserved rejected state read
 *    from the database.
 *
 * The skip-rejected re-extraction guarantee itself lives in [EntityRepository], covered by
 * EntityRepositoryCorrectionTest; here the report accurately reflects rejected counts after a
 * rebuild that touched the DB through the seams.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryRebuildWorkerTest {

    private lateinit var db: MemoryDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun note(fileName: String) = NoteFile(
        fileName = fileName,
        lastModified = "2026-01-01",
        preview = "preview"
    )

    private fun buildFakes(pipeline: SourceProcessingPipeline) = Triple(
        pipeline,
        mockk<TimelineRepository>(relaxed = true),
        mockk<TimelineBackfillService>(relaxed = true)
    )

    private fun backfillResult(sources: Int, entries: Int) =
        TimelineBackfillService.BackfillResult(sources, entries)

    @Test
    fun `runs one pipeline pass per note`() = runTest {
        val pipeline = mockk<SourceProcessingPipeline>(relaxed = true)
        val memoryRepository = mockk<MemoryRepository>(relaxed = true)
        val (p, timelineRepo, backfill) = buildFakes(pipeline)
        coEvery { backfill.backfillFromExisting() } returns backfillResult(0, 0)

        MemoryRebuildWorker.runRebuild(
            notes = listOf(note("a.md"), note("b.md")),
            pipeline = p,
            timelineRepo = timelineRepo,
            backfillService = backfill,
            db = db,
            memoryRepository = memoryRepository
        )

        coVerify(exactly = 1) { memoryRepository.resetSourceForRebuild("a.md", SourceType.NOTE) }
        coVerify(exactly = 1) { pipeline.processSource("a.md", SourceType.NOTE) }
        coVerify(exactly = 1) { memoryRepository.resetSourceForRebuild("b.md", SourceType.NOTE) }
        coVerify(exactly = 1) { pipeline.processSource("b.md", SourceType.NOTE) }
    }

    @Test
    fun `stopped before work returns retry`() = runTest {
        val pipeline = mockk<SourceProcessingPipeline>(relaxed = true)
        val (p, timelineRepo, backfill) = buildFakes(pipeline)

        val result = MemoryRebuildWorker.runRebuild(
            notes = listOf(note("a.md")),
            pipeline = p,
            timelineRepo = timelineRepo,
            backfillService = backfill,
            db = db,
            isStopped = { true }
        )

        assertTrue(result is ListenableWorker.Result.Retry)
        coVerify(exactly = 0) { pipeline.processSource(any(), any()) }
    }

    @Test
    fun `stopped mid loop returns retry before next note`() = runTest {
        val pipeline = mockk<SourceProcessingPipeline>(relaxed = true)
        val (p, timelineRepo, backfill) = buildFakes(pipeline)
        var stopped = false
        coEvery { pipeline.processSource("a.md", SourceType.NOTE) } answers {
            stopped = true
        }

        val result = MemoryRebuildWorker.runRebuild(
            notes = listOf(note("a.md"), note("b.md")),
            pipeline = p,
            timelineRepo = timelineRepo,
            backfillService = backfill,
            db = db,
            isStopped = { stopped }
        )

        // b.md is never touched once the worker is stopped.
        assertTrue(result is ListenableWorker.Result.Retry)
        coVerify(exactly = 1) { pipeline.processSource("a.md", SourceType.NOTE) }
        coVerify(exactly = 0) { pipeline.processSource("b.md", SourceType.NOTE) }
    }

    @Test
    fun `empty notes produce a successful zeroed report`() = runTest {
        val pipeline = mockk<SourceProcessingPipeline>(relaxed = true)
        val (p, timelineRepo, backfill) = buildFakes(pipeline)

        val result = MemoryRebuildWorker.runRebuild(
            notes = emptyList(),
            pipeline = p,
            timelineRepo = timelineRepo,
            backfillService = backfill,
            db = db
        )

        assertTrue(result is ListenableWorker.Result.Success)
        val report = (result as ListenableWorker.Result.Success).outputData
        assertEquals(0, report.getInt(MemoryRebuildWorker.KEY_NOTES_PROCESSED, -1))
        assertEquals(0, report.getInt(MemoryRebuildWorker.KEY_TIMELINE_TOTAL, -1))
        assertEquals("", report.getString(MemoryRebuildWorker.KEY_ERRORS))
    }

    @Test
    fun `report counts persist notes timeline and rejected state`() = runTest {
        val pipeline = mockk<SourceProcessingPipeline>(relaxed = true)
        val (p, timelineRepo, backfill) = buildFakes(pipeline)
        coEvery { backfill.backfillFromExisting() } returns backfillResult(2, 5)
        // Seed rejected rows that the rebuild must preserve; processed notes count is 2.
        db.entityDao().insert(
            com.noteflowai.app.data.memory.model.Entity(
                id = "e_r",
                type = com.noteflowai.app.data.memory.model.EntityType.PERSON,
                canonicalName = "Rejected",
                normalizedName = "rejected",
                confirmation = com.noteflowai.app.data.memory.model.ConfirmationState.REJECTED
            )
        )

        val result = MemoryRebuildWorker.runRebuild(
            notes = listOf(note("a.md"), note("b.md")),
            pipeline = p,
            timelineRepo = timelineRepo,
            backfillService = backfill,
            db = db
        )

        val report = (result as ListenableWorker.Result.Success).outputData
        assertEquals(2, report.getInt(MemoryRebuildWorker.KEY_NOTES_PROCESSED, -1))
        assertEquals(1, report.getInt(MemoryRebuildWorker.KEY_REJECTED_ENTITIES, -1))
        assertEquals(0, report.getInt(MemoryRebuildWorker.KEY_REJECTED_MENTIONS, -1))
        assertEquals(2, report.getInt(MemoryRebuildWorker.KEY_BACKFILL_SOURCES, -1))
        assertEquals(5, report.getInt(MemoryRebuildWorker.KEY_BACKFILL_ENTRIES, -1))
        assertEquals("", report.getString(MemoryRebuildWorker.KEY_ERRORS))
    }

    @Test
    fun `failing note is collected into the errors report`() = runTest {
        val pipeline = mockk<SourceProcessingPipeline>(relaxed = true)
        val (p, timelineRepo, backfill) = buildFakes(pipeline)
        coEvery { pipeline.processSource("broken.md", SourceType.NOTE) } throws RuntimeException("boom")
        coEvery { backfill.backfillFromExisting() } returns backfillResult(0, 0)

        val result = MemoryRebuildWorker.runRebuild(
            notes = listOf(note("ok.md"), note("broken.md")),
            pipeline = p,
            timelineRepo = timelineRepo,
            backfillService = backfill,
            db = db
        )

        val report = (result as ListenableWorker.Result.Success).outputData
        assertEquals(2, report.getInt(MemoryRebuildWorker.KEY_NOTES_PROCESSED, -1))
        assertEquals("broken.md", report.getString(MemoryRebuildWorker.KEY_ERRORS))
    }

    @Test
    fun `buildReport joins multiple errors with newlines`() {
        val report = MemoryRebuildWorker.buildReport(
            notesProcessed = 3,
            errors = listOf("a.md", "b.md"),
            timelineTotal = 5,
            rejectedEntities = 1,
            rejectedMentions = 2,
            backfillSources = 1,
            backfillEntries = 3
        )

        assertEquals("a.md\nb.md", report.getString(MemoryRebuildWorker.KEY_ERRORS))
        assertEquals(3, report.getInt(MemoryRebuildWorker.KEY_NOTES_PROCESSED, -1))
        assertEquals(5, report.getInt(MemoryRebuildWorker.KEY_TIMELINE_TOTAL, -1))
        assertEquals(1, report.getInt(MemoryRebuildWorker.KEY_REJECTED_ENTITIES, -1))
        assertEquals(2, report.getInt(MemoryRebuildWorker.KEY_REJECTED_MENTIONS, -1))
        assertEquals(1, report.getInt(MemoryRebuildWorker.KEY_BACKFILL_SOURCES, -1))
        assertEquals(3, report.getInt(MemoryRebuildWorker.KEY_BACKFILL_ENTRIES, -1))
    }

    @Test
    fun `resumed rebuild skips notes already completed since rebuildStartTime`() = runTest {
        val pipeline = mockk<SourceProcessingPipeline>(relaxed = true)
        val memoryRepository = mockk<MemoryRepository>(relaxed = true)
        val (p, timelineRepo, backfill) = buildFakes(pipeline)
        val startTime = 10000L

        // a.md was already completed at 15000L (> startTime)
        coEvery { p.getStatus("a.md") } returns com.noteflowai.app.data.memory.model.ProcessingStatus(
            sourceId = "a.md",
            sourceType = SourceType.NOTE,
            currentStage = com.noteflowai.app.data.memory.model.ProcessingStage.COMPLETE,
            status = com.noteflowai.app.data.memory.model.ProcessingState.COMPLETED,
            completedAt = 15000L
        )
        // b.md has not been completed since startTime
        coEvery { p.getStatus("b.md") } returns null
        coEvery { backfill.backfillFromExisting() } returns backfillResult(0, 0)

        val result = MemoryRebuildWorker.runRebuild(
            notes = listOf(note("a.md"), note("b.md")),
            pipeline = p,
            timelineRepo = timelineRepo,
            backfillService = backfill,
            db = db,
            memoryRepository = memoryRepository,
            rebuildStartTime = startTime
        )

        assertTrue(result is ListenableWorker.Result.Success)
        // a.md is skipped because it completed after rebuildStartTime
        coVerify(exactly = 0) { memoryRepository.resetSourceForRebuild("a.md", SourceType.NOTE) }
        coVerify(exactly = 0) { p.processSource("a.md", SourceType.NOTE) }
        // b.md is processed normally
        coVerify(exactly = 1) { memoryRepository.resetSourceForRebuild("b.md", SourceType.NOTE) }
        coVerify(exactly = 1) { p.processSource("b.md", SourceType.NOTE) }
    }
}