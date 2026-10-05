package com.noteflowai.app.data.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.noteflowai.app.data.memory.model.EntityMention

@Dao
interface EntityMentionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mention: EntityMention)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mentions: List<EntityMention>)

    @Query("SELECT * FROM entity_mentions WHERE entityId = :entityId")
    suspend fun getByEntityId(entityId: String): List<EntityMention>

    /**
     * Mentions of an entity that have not been link-removed, for retrieval.
     * A REJECTED mention keeps its row (so a rebuild cannot re-insert over the same
     * primary key) but is hidden from retrieval and note linking.
     */
    @Query("SELECT * FROM entity_mentions WHERE entityId = :entityId AND confirmation != 'REJECTED'")
    suspend fun getActiveByEntityId(entityId: String): List<EntityMention>

    @Query("SELECT * FROM entity_mentions WHERE sourceSegmentId = :sourceSegmentId")
    suspend fun getBySourceSegmentId(sourceSegmentId: String): List<EntityMention>

    /**
     * Mentions of a segment restricted to entities and mentions the user has not
     * rejected, for note-to-note linking (AutoLinker). Joins the entities table so a
     * REJECTED entity never links a note through its mentions.
     */
    @Query("SELECT m.* FROM entity_mentions m JOIN entities e ON e.id = m.entityId WHERE m.sourceSegmentId = :sourceSegmentId AND m.confirmation != 'REJECTED' AND e.confirmation != 'REJECTED'")
    suspend fun getActiveBySourceSegmentId(sourceSegmentId: String): List<EntityMention>

    /**
     * All mentions on any of [segmentIds] regardless of confirmation, for the
     * Filter & Eval Gate prune validator (full-chunk rejection detection).
     */
    @Query("SELECT * FROM entity_mentions WHERE sourceSegmentId IN (:segmentIds)")
    suspend fun getAllBySegmentIds(segmentIds: List<String>): List<EntityMention>

    /**
     * Mentions on any of [segmentIds] restricted to entities and mentions the user
     * has not rejected (batched sibling of [getActiveBySourceSegmentId]). Joins the
     * entities table so a REJECTED entity never counts as an active mention.
     */
    @Query("SELECT m.* FROM entity_mentions m JOIN entities e ON e.id = m.entityId WHERE m.sourceSegmentId IN (:segmentIds) AND m.confirmation != 'REJECTED' AND e.confirmation != 'REJECTED'")
    suspend fun getActiveBySegmentIds(segmentIds: List<String>): List<EntityMention>

    /** Count of link-removed (REJECTED) mentions, for the rebuild final report. */
    @Query("SELECT COUNT(*) FROM entity_mentions WHERE confirmation = 'REJECTED'")
    suspend fun countRejected(): Int

    @Query("DELETE FROM entity_mentions WHERE entityId = :entityId")
    suspend fun deleteByEntityId(entityId: String)

    @Query("DELETE FROM entity_mentions WHERE sourceSegmentId = :sourceSegmentId")
    suspend fun deleteBySourceSegmentId(sourceSegmentId: String)

    @Query("DELETE FROM entity_mentions WHERE entityId = :entityId AND sourceSegmentId = :sourceSegmentId")
    suspend fun delete(entityId: String, sourceSegmentId: String)

    @Query("UPDATE entity_mentions SET confirmation = 'REJECTED' WHERE entityId = :entityId AND sourceSegmentId = :sourceSegmentId")
    suspend fun reject(entityId: String, sourceSegmentId: String)

    @Query("DELETE FROM entity_mentions WHERE sourceSegmentId IN (SELECT id FROM source_segments WHERE sourceId = :sourceId)")
    suspend fun deleteBySourceId(sourceId: String)

    @Query("DELETE FROM entity_mentions")
    suspend fun clearAll()
}
