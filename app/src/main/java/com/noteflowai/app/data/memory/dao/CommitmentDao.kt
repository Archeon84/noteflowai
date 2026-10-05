package com.noteflowai.app.data.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.noteflowai.app.data.memory.model.Commitment
import com.noteflowai.app.data.memory.model.CommitmentStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CommitmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(commitment: Commitment)

    @Update
    suspend fun update(commitment: Commitment)

    @Query("SELECT * FROM commitments")
    suspend fun getAll(): List<Commitment>

    @Query("SELECT * FROM commitments WHERE id = :id")
    suspend fun getById(id: String): Commitment?

    @Query("SELECT * FROM commitments WHERE memoryObjectId = :memoryObjectId")
    suspend fun getByMemoryObjectId(memoryObjectId: String): Commitment?

    @Query("SELECT * FROM commitments WHERE status IN ('CONFIRMED', 'ACTIVE') ORDER BY createdAt DESC")
    suspend fun getActive(): List<Commitment>

    @Query("SELECT COUNT(*) FROM commitments WHERE status IN ('CONFIRMED', 'ACTIVE')")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM commitments WHERE status IN ('ACTIVE', 'CONFIRMED') AND dueAt IS NOT NULL AND dueAt > :now AND dueAt <= :deadline ORDER BY dueAt ASC")
    suspend fun getDueSoon(deadline: Long, now: Long = System.currentTimeMillis()): List<Commitment>

    @Query("SELECT * FROM commitments WHERE status IN ('ACTIVE', 'CONFIRMED') AND dueAt IS NOT NULL AND dueAt <= :now ORDER BY dueAt ASC")
    suspend fun getOverdue(now: Long = System.currentTimeMillis()): List<Commitment>

    @Query("SELECT * FROM commitments WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    suspend fun getCompleted(): List<Commitment>

    @Query("SELECT * FROM commitments WHERE ownerEntityId = :ownerId ORDER BY createdAt DESC")
    suspend fun getByOwner(ownerId: String): List<Commitment>

    @Query("SELECT * FROM commitments WHERE projectEntityId = :projectId ORDER BY createdAt DESC")
    suspend fun getByProject(projectId: String): List<Commitment>

    @Query("SELECT * FROM commitments WHERE status = :status ORDER BY createdAt DESC")
    suspend fun getByStatus(status: CommitmentStatus): List<Commitment>

    @Query("UPDATE commitments SET projectEntityId = :targetId WHERE projectEntityId = :sourceId")
    suspend fun repointProjectEntityId(sourceId: String, targetId: String)

    @Query("UPDATE commitments SET ownerEntityId = :targetId WHERE ownerEntityId = :sourceId")
    suspend fun repointOwnerEntityId(sourceId: String, targetId: String)

    @Query("DELETE FROM commitments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM commitments WHERE sourceSegmentId = :sourceSegmentId")
    suspend fun deleteBySourceSegmentId(sourceSegmentId: String)

    @Query("DELETE FROM commitments WHERE sourceSegmentId IN (SELECT id FROM source_segments WHERE sourceId = :sourceId)")
    suspend fun deleteBySourceId(sourceId: String)

    @Query("SELECT id FROM commitments WHERE sourceSegmentId IN (SELECT id FROM source_segments WHERE sourceId = :sourceId)")
    suspend fun getIdsBySourceId(sourceId: String): List<String>

    @Query("DELETE FROM commitments")
    suspend fun clearAll()
}
