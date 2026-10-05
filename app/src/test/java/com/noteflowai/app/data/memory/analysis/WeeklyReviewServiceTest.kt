package com.noteflowai.app.data.memory.analysis

import com.noteflowai.app.data.memory.dao.CommitmentDao
import com.noteflowai.app.data.memory.dao.ConflictDao
import com.noteflowai.app.data.memory.dao.DecisionDao
import com.noteflowai.app.data.memory.dao.EntityDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.dao.MemoryReviewItemDao
import com.noteflowai.app.data.memory.model.Commitment
import com.noteflowai.app.data.memory.model.CommitmentStatus
import com.noteflowai.app.data.memory.model.Conflict
import com.noteflowai.app.data.memory.model.ConflictStatus
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.DecisionStatus
import com.noteflowai.app.data.memory.model.Entity
import com.noteflowai.app.data.memory.model.EntityType
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import com.noteflowai.app.data.memory.model.MemoryReviewItem
import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.model.ReviewItemStatus
import com.noteflowai.app.data.memory.model.ReviewItemType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for WeeklyReviewService (personal memory layer, Phase 7).
 *
 * Uses MockK to mock all six DAOs. The service performs pure data
 * aggregation with no Android framework calls, so no Robolectric needed.
 */
class WeeklyReviewServiceTest {

    private lateinit var decisionDao: DecisionDao
    private lateinit var commitmentDao: CommitmentDao
    private lateinit var memoryObjectDao: MemoryObjectDao
    private lateinit var reviewItemDao: MemoryReviewItemDao
    private lateinit var conflictDao: ConflictDao
    private lateinit var entityDao: EntityDao
    private lateinit var service: WeeklyReviewService

    private val now = 1_000_000_000_000L
    private val sevenDays = 7 * 24 * 60 * 60 * 1000L

    private fun decision(
        id: String,
        status: DecisionStatus = DecisionStatus.CONFIRMED,
        decidedAt: Long = now - 1000
    ) = Decision(
        id = id,
        memoryObjectId = "mo_$id",
        statement = "decision $id",
        status = status,
        sourceSegmentId = "seg_$id",
        decidedAt = decidedAt,
        createdAt = now - 1000,
        updatedAt = now - 1000
    )

    private fun commitment(
        id: String,
        status: CommitmentStatus = CommitmentStatus.CONFIRMED,
        dueAt: Long? = null,
        createdAt: Long = now - 1000
    ) = Commitment(
        id = id,
        memoryObjectId = "mo_$id",
        action = "action $id",
        dueAt = dueAt,
        status = status,
        sourceSegmentId = "seg_$id",
        createdAt = createdAt,
        updatedAt = createdAt
    )

    private fun memoryObject(
        id: String,
        type: MemoryType = MemoryType.QUESTION,
        status: MemoryObjectStatus = MemoryObjectStatus.CONFIRMED
    ) = MemoryObject(
        id = id,
        type = type,
        statement = "statement $id",
        normalizedStatement = "statement $id",
        status = status,
        sourceSegmentId = "seg_$id",
        sourceId = "src_$id",
        extractedAt = now - 1000
    )

    private fun reviewItem(id: String) = MemoryReviewItem(
        id = id,
        type = ReviewItemType.ENTITY,
        referencedObjectId = "obj_$id",
        reason = "needs review",
        status = ReviewItemStatus.PENDING,
        createdAt = now - 1000
    )

    private fun conflict(id: String) = Conflict(
        id = id,
        conflictType = "DECISION_CONFLICT",
        objectIds = "[\"a\",\"b\"]",
        sourceSegmentIds = "[\"s1\",\"s2\"]",
        firstObservedAt = now - 1000,
        latestObservedAt = now - 500,
        confidence = 0.7f,
        status = ConflictStatus.PENDING,
        createdAt = now - 1000
    )

    private fun entity(id: String) = Entity(
        id = id,
        type = EntityType.PERSON,
        canonicalName = "entity $id",
        normalizedName = "entity $id",
        confidence = 0.9f,
        userConfirmed = false
    )

    @Before
    fun setUp() {
        decisionDao = mockk()
        commitmentDao = mockk()
        memoryObjectDao = mockk()
        reviewItemDao = mockk()
        conflictDao = mockk()
        entityDao = mockk()
        service = WeeklyReviewService(
            decisionDao,
            commitmentDao,
            memoryObjectDao,
            reviewItemDao,
            conflictDao,
            entityDao
        )
    }

    @Test
    fun `empty database yields empty review`() = runTest {
        coEvery { decisionDao.getByDateRange(any(), any()) } returns emptyList()
        coEvery { commitmentDao.getByStatus(any()) } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { reviewItemDao.getPending() } returns emptyList()
        coEvery { memoryObjectDao.getByTypeAndStatus(any(), any()) } returns emptyList()
        coEvery { conflictDao.getPending() } returns emptyList()
        coEvery { entityDao.getUnconfirmed() } returns emptyList()

        val review = service.generateReview()

        assertTrue(review.confirmedDecisions.isEmpty())
        assertTrue(review.newCommitments.isEmpty())
        assertTrue(review.overdueCommitments.isEmpty())
        assertTrue(review.pendingReviews.isEmpty())
        assertTrue(review.openQuestions.isEmpty())
        assertTrue(review.pendingConflicts.isEmpty())
        assertEquals(0, review.unconfirmedEntities)
    }

