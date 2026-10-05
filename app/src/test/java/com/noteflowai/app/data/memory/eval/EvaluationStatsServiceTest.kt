package com.noteflowai.app.data.memory.eval

import com.noteflowai.app.data.memory.dao.AnswerCitationDao
import com.noteflowai.app.data.memory.dao.ConflictDao
import com.noteflowai.app.data.memory.dao.EntityDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.dao.MemoryRelationDao
import com.noteflowai.app.data.memory.dao.MemoryReviewItemDao
import com.noteflowai.app.data.memory.dao.ProcessingStatusDao
import com.noteflowai.app.data.memory.dao.SourceSegmentDao
import com.noteflowai.app.data.memory.model.StatusCount
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for EvaluationStatsService (personal memory layer, Phase 8d).
 *
 * Uses MockK to mock all eight DAOs. The service performs pure data
 * aggregation with no Android framework calls, so no Robolectric needed.
 */
class EvaluationStatsServiceTest {

    private lateinit var processingStatusDao: ProcessingStatusDao
    private lateinit var answerCitationDao: AnswerCitationDao
    private lateinit var reviewItemDao: MemoryReviewItemDao
    private lateinit var memoryObjectDao: MemoryObjectDao
    private lateinit var conflictDao: ConflictDao
    private lateinit var entityDao: EntityDao
    private lateinit var relationDao: MemoryRelationDao
    private lateinit var sourceSegmentDao: SourceSegmentDao
    private lateinit var service: EvaluationStatsService

    @Before
    fun setUp() {
        processingStatusDao = mockk()
        answerCitationDao = mockk()
        reviewItemDao = mockk()
        memoryObjectDao = mockk()
        conflictDao = mockk()
        entityDao = mockk()
        relationDao = mockk()
        sourceSegmentDao = mockk()
        service = EvaluationStatsService(
            processingStatusDao,
            answerCitationDao,
            reviewItemDao,
            memoryObjectDao,
            conflictDao,
            entityDao,
            relationDao,
            sourceSegmentDao
        )

        // Default empty stubs so only metrics under test need explicit values.
        coEvery { processingStatusDao.countByStatus() } returns emptyList()
        coEvery { processingStatusDao.totalCount() } returns 0
        coEvery { processingStatusDao.avgAttempts() } returns null
        coEvery { processingStatusDao.countRetried() } returns 0
        coEvery { processingStatusDao.countStuck(any()) } returns 0
        coEvery { answerCitationDao.countBySupportStatus() } returns emptyList()
        coEvery { answerCitationDao.countByLocationType() } returns emptyList()
        coEvery { answerCitationDao.totalCount() } returns 0
        coEvery { answerCitationDao.distinctAnswerCount() } returns 0
        coEvery { reviewItemDao.countByStatus() } returns emptyList()
        coEvery { memoryObjectDao.countByStatus() } returns emptyList()
        coEvery { memoryObjectDao.totalCount() } returns 0
        coEvery { conflictDao.pendingCount() } returns 0
        coEvery { entityDao.totalCount() } returns 0
        coEvery { relationDao.totalCount() } returns 0
        coEvery { sourceSegmentDao.totalCount() } returns 0
    }

    @Test
    fun `empty data produces zero rates without division by zero`() = runTest {
        val snapshot = service.buildSnapshot()

        assertEquals(0f, snapshot.pipelineCompletionRate, 0.0001f)
        assertEquals(0f, snapshot.citationValidityRate, 0.0001f)
        assertEquals(0f, snapshot.reviewConfirmationRate, 0.0001f)
        assertEquals(0, snapshot.pipelineTotalSources)
        assertEquals(0, snapshot.citationTotal)
        assertTrue(snapshot.isEmpty)
    }

