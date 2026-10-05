package com.noteflowai.app.data.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.noteflowai.app.data.memory.model.MemoryReviewItem
import com.noteflowai.app.data.memory.model.ReviewItemStatus
import com.noteflowai.app.data.memory.model.ReviewItemType
import com.noteflowai.app.data.memory.model.StatusCount
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryReviewItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MemoryReviewItem)

    @Update
    suspend fun update(item: MemoryReviewItem)

    @Query("SELECT * FROM memory_review_items WHERE id = :id")
    suspend fun getById(id: String): MemoryReviewItem?

    @Query("SELECT * FROM memory_review_items WHERE status = 'PENDING' ORDER BY createdAt DESC")
    suspend fun getPending(): List<MemoryReviewItem>

    @Query("SELECT * FROM memory_review_items WHERE status = 'PENDING' AND type = :type ORDER BY createdAt DESC")
    suspend fun getPendingByType(type: ReviewItemType): List<MemoryReviewItem>

    @Query("SELECT * FROM memory_review_items WHERE referencedObjectId = :objectId")
    suspend fun getByReferencedObject(objectId: String): List<MemoryReviewItem>

    @Query("DELETE FROM memory_review_items WHERE referencedObjectId IN (:objectIds)")
    suspend fun deleteByObjectIds(objectIds: List<String>)

    @Query("SELECT * FROM memory_review_items ORDER BY createdAt DESC")
    suspend fun getAll(): List<MemoryReviewItem>

    @Query("UPDATE memory_review_items SET referencedObjectId = :targetId WHERE type = 'ENTITY' AND referencedObjectId = :sourceId")
    suspend fun repointEntityReference(sourceId: String, targetId: String)

    @Query("DELETE FROM memory_review_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM memory_review_items WHERE type = 'ENTITY' AND referencedObjectId = :entityId")
    suspend fun deleteByEntityId(entityId: String)

    @Query("DELETE FROM memory_review_items")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM memory_review_items WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM memory_review_items WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    // -- Evaluation dashboard aggregates (Phase 8d) --

    @Query("SELECT status, COUNT(*) AS cnt FROM memory_review_items GROUP BY status")
    suspend fun countByStatus(): List<StatusCount>
}