    @Test
    fun `only confirmed or active decisions are included`() = runTest {
        coEvery { decisionDao.getByDateRange(any(), any()) } returns listOf(
            decision("confirmed", DecisionStatus.CONFIRMED),
            decision("active", DecisionStatus.ACTIVE),
            decision("detected", DecisionStatus.DETECTED),
            decision("reversed", DecisionStatus.REVERSED)
        )
        coEvery { commitmentDao.getByStatus(any()) } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { reviewItemDao.getPending() } returns emptyList()
        coEvery { memoryObjectDao.getByTypeAndStatus(any(), any()) } returns emptyList()
        coEvery { conflictDao.getPending() } returns emptyList()
        coEvery { entityDao.getUnconfirmed() } returns emptyList()

        val review = service.generateReview()

        val ids = review.confirmedDecisions.map { it.id }
        assertEquals(2, ids.size)
        assertTrue(ids.containsAll(listOf("confirmed", "active")))
    }

    @Test
    fun `new commitments are filtered to last seven days`() = runTest {
        // Service filters against real System.currentTimeMillis(), so fixtures
        // must be anchored to real time, not the fake `now` constant.
        val recent = commitment("recent", createdAt = System.currentTimeMillis() - 1000)
        val old = commitment("old", createdAt = System.currentTimeMillis() - 20 * 24 * 60 * 60 * 1000L)
        coEvery { decisionDao.getByDateRange(any(), any()) } returns emptyList()
        coEvery { commitmentDao.getByStatus(CommitmentStatus.CONFIRMED) } returns listOf(recent, old)
        coEvery { commitmentDao.getByStatus(CommitmentStatus.ACTIVE) } returns listOf(
            commitment("recent_active", createdAt = System.currentTimeMillis() - 1000)
        )
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { reviewItemDao.getPending() } returns emptyList()
        coEvery { memoryObjectDao.getByTypeAndStatus(any(), any()) } returns emptyList()
        coEvery { conflictDao.getPending() } returns emptyList()
        coEvery { entityDao.getUnconfirmed() } returns emptyList()

        val review = service.generateReview()

        // Both live states count; only the 20-day-old row is excluded.
        assertEquals(listOf("recent", "recent_active"), review.newCommitments.map { it.id })
    }

    @Test
    fun `overdue commitments are reported separately`() = runTest {
        coEvery { decisionDao.getByDateRange(any(), any()) } returns emptyList()
        coEvery { commitmentDao.getByStatus(any()) } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns listOf(
            commitment("overdue_1", dueAt = now - 100),
            commitment("overdue_2", dueAt = now - 200)
        )
        coEvery { reviewItemDao.getPending() } returns emptyList()
        coEvery { memoryObjectDao.getByTypeAndStatus(any(), any()) } returns emptyList()
        coEvery { conflictDao.getPending() } returns emptyList()
        coEvery { entityDao.getUnconfirmed() } returns emptyList()

        val review = service.generateReview()

        assertEquals(2, review.overdueCommitments.size)
    }

    @Test
    fun `open questions aggregate across detected confirmed and active`() = runTest {
        coEvery { decisionDao.getByDateRange(any(), any()) } returns emptyList()
        coEvery { commitmentDao.getByStatus(any()) } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { reviewItemDao.getPending() } returns emptyList()
        coEvery {
            memoryObjectDao.getByTypeAndStatus(MemoryType.QUESTION, MemoryObjectStatus.DETECTED)
        } returns listOf(memoryObject("q1", MemoryType.QUESTION, MemoryObjectStatus.DETECTED))
        coEvery {
            memoryObjectDao.getByTypeAndStatus(MemoryType.QUESTION, MemoryObjectStatus.CONFIRMED)
        } returns listOf(memoryObject("q2", MemoryType.QUESTION, MemoryObjectStatus.CONFIRMED))
        coEvery {
            memoryObjectDao.getByTypeAndStatus(MemoryType.QUESTION, MemoryObjectStatus.ACTIVE)
        } returns listOf(memoryObject("q3", MemoryType.QUESTION, MemoryObjectStatus.ACTIVE))
        coEvery { conflictDao.getPending() } returns emptyList()
        coEvery { entityDao.getUnconfirmed() } returns emptyList()

        val review = service.generateReview()

        assertEquals(3, review.openQuestions.size)
    }

    @Test
    fun `review carries pending conflicts and unconfirmed entity count`() = runTest {
        coEvery { decisionDao.getByDateRange(any(), any()) } returns emptyList()
        coEvery { commitmentDao.getByStatus(any()) } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { reviewItemDao.getPending() } returns listOf(reviewItem("r1"), reviewItem("r2"))
        coEvery { memoryObjectDao.getByTypeAndStatus(any(), any()) } returns emptyList()
        coEvery { conflictDao.getPending() } returns listOf(conflict("c1"))
        coEvery { entityDao.getUnconfirmed() } returns listOf(entity("e1"), entity("e2"), entity("e3"))

        val review = service.generateReview()

        assertEquals(2, review.pendingReviews.size)
        assertEquals(1, review.pendingConflicts.size)
        assertEquals(3, review.unconfirmedEntities)
    }

    @Test
    fun `review date is populated`() = runTest {
        coEvery { decisionDao.getByDateRange(any(), any()) } returns emptyList()
        coEvery { commitmentDao.getByStatus(any()) } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { reviewItemDao.getPending() } returns emptyList()
        coEvery { memoryObjectDao.getByTypeAndStatus(any(), any()) } returns emptyList()
        coEvery { conflictDao.getPending() } returns emptyList()
        coEvery { entityDao.getUnconfirmed() } returns emptyList()

        val review = service.generateReview()

        assertTrue(review.date > 0)
        coVerify { decisionDao.getByDateRange(any(), any()) }
        coVerify { entityDao.getUnconfirmed() }
    }
}
