package com.noteflowai.app.data.memory.repository

import android.content.Context
import com.noteflowai.app.data.memory.dao.DecisionDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.DecisionStatus
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import com.noteflowai.app.data.memory.model.MemoryType
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DecisionRepository(context: Context) {

    private val db: MemoryDatabase = MemoryDatabase.getInstance(context)
    private val decisionDao: DecisionDao = db.decisionDao()
    private val memoryObjectDao: MemoryObjectDao = db.memoryObjectDao()

    /**
     * Create a Decision and its parent MemoryObject atomically in a Room transaction.
     */
    suspend fun createDecision(
        memoryObject: MemoryObject,
        decision: Decision
    ) = withContext(Dispatchers.IO) {
        db.withTransaction {
            memoryObjectDao.insert(memoryObject)
            decisionDao.insert(decision)
        }
    }

    suspend fun getById(id: String): Decision? = withContext(Dispatchers.IO) {
        decisionDao.getById(id)
    }

    suspend fun getActive(): List<Decision> = withContext(Dispatchers.IO) {
        decisionDao.getActive()
    }

    suspend fun getByProject(projectId: String): List<Decision> = withContext(Dispatchers.IO) {
        decisionDao.getByProject(projectId)
    }

    suspend fun getByDateRange(startMs: Long, endMs: Long): List<Decision> = withContext(Dispatchers.IO) {
        decisionDao.getByDateRange(startMs, endMs)
    }

    suspend fun getByStatus(status: DecisionStatus): List<Decision> = withContext(Dispatchers.IO) {
        decisionDao.getByStatus(status)
    }

    suspend fun getTimeline(): List<Decision> = withContext(Dispatchers.IO) {
        decisionDao.getTimeline()
    }

    suspend fun getNeedingReview(): List<Decision> = withContext(Dispatchers.IO) {
        decisionDao.getNeedingReview()
    }

    suspend fun confirmDecision(decisionId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val decision = decisionDao.getById(decisionId) ?: return@withTransaction
            decisionDao.update(decision.copy(
                status = DecisionStatus.CONFIRMED,
                userConfirmed = true,
                updatedAt = System.currentTimeMillis()
            ))
            val memObj = memoryObjectDao.getById(decision.memoryObjectId)
            if (memObj != null) {
                memoryObjectDao.update(memObj.copy(
                    status = MemoryObjectStatus.CONFIRMED,
                    confirmedAt = System.currentTimeMillis()
                ))
            }
        }
    }

    suspend fun markReversed(decisionId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val decision = decisionDao.getById(decisionId) ?: return@withTransaction
            decisionDao.update(decision.copy(
                status = DecisionStatus.REVERSED,
                updatedAt = System.currentTimeMillis()
            ))
            // Keep the parent object in sync so grounded chat and the
            // status-conflict detector never see a stale CONFIRMED.
            val memObj = memoryObjectDao.getById(decision.memoryObjectId)
            if (memObj != null) {
                memoryObjectDao.update(memObj.copy(status = MemoryObjectStatus.CANCELLED))
            }
        }
    }

    suspend fun markSuperseded(decisionId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val decision = decisionDao.getById(decisionId) ?: return@withTransaction
            decisionDao.update(decision.copy(
                status = DecisionStatus.SUPERSEDED,
                updatedAt = System.currentTimeMillis()
            ))
            val memObj = memoryObjectDao.getById(decision.memoryObjectId)
            if (memObj != null) {
                memoryObjectDao.update(memObj.copy(status = MemoryObjectStatus.SUPERSEDED))
            }
        }
    }

    suspend fun scheduleReview(decisionId: String, reviewAtMillis: Long) = withContext(Dispatchers.IO) {
        val decision = decisionDao.getById(decisionId) ?: return@withContext
        decisionDao.update(decision.copy(
            reviewAt = reviewAtMillis,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun editDecision(decisionId: String, statement: String, reason: String?) = withContext(Dispatchers.IO) {
        val decision = decisionDao.getById(decisionId) ?: return@withContext
        // Editing is itself a confirmation: a DETECTED row edited by the user
        // must become CONFIRMED, otherwise getActive() (CONFIRMED/ACTIVE only)
        // never returns it and detectors scanning ACTIVE miss it. Non-DETECTED
        // statuses (REVERSED, SUPERSEDED, ...) are preserved.
        val confirmed = decision.status == DecisionStatus.DETECTED
        decisionDao.update(decision.copy(
            statement = statement,
            reason = reason,
            status = if (confirmed) DecisionStatus.CONFIRMED else decision.status,
            userConfirmed = true,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun getSourceSegments(ids: List<String>): Map<String, com.noteflowai.app.data.memory.model.SourceSegment> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) emptyMap()
        else db.sourceSegmentDao().getByIds(ids).associateBy { it.id }
    }
}
