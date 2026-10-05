package com.noteflowai.app.data.memory.repository

import android.content.Context
import com.noteflowai.app.data.memory.dao.CommitmentDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.Commitment
import com.noteflowai.app.data.memory.model.CommitmentStatus
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CommitmentRepository(context: Context) {

    private val db: MemoryDatabase = MemoryDatabase.getInstance(context)
    private val commitmentDao: CommitmentDao = db.commitmentDao()
    private val memoryObjectDao: MemoryObjectDao = db.memoryObjectDao()

    /**
     * Create a Commitment and its parent MemoryObject atomically in a Room transaction.
     */
    suspend fun createCommitment(
        memoryObject: MemoryObject,
        commitment: Commitment
    ) = withContext(Dispatchers.IO) {
        db.withTransaction {
            memoryObjectDao.insert(memoryObject)
            commitmentDao.insert(commitment)
        }
    }

    suspend fun getById(id: String): Commitment? = withContext(Dispatchers.IO) {
        commitmentDao.getById(id)
    }

    suspend fun getActive(): List<Commitment> = withContext(Dispatchers.IO) {
        commitmentDao.getActive()
    }

    suspend fun getDueSoon(withinMillis: Long): List<Commitment> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        commitmentDao.getDueSoon(deadline = now + withinMillis, now = now)
    }

    suspend fun getOverdue(): List<Commitment> = withContext(Dispatchers.IO) {
        commitmentDao.getOverdue()
    }

    suspend fun getCompleted(): List<Commitment> = withContext(Dispatchers.IO) {
        commitmentDao.getCompleted()
    }

    suspend fun getByStatus(status: CommitmentStatus): List<Commitment> = withContext(Dispatchers.IO) {
        commitmentDao.getByStatus(status)
    }

    suspend fun getByOwner(ownerId: String): List<Commitment> = withContext(Dispatchers.IO) {
        commitmentDao.getByOwner(ownerId)
    }

    suspend fun getByProject(projectId: String): List<Commitment> = withContext(Dispatchers.IO) {
        commitmentDao.getByProject(projectId)
    }

    suspend fun confirmCommitment(commitmentId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val commitment = commitmentDao.getById(commitmentId) ?: return@withTransaction
            commitmentDao.update(commitment.copy(
                status = CommitmentStatus.CONFIRMED,
                userConfirmed = true,
                updatedAt = System.currentTimeMillis()
            ))
            val memObj = memoryObjectDao.getById(commitment.memoryObjectId)
            if (memObj != null) {
                memoryObjectDao.update(memObj.copy(
                    status = MemoryObjectStatus.CONFIRMED,
                    confirmedAt = System.currentTimeMillis()
                ))
            }
        }
    }

    suspend fun completeCommitment(commitmentId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val commitment = commitmentDao.getById(commitmentId) ?: return@withTransaction
            commitmentDao.update(commitment.copy(
                status = CommitmentStatus.COMPLETED,
                completedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            ))
            val memObj = memoryObjectDao.getById(commitment.memoryObjectId)
            if (memObj != null) {
                memoryObjectDao.update(memObj.copy(status = MemoryObjectStatus.COMPLETED))
            }
        }
    }

    suspend fun cancelCommitment(commitmentId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val commitment = commitmentDao.getById(commitmentId) ?: return@withTransaction
            commitmentDao.update(commitment.copy(
                status = CommitmentStatus.CANCELLED,
                updatedAt = System.currentTimeMillis()
            ))
            val memObj = memoryObjectDao.getById(commitment.memoryObjectId)
            if (memObj != null) {
                memoryObjectDao.update(memObj.copy(status = MemoryObjectStatus.CANCELLED))
            }
        }
    }

    suspend fun editCommitment(commitmentId: String, action: String, dueAt: Long?) = withContext(Dispatchers.IO) {
        val commitment = commitmentDao.getById(commitmentId) ?: return@withContext
        // Same confirmation semantics as DecisionRepository.editDecision: a
        // user-edited DETECTED row becomes CONFIRMED so getActive() returns it.
        val confirmed = commitment.status == CommitmentStatus.DETECTED
        commitmentDao.update(commitment.copy(
            action = action,
            dueAt = dueAt,
            status = if (confirmed) CommitmentStatus.CONFIRMED else commitment.status,
            userConfirmed = true,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun getSourceSegments(ids: List<String>): Map<String, com.noteflowai.app.data.memory.model.SourceSegment> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) emptyMap()
        else db.sourceSegmentDao().getByIds(ids).associateBy { it.id }
    }
}
