package com.noteflowai.app.data.memory.dao

import androidx.room.Dao
import kotlinx.coroutines.flow.Flow
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.noteflowai.app.data.memory.model.ProcessingStatus
import com.noteflowai.app.data.memory.model.StatusCount

@Dao
interface ProcessingStatusDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(status: ProcessingStatus)

    @Query("SELECT * FROM processing_status WHERE sourceId = :sourceId")
    suspend fun getBySourceId(sourceId: String): ProcessingStatus?

    @Query("SELECT * FROM processing_status")
    suspend fun getAll(): List<ProcessingStatus>

    @Query("SELECT * FROM processing_status")
    fun observeAll(): Flow<List<ProcessingStatus>>

    /**
     * Rows that need to be re-enqueued after an app restart:
     * pending work, work interrupted mid-run, and rows parked at a SKIPPED
     * stage (a flag enabled later must actually run the skipped stage).
     * PENDING is included defensively: the pipeline never writes it today,
     * but any future PENDING writer must be recovered, not stranded.
     */
    @Query("SELECT * FROM processing_status WHERE status IN ('PENDING', 'RUNNING', 'FAILED_RETRYABLE', 'SKIPPED')")
    suspend fun getIncomplete(): List<ProcessingStatus>

    @Query("DELETE FROM processing_status WHERE sourceId = :sourceId")
    suspend fun deleteBySourceId(sourceId: String)

    @Query("DELETE FROM processing_status")
    suspend fun clearAll()

    // -- Evaluation dashboard aggregates (Phase 8d) --

    @Query("SELECT status, COUNT(*) AS cnt FROM processing_status GROUP BY status")
    suspend fun countByStatus(): List<StatusCount>

    @Query("SELECT COUNT(*) FROM processing_status WHERE attempts > 1")
    suspend fun countRetried(): Int

    @Query("SELECT COUNT(*) FROM processing_status WHERE status IN ('PENDING', 'RUNNING', 'FAILED_RETRYABLE') AND updatedAt < :cutoff")
    suspend fun countStuck(cutoff: Long): Int

    @Query("SELECT AVG(attempts) FROM processing_status")
    suspend fun avgAttempts(): Double?

    @Query("SELECT COUNT(*) FROM processing_status")
    suspend fun totalCount(): Int

    @Query("""
        UPDATE processing_status
        SET status = 'PENDING', currentStage = 'SEGMENTS_CREATED', attempts = 0, error = NULL, updatedAt = :now
        WHERE status = 'FAILED_PERMANENT' AND error LIKE '%' || :reasonSubstring || '%'
    """)
    suspend fun resetPermanentFailuresByReason(
        reasonSubstring: String,
        now: Long = System.currentTimeMillis()
    ): Int
}
