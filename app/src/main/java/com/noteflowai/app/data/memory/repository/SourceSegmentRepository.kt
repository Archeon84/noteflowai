package com.noteflowai.app.data.memory.repository

import android.content.Context
import com.noteflowai.app.data.memory.dao.SourceSegmentDao
import com.noteflowai.app.data.memory.dao.escapeLike
import com.noteflowai.app.data.memory.db.MemoryDatabaseModule
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for managing SourceSegments in the Personal Memory Layer.
 *
 * Provides a coroutine-based API over the Room DAO, ensuring all database
 * operations run on the IO dispatcher.
 */
class SourceSegmentRepository(private val context: Context) {

    private val dao: SourceSegmentDao = MemoryDatabaseModule.provideSourceSegmentDao(context)

    /**
     * Phase 1 integrity: delete+insert in one Room transaction so a crash can
     * never strand a source with zero segments (recovery re-runs the pipeline
     * from the persisted stage — it never recreates hook-built segments, so a
     * torn delete would fail permanently on an intact source file).
     */
    suspend fun replaceSourceSegments(sourceId: String, segments: List<SourceSegment>) =
        withContext(Dispatchers.IO) {
            MemoryDatabaseModule.getDatabase(context).withTransaction {
                dao.deleteBySourceId(sourceId)
                if (segments.isNotEmpty()) dao.insertAll(segments)
            }
        }

    // -- Insert --

    suspend fun insert(segment: SourceSegment) = withContext(Dispatchers.IO) {
        dao.insert(segment)
    }

    suspend fun insertAll(segments: List<SourceSegment>) = withContext(Dispatchers.IO) {
        dao.insertAll(segments)
    }

    // -- Update --

    suspend fun update(segment: SourceSegment) = withContext(Dispatchers.IO) {
        dao.update(segment)
    }

    // -- Get --

    suspend fun getById(id: String): SourceSegment? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    /**
     * Batch lookup: fetch all segments whose ids are in [ids] in a single query.
     * Avoids the N+1 pattern of calling [getById] per id in a loop.
     */
    suspend fun getByIds(ids: List<String>): List<SourceSegment> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) emptyList() else dao.getByIds(ids)
    }

    suspend fun getBySourceId(sourceId: String): List<SourceSegment> = withContext(Dispatchers.IO) {
        dao.getBySourceId(sourceId)
    }

    suspend fun getBySourceType(sourceType: SourceType): List<SourceSegment> = withContext(Dispatchers.IO) {
        dao.getBySourceType(sourceType)
    }

    suspend fun getNeighbors(sourceId: String, excludeSegmentId: String): List<SourceSegment> = withContext(Dispatchers.IO) {
        dao.getNeighbors(sourceId, excludeSegmentId)
    }

    suspend fun getInTimeRange(sourceId: String, startMs: Long, endMs: Long): List<SourceSegment> = withContext(Dispatchers.IO) {
        dao.getInTimeRange(sourceId, startMs, endMs)
    }

    suspend fun getOnPage(sourceId: String, pageNumber: Int): List<SourceSegment> = withContext(Dispatchers.IO) {
        dao.getOnPage(sourceId, pageNumber)
    }

    // -- Search --

    suspend fun searchBySource(sourceId: String, query: String): List<SourceSegment> = withContext(Dispatchers.IO) {
        // normalizedText is indexed case-folded: fold the query the same way
        // (ASCII LIKE is case-insensitive; folding additionally fixes
        // non-ASCII case mismatches against the folded column).
        dao.searchBySource(sourceId, escapeLike(query.lowercase(java.util.Locale.ROOT)))
    }

    suspend fun searchAll(query: String, limit: Int = 50): List<SourceSegment> = withContext(Dispatchers.IO) {
        dao.searchAll(escapeLike(query.lowercase(java.util.Locale.ROOT)), limit)
    }

    // -- Delete --

    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    /**
     * Delete all SourceSegments for a given source.
     * This is the cascade deletion entry point — when a note, recording, or document
     * is deleted, call this to remove all its source segments.
     */
    suspend fun deleteBySourceId(sourceId: String) = withContext(Dispatchers.IO) {
        dao.deleteBySourceId(sourceId)
    }

    // -- Counts --

    suspend fun countBySourceId(sourceId: String): Int = withContext(Dispatchers.IO) {
        dao.countBySourceId(sourceId)
    }

    suspend fun totalCount(): Int = withContext(Dispatchers.IO) {
        dao.totalCount()
    }
}
