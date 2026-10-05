package com.noteflowai.app.data.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.noteflowai.app.data.memory.model.AnswerCitation
import com.noteflowai.app.data.memory.model.StatusCount

@Dao
interface AnswerCitationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(citation: AnswerCitation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(citations: List<AnswerCitation>)

    @Query("SELECT * FROM answer_citations WHERE answerId = :answerId ORDER BY claimIndex ASC")
    suspend fun getByAnswerId(answerId: String): List<AnswerCitation>

    @Query("SELECT * FROM answer_citations WHERE sourceSegmentId = :sourceSegmentId")
    suspend fun getBySourceSegmentId(sourceSegmentId: String): List<AnswerCitation>

    @Query("DELETE FROM answer_citations WHERE answerId = :answerId")
    suspend fun deleteByAnswerId(answerId: String)

    @Query("DELETE FROM answer_citations WHERE sourceSegmentId = :sourceSegmentId")
    suspend fun deleteBySourceSegmentId(sourceSegmentId: String)

    @Query("DELETE FROM answer_citations WHERE sourceSegmentId IN (SELECT id FROM source_segments WHERE sourceId = :sourceId)")
    suspend fun deleteBySourceId(sourceId: String)

    @Query("DELETE FROM answer_citations")
    suspend fun clearAll()

    // -- Evaluation dashboard aggregates (Phase 8d) --

    @Query("SELECT supportStatus AS status, COUNT(*) AS cnt FROM answer_citations GROUP BY supportStatus")
    suspend fun countBySupportStatus(): List<StatusCount>

    @Query("SELECT locationType AS status, COUNT(*) AS cnt FROM answer_citations GROUP BY locationType")
    suspend fun countByLocationType(): List<StatusCount>

    @Query("SELECT COUNT(DISTINCT answerId) FROM answer_citations")
    suspend fun distinctAnswerCount(): Int

    @Query("SELECT COUNT(*) FROM answer_citations")
    suspend fun totalCount(): Int
}
