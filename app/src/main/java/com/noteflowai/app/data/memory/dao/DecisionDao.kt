package com.noteflowai.app.data.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.DecisionStatus

@Dao
interface DecisionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(decision: Decision)

    @Update
    suspend fun update(decision: Decision)

    @Query("SELECT * FROM decisions")
    suspend fun getAll(): List<Decision>

    @Query("SELECT * FROM decisions WHERE id = :id")
    suspend fun getById(id: String): Decision?

    @Query("SELECT * FROM decisions WHERE memoryObjectId = :memoryObjectId")
    suspend fun getByMemoryObjectId(memoryObjectId: String): Decision?

    @Query("SELECT * FROM decisions WHERE status IN ('CONFIRMED', 'ACTIVE') ORDER BY decidedAt DESC")
    suspend fun getActive(): List<Decision>

    @Query("SELECT * FROM decisions WHERE projectEntityId = :projectId ORDER BY decidedAt DESC")
    suspend fun getByProject(projectId: String): List<Decision>

    @Query("SELECT * FROM decisions WHERE decidedAt BETWEEN :startMs AND :endMs ORDER BY decidedAt DESC")
    suspend fun getByDateRange(startMs: Long, endMs: Long): List<Decision>

    @Query("SELECT * FROM decisions WHERE status = :status ORDER BY decidedAt DESC")
    suspend fun getByStatus(status: DecisionStatus): List<Decision>

    @Query("SELECT * FROM decisions WHERE reviewAt IS NOT NULL AND reviewAt <= :now ORDER BY reviewAt ASC")
    suspend fun getNeedingReview(now: Long = System.currentTimeMillis()): List<Decision>

    @Query("SELECT * FROM decisions ORDER BY decidedAt DESC")
    suspend fun getTimeline(): List<Decision>

    @Query("UPDATE decisions SET projectEntityId = :targetId WHERE projectEntityId = :sourceId")
    suspend fun repointProjectEntityId(sourceId: String, targetId: String)

    @Query("DELETE FROM decisions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM decisions WHERE sourceSegmentId = :sourceSegmentId")
    suspend fun deleteBySourceSegmentId(sourceSegmentId: String)

    @Query("DELETE FROM decisions WHERE sourceSegmentId IN (SELECT id FROM source_segments WHERE sourceId = :sourceId)")
    suspend fun deleteBySourceId(sourceId: String)

    @Query("SELECT id FROM decisions WHERE sourceSegmentId IN (SELECT id FROM source_segments WHERE sourceId = :sourceId)")
    suspend fun getIdsBySourceId(sourceId: String): List<String>

    @Query("DELETE FROM decisions")
    suspend fun clearAll()
}
