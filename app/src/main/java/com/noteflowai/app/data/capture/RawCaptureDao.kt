package com.noteflowai.app.data.capture

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * DAO for the durable raw-capture table ([RawCapture]).
 */
@Dao
interface RawCaptureDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(capture: RawCapture)

    @Update
    suspend fun update(capture: RawCapture)

    @Query("SELECT * FROM raw_captures WHERE sourceId = :sourceId")
    suspend fun getBySourceId(sourceId: String): RawCapture?

    @Query("SELECT * FROM raw_captures")
    suspend fun getAll(): List<RawCapture>

    /**
     * Captures that have not reached a terminal READY/FAILED state — i.e. rows that may still
     * have pending or interrupted processing work. Re-enqueued after app start.
     */
    @Query("SELECT * FROM raw_captures WHERE status IN ('CAPTURED', 'PROCESSING')")
    suspend fun getIncomplete(): List<RawCapture>

    @Query(
        "UPDATE raw_captures SET status = :status, updatedAt = :updatedAt WHERE sourceId = :sourceId"
    )
    suspend fun updateStatus(sourceId: String, status: CaptureStatus, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM raw_captures WHERE sourceId = :sourceId")
    suspend fun deleteBySourceId(sourceId: String)
}