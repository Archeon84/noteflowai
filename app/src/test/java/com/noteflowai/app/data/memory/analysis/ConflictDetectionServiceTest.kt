package com.noteflowai.app.data.memory.analysis

import com.noteflowai.app.data.memory.dao.CommitmentDao
import com.noteflowai.app.data.memory.dao.DecisionDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.model.Commitment
import com.noteflowai.app.data.memory.model.CommitmentStatus
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.DecisionStatus
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.repository.ConflictRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ConflictDetectionService (personal memory layer, Phase 7).
 *
 * Mocks the DAOs + ConflictRepository. Robolectric stubs android.util.Log
 * for the persist-failure path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConflictDetectionServiceTest {

    private lateinit var decisionDao: DecisionDao
    private lateinit var commitmentDao: CommitmentDao
    private lateinit var memoryObjectDao: MemoryObjectDao
    private lateinit var conflictRepository: ConflictRepository
    private lateinit var service: ConflictDetectionService

    private val now = 1_000_000_000_000L

    private fun decision(
        id: String,
        status: DecisionStatus = DecisionStatus.ACTIVE,
        statement: String = "use postgres for the user store",
        projectEntityId: String? = "proj_1"
    ) = Decision(
        id = id,
        memoryObjectId = "mo_$id",
        statement = statement,
        status = status,
        sourceSegmentId = "seg_$id",
        decidedAt = now - 1000,
        createdAt = now - 1000,
        updatedAt = now - 1000,
        projectEntityId = projectEntityId
    )

    private fun commitment(
        id: String,
        action: String,
        status: CommitmentStatus = CommitmentStatus.ACTIVE,
        dueAt: Long? = null,
        projectEntityId: String? = null,
        createdAt: Long = now - 1000,
        updatedAt: Long = now - 1000
    ) = Commitment(
        id = id,
        memoryObjectId = "mo_$id",
        action = action,
        dueAt = dueAt,
        status = status,
        sourceSegmentId = "seg_$id",
        projectEntityId = projectEntityId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun memoryObject(
        id: String,
        statement: String,
        status: MemoryObjectStatus = MemoryObjectStatus.COMPLETED,
        type: MemoryType = MemoryType.COMMITMENT
    ) = MemoryObject(
        id = id,
        type = type,
        statement = statement,
        normalizedStatement = statement,
        status = status,
        sourceSegmentId = "seg_$id",
        sourceId = "src_$id",
        extractedAt = now - 1000
    )

    private fun stubEmpty(): Unit {
        coEvery { decisionDao.getByStatus(any()) } returns emptyList()
        coEvery { decisionDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { memoryObjectDao.getByStatuses(any()) } returns emptyList()
    }

    @Before
    fun setUp() {
        decisionDao = mockk()
        commitmentDao = mockk()
        memoryObjectDao = mockk()
        conflictRepository = mockk()
        coEvery { conflictRepository.insert(any()) } returns Unit
        coEvery { conflictRepository.pendingCountFor(any(), any()) } returns 0
        service = ConflictDetectionService(
            decisionDao,
            commitmentDao,
            memoryObjectDao,
            conflictRepository
        )
    }

    // ── Decision conflicts ────────────────────────────────────────────

    @Test
    fun `no data produces no conflicts`() = runTest {
        stubEmpty()
        val conflicts = service.detectConflicts()
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `reversed decision on same project conflicts with active`() = runTest {
        coEvery { decisionDao.getByStatus(DecisionStatus.REVERSED) } returns listOf(
            decision("rev", DecisionStatus.REVERSED, statement = "use mysql")
        )
        coEvery { decisionDao.getByStatus(DecisionStatus.SUPERSEDED) } returns emptyList()
        coEvery { decisionDao.getActive() } returns listOf(
            decision("act", DecisionStatus.ACTIVE, statement = "use postgres")
        )
        coEvery { commitmentDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { memoryObjectDao.getByStatuses(any()) } returns emptyList()

        val conflicts = service.detectConflicts()

        assertEquals(1, conflicts.size)
        val c = conflicts[0]
        assertEquals("DECISION_CONFLICT", c.conflictType)
        assertTrue(c.objectIds.contains("rev"))
        assertTrue(c.objectIds.contains("act"))
    }

    @Test
    fun `superseded decision conflicts with active`() = runTest {
        coEvery { decisionDao.getByStatus(DecisionStatus.REVERSED) } returns emptyList()
        coEvery { decisionDao.getByStatus(DecisionStatus.SUPERSEDED) } returns listOf(
            decision("sup", DecisionStatus.SUPERSEDED)
        )
        coEvery { decisionDao.getActive() } returns listOf(
            decision("act", DecisionStatus.ACTIVE)
        )
        coEvery { commitmentDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { memoryObjectDao.getByStatuses(any()) } returns emptyList()

        val conflicts = service.detectConflicts()

        assertEquals(1, conflicts.size)
        assertEquals("DECISION_CONFLICT", conflicts[0].conflictType)
    }

    @Test
    fun `unrelated decisions on different projects do not conflict`() = runTest {
        coEvery { decisionDao.getByStatus(DecisionStatus.REVERSED) } returns listOf(
            decision("rev", DecisionStatus.REVERSED, statement = "use mysql", projectEntityId = "proj_a")
        )
        coEvery { decisionDao.getByStatus(DecisionStatus.SUPERSEDED) } returns emptyList()
        coEvery { decisionDao.getActive() } returns listOf(
            decision("act", DecisionStatus.ACTIVE, statement = "use postgres", projectEntityId = "proj_b")
        )
        coEvery { commitmentDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { memoryObjectDao.getByStatuses(any()) } returns emptyList()

        val conflicts = service.detectConflicts()

        assertTrue(conflicts.isEmpty())
    }

    // ── Deadline conflicts ────────────────────────────────────────────

    @Test
    fun `commitments with different due dates on same project conflict`() = runTest {
        coEvery { decisionDao.getByStatus(any()) } returns emptyList()
        coEvery { decisionDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getActive() } returns listOf(
            commitment("c1", "ship the migration", dueAt = now + 1_000, projectEntityId = "proj_x"),
            commitment("c2", "ship the migration", dueAt = now + 9_000, projectEntityId = "proj_x")
        )
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { memoryObjectDao.getByStatuses(any()) } returns emptyList()

        val conflicts = service.detectConflicts()

        assertEquals(1, conflicts.size)
        assertEquals("DEADLINE_CONFLICT", conflicts[0].conflictType)
    }

    @Test
    fun `commitments with same due date on same project do not conflict`() = runTest {
        coEvery { decisionDao.getByStatus(any()) } returns emptyList()
        coEvery { decisionDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getActive() } returns listOf(
            commitment("c1", "ship the migration", dueAt = now + 1_000, projectEntityId = "proj_x"),
            commitment("c2", "ship the migration", dueAt = now + 1_000, projectEntityId = "proj_x")
        )
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { memoryObjectDao.getByStatuses(any()) } returns emptyList()

        val conflicts = service.detectConflicts()

        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun `overdue and active commitment with similar action conflict`() = runTest {
        coEvery { decisionDao.getByStatus(any()) } returns emptyList()
        coEvery { decisionDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getActive() } returns listOf(
            commitment("act", "prepare the quarterly report", dueAt = null)
        )
        coEvery { commitmentDao.getOverdue(any()) } returns listOf(
            commitment("overdue", "prepare the quarterly report", status = CommitmentStatus.ACTIVE, dueAt = now - 100)
        )
        coEvery { memoryObjectDao.getByStatuses(any()) } returns emptyList()

        val conflicts = service.detectConflicts()

        assertEquals(1, conflicts.size)
        assertEquals("DEADLINE_CONFLICT", conflicts[0].conflictType)
    }

    // ── Status conflicts ──────────────────────────────────────────────

    @Test
    fun `completed and active objects with similar statements conflict`() = runTest {
        coEvery { decisionDao.getByStatus(any()) } returns emptyList()
        coEvery { decisionDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery {
            memoryObjectDao.getByStatuses(listOf(MemoryObjectStatus.COMPLETED))
        } returns listOf(
            memoryObject("done", "the user store is complete", MemoryObjectStatus.COMPLETED)
        )
        coEvery {
            memoryObjectDao.getByStatuses(
                listOf(MemoryObjectStatus.CONFIRMED, MemoryObjectStatus.ACTIVE)
            )
        } returns listOf(
            memoryObject("inprog", "the user store is complete", MemoryObjectStatus.ACTIVE)
        )

        val conflicts = service.detectConflicts()

        assertEquals(1, conflicts.size)
        assertEquals("STATUS_CONFLICT", conflicts[0].conflictType)
    }

    @Test
    fun `completed and confirmed objects with similar statements conflict`() = runTest {
        coEvery { decisionDao.getByStatus(any()) } returns emptyList()
        coEvery { decisionDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery {
            memoryObjectDao.getByStatuses(listOf(MemoryObjectStatus.COMPLETED))
        } returns listOf(
            memoryObject("done", "the user store is complete", MemoryObjectStatus.COMPLETED)
        )
        coEvery {
            memoryObjectDao.getByStatuses(
                listOf(MemoryObjectStatus.CONFIRMED, MemoryObjectStatus.ACTIVE)
            )
        } returns listOf(
            memoryObject("live", "the user store is complete", MemoryObjectStatus.CONFIRMED)
        )

        val conflicts = service.detectConflicts()

        assertEquals(1, conflicts.size)
        assertEquals("STATUS_CONFLICT", conflicts[0].conflictType)
    }

    @Test
    fun `dissimilar statements do not trigger status conflict`() = runTest {
        coEvery { decisionDao.getByStatus(any()) } returns emptyList()
        coEvery { decisionDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery {
            memoryObjectDao.getByStatuses(listOf(MemoryObjectStatus.COMPLETED))
        } returns listOf(
            memoryObject("done", "the user store is complete", MemoryObjectStatus.COMPLETED)
        )
        coEvery {
            memoryObjectDao.getByStatuses(
                listOf(MemoryObjectStatus.CONFIRMED, MemoryObjectStatus.ACTIVE)
            )
        } returns listOf(
            memoryObject("inprog", "the migration is still in progress", MemoryObjectStatus.ACTIVE)
        )

        val conflicts = service.detectConflicts()

        assertTrue(conflicts.isEmpty())
    }

    // ── Persistence ───────────────────────────────────────────────────

    @Test
    fun `detected conflicts are persisted`() = runTest {
        coEvery { decisionDao.getByStatus(DecisionStatus.REVERSED) } returns listOf(
            decision("rev", DecisionStatus.REVERSED)
        )
        coEvery { decisionDao.getByStatus(DecisionStatus.SUPERSEDED) } returns emptyList()
        coEvery { decisionDao.getActive() } returns listOf(
            decision("act", DecisionStatus.ACTIVE)
        )
        coEvery { commitmentDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { memoryObjectDao.getByStatuses(any()) } returns emptyList()

        val conflicts = service.detectConflicts()

        assertEquals(1, conflicts.size)
        coVerify(exactly = 1) { conflictRepository.insert(conflicts[0]) }
    }

    @Test
    fun `no conflicts means nothing persisted`() = runTest {
        stubEmpty()
        service.detectConflicts()
        coVerify(exactly = 0) { conflictRepository.insert(any()) }
    }

    @Test
    fun `persist failure does not throw`() = runTest {
        coEvery { decisionDao.getByStatus(DecisionStatus.REVERSED) } returns listOf(
            decision("rev", DecisionStatus.REVERSED)
        )
        coEvery { decisionDao.getByStatus(DecisionStatus.SUPERSEDED) } returns emptyList()
        coEvery { decisionDao.getActive() } returns listOf(
            decision("act", DecisionStatus.ACTIVE)
        )
        coEvery { commitmentDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { memoryObjectDao.getByStatuses(any()) } returns emptyList()
        coEvery { conflictRepository.insert(any()) } throws RuntimeException("db down")

        val conflicts = service.detectConflicts()

        // Only persisted conflicts are reported; a failed insert is neither
        // returned nor thrown.
        assertEquals(0, conflicts.size)
    }

    // ── Resolution delegation ─────────────────────────────────────────

    @Test
    fun `already-pending conflict is neither re-inserted nor re-reported`() = runTest {
        coEvery { decisionDao.getByStatus(DecisionStatus.REVERSED) } returns listOf(
            decision("rev", DecisionStatus.REVERSED)
        )
        coEvery { decisionDao.getByStatus(DecisionStatus.SUPERSEDED) } returns emptyList()
        coEvery { decisionDao.getActive() } returns listOf(
            decision("act", DecisionStatus.ACTIVE)
        )
        coEvery { commitmentDao.getActive() } returns emptyList()
        coEvery { commitmentDao.getOverdue(any()) } returns emptyList()
        coEvery { memoryObjectDao.getByStatuses(any()) } returns emptyList()
        coEvery { conflictRepository.pendingCountFor(any(), any()) } returns 1

        val conflicts = service.detectConflicts()

        // Not persisted again, and not reported as newly detected either.
        assertEquals(0, conflicts.size)
        coVerify(exactly = 0) { conflictRepository.insert(any()) }
    }

    @Test
    fun `resolve dismiss and confirm delegate to repository`() = runTest {
        coEvery { conflictRepository.resolveConflict("c1", "res") } returns Unit
        coEvery { conflictRepository.dismissConflict("c2") } returns Unit
        coEvery { conflictRepository.confirmConflict("c3") } returns Unit

        service.resolveConflict("c1", "res")
        service.dismissConflict("c2")
        service.confirmConflict("c3")

        coVerify(exactly = 1) { conflictRepository.resolveConflict("c1", "res") }
        coVerify(exactly = 1) { conflictRepository.dismissConflict("c2") }
        coVerify(exactly = 1) { conflictRepository.confirmConflict("c3") }
    }
}
