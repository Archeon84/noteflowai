package com.noteflowai.app.data.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceSegmentDao {

    // -- Insert --

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(segment: SourceSegment)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(segments: List<SourceSegment>)

    // -- Update --

    @Update
    suspend fun update(segment: SourceSegment)

    // -- Get by ID --

    @Query("SELECT * FROM source_segments WHERE id = :id")
    suspend fun getById(id: String): SourceSegment?

    // -- Get by IDs (batch) --

    @Query("SELECT * FROM source_segments WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<SourceSegment>

    // -- Get by source ID --

    @Query("SELECT * FROM source_segments WHERE sourceId = :sourceId ORDER BY startMs ASC, pageNumber ASC, createdAt ASC")
    suspend fun getBySourceId(sourceId: String): List<SourceSegment>

    @Query("SELECT * FROM source_segments WHERE sourceId = :sourceId ORDER BY startMs ASC, pageNumber ASC, createdAt ASC")
    fun getBySourceIdFlow(sourceId: String): Flow<List<SourceSegment>>

    // -- Get by source type --

    @Query("SELECT * FROM source_segments WHERE sourceType = :sourceType ORDER BY createdAt DESC")
    suspend fun getBySourceType(sourceType: SourceType): List<SourceSegment>

    // -- Get neighboring segments --

    /**
     * Get segments that are neighbors of a given segment within the same source.
     * Returns segments ordered by position (startMs or pageNumber).
     */
    @Query("""
        SELECT * FROM source_segments
        WHERE sourceId = :sourceId
        AND id != :excludeSegmentId
        ORDER BY
            CASE
                WHEN startMs IS NOT NULL THEN startMs
                WHEN pageNumber IS NOT NULL THEN pageNumber * 1000000
                ELSE createdAt
            END ASC
    """)
    suspend fun getNeighbors(sourceId: String, excludeSegmentId: String): List<SourceSegment>

    // -- Get segments in a time range --

    @Query("""
        SELECT * FROM source_segments
        WHERE sourceId = :sourceId
        AND startMs IS NOT NULL
        AND startMs >= :startMs
        AND startMs <= :endMs
        ORDER BY startMs ASC
    """)
    suspend fun getInTimeRange(sourceId: String, startMs: Long, endMs: Long): List<SourceSegment>

    // -- Get segments on a page --

    @Query("""
        SELECT * FROM source_segments
        WHERE sourceId = :sourceId
        AND pageNumber = :pageNumber
        ORDER BY createdAt ASC
    """)
    suspend fun getOnPage(sourceId: String, pageNumber: Int): List<SourceSegment>

    // -- Search by source and metadata --

    @Query("""
        SELECT * FROM source_segments
        WHERE sourceId = :sourceId
        AND normalizedText LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY createdAt DESC
    """)
    suspend fun searchBySource(sourceId: String, query: String): List<SourceSegment>

    @Query("""
        SELECT * FROM source_segments
        WHERE normalizedText LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchAll(query: String, limit: Int = 50): List<SourceSegment>

    // -- Delete --

    @Query("DELETE FROM source_segments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM source_segments WHERE sourceId = :sourceId")
    suspend fun deleteBySourceId(sourceId: String)

    @Query("DELETE FROM source_segments WHERE sourceType = :sourceType")
    suspend fun deleteBySourceType(sourceType: SourceType)

    @Query("DELETE FROM source_segments")
    suspend fun clearAll()

    // -- Counts --

    @Query("SELECT COUNT(*) FROM source_segments WHERE sourceId = :sourceId")
    suspend fun countBySourceId(sourceId: String): Int

    @Query("SELECT COUNT(*) FROM source_segments")
    suspend fun totalCount(): Int
}

/**
 * Phase 0 safety: escape SQLite LIKE wildcards in a bound parameter.
 * Without this a user query containing `%` matches every row (recall
 * explosion) and `_` false-positives; system-generated segment IDs contain
 * `_` (e.g. `note_<src>_block0_<uuid>`), so even the conflict cleanup DELETE
 * could over-match. Callers pair this with the `ESCAPE '\'` clause on the
 * query side. Escaping only — case-folding is the caller's choice (search
 * columns are normalized; ID columns are NOT, so the conflict DELETE must
 * not fold).
 */
internal fun escapeLike(raw: String): String =
    raw.replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
