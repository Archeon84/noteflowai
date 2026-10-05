package com.noteflowai.app.data.memory.repository

import android.content.Context
import com.noteflowai.app.data.memory.dao.ConflictDao
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.Conflict
import com.noteflowai.app.data.memory.model.ConflictStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConflictRepository(context: Context) {

    private val db: MemoryDatabase = MemoryDatabase.getInstance(context)
    private val dao: ConflictDao = db.conflictDao()

    suspend fun getSourceSegments(ids: List<String>): Map<String, com.noteflowai.app.data.memory.model.SourceSegment> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) emptyMap()
        else db.sourceSegmentDao().getByIds(ids).associateBy { it.id }
    }

    suspend fun insert(conflict: Conflict) = withContext(Dispatchers.IO) {
        dao.insert(conflict)
    }

    suspend fun getById(id: String): Conflict? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    suspend fun getPending(): List<Conflict> = withContext(Dispatchers.IO) {
        dao.getPending()
    }

    suspend fun pendingCountFor(type: String, objectIds: String): Int = withContext(Dispatchers.IO) {
        dao.pendingCountFor(type, objectIds)
    }

    suspend fun getByStatus(status: ConflictStatus): List<Conflict> = withContext(Dispatchers.IO) {
        dao.getByStatus(status)
    }

    suspend fun getAll(): List<Conflict> = withContext(Dispatchers.IO) {
        dao.getAll()
    }

    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        dao.pendingCount()
    }

    suspend fun confirmConflict(conflictId: String) = withContext(Dispatchers.IO) {
        val conflict = dao.getById(conflictId) ?: return@withContext
        dao.update(conflict.copy(
            status = ConflictStatus.CONFIRMED,
            resolvedAt = System.currentTimeMillis()
        ))
    }

    suspend fun dismissConflict(conflictId: String) = withContext(Dispatchers.IO) {
        val conflict = dao.getById(conflictId) ?: return@withContext
        dao.update(conflict.copy(
            status = ConflictStatus.DISMISSED,
            resolvedAt = System.currentTimeMillis()
        ))
    }

    suspend fun resolveConflict(conflictId: String, resolution: String) = withContext(Dispatchers.IO) {
        val conflict = dao.getById(conflictId) ?: return@withContext
        dao.update(conflict.copy(
            status = ConflictStatus.RESOLVED,
            resolution = resolution,
            resolvedAt = System.currentTimeMillis()
        ))
    }

    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }
}
