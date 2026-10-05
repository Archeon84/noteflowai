package com.noteflowai.app.data.security

import com.noteflowai.app.data.memory.dao.*
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.repository.MemoryRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.Executor

class GranularDeletionTest {

    /**
     * Room's `withTransaction` dispatches onto `db.transactionExecutor`. A
     * relaxed mock drops the runnable and the awaiting coroutine hangs
     * forever, so every mocked-database test must bind a direct executor.
     */
    private fun stubDirectExecutors(db: MemoryDatabase) {
        val direct = Executor { it.run() }
        every { db.queryExecutor } returns direct
        every { db.transactionExecutor } returns direct
    }

    @Test
    fun `purgeDerivedMemoryData clears all derived memory tables`() = runBlocking {
        val db = mockk<MemoryDatabase>(relaxed = true)
        stubDirectExecutors(db)
        val segmentDao = mockk<SourceSegmentDao>(relaxed = true)
        val mentionDao = mockk<EntityMentionDao>(relaxed = true)
        val entityDao = mockk<EntityDao>(relaxed = true)
        val timelineDao = mockk<TimelineEntryDao>(relaxed = true)
        val memoryObjectDao = mockk<MemoryObjectDao>(relaxed = true)
        val decisionDao = mockk<DecisionDao>(relaxed = true)
        val commitmentDao = mockk<CommitmentDao>(relaxed = true)
        val relationDao = mockk<MemoryRelationDao>(relaxed = true)
        val reviewDao = mockk<MemoryReviewItemDao>(relaxed = true)
        val citationDao = mockk<AnswerCitationDao>(relaxed = true)
        val conflictDao = mockk<ConflictDao>(relaxed = true)
        val statusDao = mockk<ProcessingStatusDao>(relaxed = true)

        every { db.sourceSegmentDao() } returns segmentDao
        every { db.entityMentionDao() } returns mentionDao
        every { db.entityDao() } returns entityDao
        every { db.timelineEntryDao() } returns timelineDao
        every { db.memoryObjectDao() } returns memoryObjectDao
        every { db.decisionDao() } returns decisionDao
        every { db.commitmentDao() } returns commitmentDao
        every { db.memoryRelationDao() } returns relationDao
        every { db.memoryReviewItemDao() } returns reviewDao
        every { db.answerCitationDao() } returns citationDao
        every { db.conflictDao() } returns conflictDao
        every { db.processingStatusDao() } returns statusDao

        val memRepo = MemoryRepository(db)

        memRepo.purgeDerivedMemoryData()

        coVerify { segmentDao.clearAll() }
        coVerify { mentionDao.clearAll() }
        coVerify { entityDao.clearUnconfirmed() }
        coVerify { timelineDao.clearAll() }
        coVerify { memoryObjectDao.clearAll() }
        coVerify { decisionDao.clearAll() }
        coVerify { commitmentDao.clearAll() }
        coVerify { relationDao.clearAll() }
        coVerify { reviewDao.clearAll() }
        coVerify { citationDao.clearAll() }
        coVerify { conflictDao.clearAll() }
        coVerify { statusDao.clearAll() }
    }

    @Test
    fun `deleteDerivedDataForSource cascades across DAOs for given sourceId`() = runBlocking {
        val db = mockk<MemoryDatabase>(relaxed = true)
        stubDirectExecutors(db)
        val segmentDao = mockk<SourceSegmentDao>(relaxed = true)
        val mentionDao = mockk<EntityMentionDao>(relaxed = true)
        val timelineDao = mockk<TimelineEntryDao>(relaxed = true)
        val memoryObjectDao = mockk<MemoryObjectDao>(relaxed = true)
        val decisionDao = mockk<DecisionDao>(relaxed = true)
        val commitmentDao = mockk<CommitmentDao>(relaxed = true)
        val relationDao = mockk<MemoryRelationDao>(relaxed = true)
        val citationDao = mockk<AnswerCitationDao>(relaxed = true)
        val statusDao = mockk<ProcessingStatusDao>(relaxed = true)

        every { db.sourceSegmentDao() } returns segmentDao
        every { db.entityMentionDao() } returns mentionDao
        every { db.timelineEntryDao() } returns timelineDao
        every { db.memoryObjectDao() } returns memoryObjectDao
        every { db.decisionDao() } returns decisionDao
        every { db.commitmentDao() } returns commitmentDao
        every { db.memoryRelationDao() } returns relationDao
        every { db.answerCitationDao() } returns citationDao
        every { db.processingStatusDao() } returns statusDao

        val memRepo = MemoryRepository(db)
        memRepo.deleteDerivedDataForSource("test_note.md")

        coVerify { mentionDao.deleteBySourceId("test_note.md") }
        coVerify { decisionDao.deleteBySourceId("test_note.md") }
        coVerify { commitmentDao.deleteBySourceId("test_note.md") }
        coVerify { relationDao.deleteBySourceId("test_note.md") }
        coVerify { citationDao.deleteBySourceId("test_note.md") }
        coVerify { timelineDao.deleteBySourceId("test_note.md") }
        coVerify { memoryObjectDao.deleteBySourceId("test_note.md") }
        coVerify { statusDao.deleteBySourceId("test_note.md") }
        coVerify { segmentDao.deleteBySourceId("test_note.md") }
    }
}
