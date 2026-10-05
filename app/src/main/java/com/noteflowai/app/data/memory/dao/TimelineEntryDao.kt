package com.noteflowai.app.data.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.TimelineEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TimelineEntry)

    @Update
    suspend fun update(entry: TimelineEntry)

    @Query("SELECT * FROM timeline_entries WHERE id = :id")
    suspend fun getById(id: String): TimelineEntry?

    @Query("SELECT * FROM timeline_entries ORDER BY startMs IS NULL, startMs ASC, createdAt ASC")
    suspend fun getAll(): List<TimelineEntry>

    @Query("SELECT * FROM timeline_entries ORDER BY startMs IS NULL, startMs ASC, createdAt ASC")
    fun observeAll(): Flow<List<TimelineEntry>>

    @Query("SELECT * FROM timeline_entries WHERE sourceNoteId = :noteId ORDER BY startMs ASC")
    suspend fun getBySourceNoteId(noteId: String): List<TimelineEntry>

    @Query("SELECT * FROM timeline_entries WHERE sourceSegmentId IN (:segmentIds) AND confirmation != 'REJECTED' ORDER BY startMs ASC")
    suspend fun getBySegmentIds(segmentIds: List<String>): List<TimelineEntry>

    @Query("SELECT * FROM timeline_entries WHERE startMs IS NOT NULL AND startMs >= :startMs AND startMs <= :endMs ORDER BY startMs ASC")
    suspend fun getByDateRange(startMs: Long, endMs: Long): List<TimelineEntry>

    @Query("SELECT * FROM timeline_entries WHERE confirmation = :confirmation ORDER BY startMs ASC")
    suspend fun getByConfirmation(confirmation: ConfirmationState): List<TimelineEntry>

    @Query("SELECT * FROM timeline_entries WHERE confirmation = 'SUGGESTED' ORDER BY startMs ASC")
    suspend fun getUnconfirmed(): List<TimelineEntry>

    @Query("SELECT COUNT(*) FROM timeline_entries WHERE sourceNoteId = :noteId")
    suspend fun countBySourceNoteId(noteId: String): Int

    @Query("DELETE FROM timeline_entries WHERE sourceNoteId = :noteId")
    suspend fun deleteBySourceId(noteId: String)

    @Query("DELETE FROM timeline_entries WHERE sourceSegmentId = :segmentId")
    suspend fun deleteBySourceSegmentId(segmentId: String)

    @Query("DELETE FROM timeline_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM timeline_entries")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM timeline_entries")
    suspend fun totalCount(): Int
}