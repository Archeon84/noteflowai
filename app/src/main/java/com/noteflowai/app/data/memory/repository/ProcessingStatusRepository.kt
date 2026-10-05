package com.noteflowai.app.data.memory.repository

import android.content.Context
import com.noteflowai.app.data.memory.dao.ProcessingStatusDao
import com.noteflowai.app.data.memory.db.MemoryDatabaseModule
import com.noteflowai.app.data.memory.model.ProcessingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * Thin coroutine wrapper over [ProcessingStatusDao], matching the pattern of
 * [SourceSegmentRepository]. All database operations run on the IO dispatcher.
 */
class ProcessingStatusRepository(context: Context) {

    private val dao: ProcessingStatusDao = MemoryDatabaseModule.provideProcessingStatusDao(context)

    suspend fun upsert(status: ProcessingStatus) = withContext(Dispatchers.IO) {
        dao.upsert(status)
    }

    suspend fun getBySourceId(sourceId: String): ProcessingStatus? = withContext(Dispatchers.IO) {
        dao.getBySourceId(sourceId)
    }

    suspend fun getIncomplete(): List<ProcessingStatus> = withContext(Dispatchers.IO) {
        dao.getIncomplete()
    }

    /**
     * Reactive observation of every processing-status row. Distinct until
     * changed: equivalent consecutive emissions (e.g. an unrelated table
     * write waking the observer) no longer fan out to every collector.
     */
    fun observeAll(): Flow<List<ProcessingStatus>> = dao.observeAll().distinctUntilChanged()

    suspend fun deleteBySourceId(sourceId: String) = withContext(Dispatchers.IO) {
        dao.deleteBySourceId(sourceId)
    }

    suspend fun resetPermanentFailuresByReason(reasonSubstring: String) = withContext(Dispatchers.IO) {
        dao.resetPermanentFailuresByReason(reasonSubstring)
    }
}
