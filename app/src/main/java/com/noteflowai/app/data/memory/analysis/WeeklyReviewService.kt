package com.noteflowai.app.data.memory.analysis

import android.content.Context
import com.noteflowai.app.data.memory.dao.ConflictDao
import com.noteflowai.app.data.memory.dao.CommitmentDao
import com.noteflowai.app.data.memory.dao.DecisionDao
import com.noteflowai.app.data.memory.dao.EntityDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.dao.MemoryReviewItemDao
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.Conflict
import com.noteflowai.app.data.memory.model.Commitment
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import com.noteflowai.app.data.memory.model.MemoryReviewItem
import com.noteflowai.app.data.memory.model.MemoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Aggregates data from existing DAOs for the weekly memory review.
 * No LLM calls -- pure data aggregation.
 *
 * DAOs are injected via the primary constructor for testability. The
 * context-based secondary constructor wires them from [MemoryDatabase]
 * so UI call sites stay unchanged.
 */
class WeeklyReviewService(
    private val decisionDao: DecisionDao,
    private val commitmentDao: CommitmentDao,
    private val memoryObjectDao: MemoryObjectDao,
    private val reviewItemDao: MemoryReviewItemDao,
    private val conflictDao: ConflictDao,
    private val entityDao: EntityDao
) {

    constructor(context: Context) : this(
        MemoryDatabase.getInstance(context).decisionDao(),
        MemoryDatabase.getInstance(context).commitmentDao(),
        MemoryDatabase.getInstance(context).memoryObjectDao(),
        MemoryDatabase.getInstance(context).memoryReviewItemDao(),
        MemoryDatabase.getInstance(context).conflictDao(),
        MemoryDatabase.getInstance(context).entityDao()
    )

    data class WeeklyReview(
        val date: Long = System.currentTimeMillis(),
        val confirmedDecisions: List<Decision>,
        val newCommitments: List<Commitment>,
        val overdueCommitments: List<Commitment>,
        val pendingReviews: List<MemoryReviewItem>,
        val openQuestions: List<MemoryObject>,
        val pendingConflicts: List<Conflict>,
        val unconfirmedEntities: Int
    )

    suspend fun generateReview(): WeeklyReview = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val sevenDaysAgo = now - 7 * 24 * 60 * 60 * 1000L

        // Confirmed decisions from last 7 days
        val allDecisions = decisionDao.getByDateRange(sevenDaysAgo, now)
        val confirmedDecisions = allDecisions.filter {
            it.status == com.noteflowai.app.data.memory.model.DecisionStatus.CONFIRMED ||
            it.status == com.noteflowai.app.data.memory.model.DecisionStatus.ACTIVE
        }

        // New commitments from last 7 days. Both live states count — the old
        // CONFIRMED-only query silently dropped ACTIVE commitments, and the
        // comment claimed "all statuses" while filtering to one.
        val recentCommitments = commitmentDao.getByStatus(
            com.noteflowai.app.data.memory.model.CommitmentStatus.CONFIRMED
        ) + commitmentDao.getByStatus(
            com.noteflowai.app.data.memory.model.CommitmentStatus.ACTIVE
        )
        val newCommitments = recentCommitments.filter { it.createdAt >= sevenDaysAgo }

        // Overdue commitments
        val overdueCommitments = commitmentDao.getOverdue(now)

        // Pending review items
        val pendingReviews = reviewItemDao.getPending()

        // Open questions (MEMORY_TYPE=QUESTION, not cancelled)
        val openQuestions = memoryObjectDao.getByTypeAndStatus(
            MemoryType.QUESTION,
            MemoryObjectStatus.DETECTED
        ) + memoryObjectDao.getByTypeAndStatus(
            MemoryType.QUESTION,
            MemoryObjectStatus.CONFIRMED
        ) + memoryObjectDao.getByTypeAndStatus(
            MemoryType.QUESTION,
            MemoryObjectStatus.ACTIVE
        )

        // Pending conflicts
        val pendingConflicts = conflictDao.getPending()

        // Unconfirmed entities count
        val unconfirmedEntities = entityDao.getUnconfirmed().size

        WeeklyReview(
            date = now,
            confirmedDecisions = confirmedDecisions,
            newCommitments = newCommitments,
            overdueCommitments = overdueCommitments,
            pendingReviews = pendingReviews,
            openQuestions = openQuestions,
            pendingConflicts = pendingConflicts,
            unconfirmedEntities = unconfirmedEntities
        )
    }
}