    @Test
    fun `completion rate is completed divided by total`() = runTest {
        coEvery { processingStatusDao.countByStatus() } returns listOf(
            StatusCount("COMPLETED", 3),
            StatusCount("FAILED_PERMANENT", 1)
        )
        coEvery { processingStatusDao.totalCount() } returns 4

        val snapshot = service.buildSnapshot()

        assertEquals(4, snapshot.pipelineTotalSources)
        assertEquals(3, snapshot.pipelineCompleted)
        assertEquals(1, snapshot.pipelineFailedPermanent)
        assertEquals(0.75f, snapshot.pipelineCompletionRate, 0.0001f)
    }

    @Test
    fun `retried and stuck counts surface from pipeline dao`() = runTest {
        coEvery { processingStatusDao.countRetried() } returns 2
        coEvery { processingStatusDao.countStuck(any()) } returns 1

        val snapshot = service.buildSnapshot()

        assertEquals(2, snapshot.pipelineRetriedCount)
        assertEquals(1, snapshot.pipelineStuckCount)
    }

    @Test
    fun `citation validity rate counts validated over total`() = runTest {
        coEvery { answerCitationDao.countBySupportStatus() } returns listOf(
            StatusCount("VALIDATED", 6),
            StatusCount("INVALID", 2)
        )
        coEvery { answerCitationDao.totalCount() } returns 8
        coEvery { answerCitationDao.distinctAnswerCount() } returns 3
        coEvery { answerCitationDao.countByLocationType() } returns listOf(
            StatusCount("AUDIO", 5),
            StatusCount("NOTE", 3)
        )

        val snapshot = service.buildSnapshot()

        assertEquals(8, snapshot.citationTotal)
        assertEquals(6, snapshot.citationValidated)
        assertEquals(2, snapshot.citationInvalid)
        assertEquals(0.75f, snapshot.citationValidityRate, 0.0001f)
        assertEquals(3, snapshot.citationDistinctAnswers)
        assertEquals(5, snapshot.citationByLocationType["AUDIO"])
        assertEquals(3, snapshot.citationByLocationType["NOTE"])
    }

    @Test
    fun `confirmation rate is accepted plus edited over all review items`() = runTest {
        coEvery { reviewItemDao.countByStatus() } returns listOf(
            StatusCount("PENDING", 4),
            StatusCount("ACCEPTED", 3),
            StatusCount("EDITED", 1),
            StatusCount("IGNORED", 2)
        )

        val snapshot = service.buildSnapshot()

        assertEquals(4, snapshot.reviewPending)
        assertEquals(3, snapshot.reviewAccepted)
        assertEquals(1, snapshot.reviewEdited)
        assertEquals(2, snapshot.reviewIgnored)
        // Overall rate: pending items stay in the denominator (4+3+1+2 = 10),
        // so unreviewed work cannot inflate the metric. Accepted+edited = 4.
        assertEquals(4f / 10f, snapshot.reviewConfirmationRate, 0.0001f)
    }

    @Test
    fun `memory confirmed count is total minus detected`() = runTest {
        coEvery { memoryObjectDao.countByStatus() } returns listOf(
            StatusCount("DETECTED", 7),
            StatusCount("CONFIRMED", 2),
            StatusCount("ACTIVE", 1)
        )
        coEvery { memoryObjectDao.totalCount() } returns 10

        val snapshot = service.buildSnapshot()

        assertEquals(10, snapshot.memoryObjectTotal)
        assertEquals(7, snapshot.memoryObjectDetected)
        assertEquals(3, snapshot.memoryObjectConfirmedActive)
    }

    @Test
    fun `system volume counts surface`() = runTest {
        coEvery { entityDao.totalCount() } returns 12
        coEvery { relationDao.totalCount() } returns 4
        coEvery { sourceSegmentDao.totalCount() } returns 40
        coEvery { conflictDao.pendingCount() } returns 2

        val snapshot = service.buildSnapshot()

        assertEquals(12, snapshot.entityCount)
        assertEquals(4, snapshot.relationCount)
        assertEquals(40, snapshot.segmentCount)
        assertEquals(2, snapshot.pendingConflicts)
    }
}
