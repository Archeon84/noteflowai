package com.noteflowai.app.data.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.noteflowai.app.data.memory.model.Conflict
import com.noteflowai.app.data.memory.model.ConflictStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ConflictDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conflict: Conflict)

    @Update
    suspend fun update(conflict: Conflict)

    @Query("SELECT * FROM conflicts WHERE id = :id")
    suspend fun getById(id: String): Conflict?

    @Query("SELECT * FROM conflicts WHERE status = 'PENDING' ORDER BY createdAt DESC")
    suspend fun getPending(): List<Conflict>

    @Query("SELECT COUNT(*) FROM conflicts WHERE status = 'PENDING' AND conflictType = :type AND objectIds = :objectIds")
    suspend fun pendingCountFor(type: String, objectIds: String): Int

    @Query("SELECT * FROM conflicts WHERE status = :status ORDER BY createdAt DESC")
    suspend fun getByStatus(status: ConflictStatus): List<Conflict>

    @Query("SELECT * FROM conflicts ORDER BY createdAt DESC")
    suspend fun getAll(): List<Conflict>

    @Query("DELETE FROM conflicts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conflicts WHERE sourceSegmentIds LIKE '%' || :segmentId || '%' ESCAPE '\\'")
    suspend fun deleteBySourceSegmentId(segmentId: String)

    @Query("DELETE FROM conflicts")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM conflicts WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM conflicts WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>
}
