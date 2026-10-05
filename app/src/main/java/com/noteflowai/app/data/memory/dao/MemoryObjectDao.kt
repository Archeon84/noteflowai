package com.noteflowai.app.data.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.model.StatusCount

@Dao
interface MemoryObjectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memoryObject: MemoryObject)

    @Update
    suspend fun update(memoryObject: MemoryObject)

    @Query("SELECT * FROM memory_objects")
    suspend fun getAll(): List<MemoryObject>

    @Query("SELECT * FROM memory_objects WHERE id = :id")
    suspend fun getById(id: String): MemoryObject?

    @Query("SELECT * FROM memory_objects WHERE sourceSegmentId = :sourceSegmentId")
    suspend fun getBySourceSegmentId(sourceSegmentId: String): List<MemoryObject>

    @Query("SELECT * FROM memory_objects WHERE sourceId = :sourceId")
    suspend fun getBySourceId(sourceId: String): List<MemoryObject>

    @Query("SELECT * FROM memory_objects WHERE type = :type AND status = :status ORDER BY extractedAt DESC")
    suspend fun getByTypeAndStatus(type: MemoryType, status: MemoryObjectStatus): List<MemoryObject>

    @Query("SELECT * FROM memory_objects WHERE status IN (:statuses) ORDER BY extractedAt DESC")
    suspend fun getByStatuses(statuses: List<MemoryObjectStatus>): List<MemoryObject>

    @Query("SELECT * FROM memory_objects WHERE projectEntityId = :projectId ORDER BY extractedAt DESC")
    suspend fun getByProject(projectId: String): List<MemoryObject>

    @Query("SELECT * FROM memory_objects WHERE ownerEntityId = :ownerId ORDER BY extractedAt DESC")
    suspend fun getByOwner(ownerId: String): List<MemoryObject>

    @Query("SELECT * FROM memory_objects WHERE extractedAt BETWEEN :startMs AND :endMs ORDER BY extractedAt DESC")
    suspend fun getByDateRange(startMs: Long, endMs: Long): List<MemoryObject>

    @Query("SELECT * FROM memory_objects WHERE status = 'DETECTED' ORDER BY confidence DESC")
    suspend fun getDetected(): List<MemoryObject>

    // -- Entity merge support --

    @Query("UPDATE memory_objects SET projectEntityId = :targetId WHERE projectEntityId = :sourceId")
    suspend fun repointProjectEntityId(sourceId: String, targetId: String)

    @Query("UPDATE memory_objects SET ownerEntityId = :targetId WHERE ownerEntityId = :sourceId")
    suspend fun repointOwnerEntityId(sourceId: String, targetId: String)

    @Query("SELECT * FROM memory_objects WHERE sourceSegmentId = :segmentId AND normalizedStatement = :normalizedStatement LIMIT 1")
    suspend fun getBySegmentAndStatement(segmentId: String, normalizedStatement: String): MemoryObject?

    @Query("DELETE FROM memory_objects WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memory_objects WHERE sourceId = :sourceId")
    suspend fun deleteBySourceId(sourceId: String)

    @Query("SELECT id FROM memory_objects WHERE sourceId = :sourceId")
    suspend fun getIdsBySourceId(sourceId: String): List<String>

    @Query("DELETE FROM memory_objects WHERE sourceSegmentId = :sourceSegmentId")
    suspend fun deleteBySourceSegmentId(sourceSegmentId: String)

    @Query("DELETE FROM memory_objects")
    suspend fun clearAll()

    // -- Evaluation dashboard aggregates (Phase 8d) --

    @Query("SELECT status, COUNT(*) AS cnt FROM memory_objects GROUP BY status")
    suspend fun countByStatus(): List<StatusCount>

    @Query("SELECT COUNT(*) FROM memory_objects")
    suspend fun totalCount(): Int
}
