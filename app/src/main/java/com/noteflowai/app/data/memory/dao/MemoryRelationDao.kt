package com.noteflowai.app.data.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.noteflowai.app.data.memory.model.MemoryRelation
import com.noteflowai.app.data.memory.model.RelationType

@Dao
interface MemoryRelationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(relation: MemoryRelation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(relations: List<MemoryRelation>)

    @Query("SELECT * FROM memory_relations WHERE fromId = :objectId OR toId = :objectId")
    suspend fun getByObjectId(objectId: String): List<MemoryRelation>

    @Query("""
        SELECT DISTINCT CASE WHEN fromId = :entityId THEN toId ELSE fromId END as neighborId
        FROM memory_relations
        WHERE (fromId = :entityId OR toId = :entityId)
          AND fromType = 'ENTITY' AND toType = 'ENTITY'
          AND confidence >= :minConfidence
    """)
    suspend fun getNeighborEntityIds(entityId: String, minConfidence: Float = 0.4f): List<String>

    @Query("SELECT * FROM memory_relations WHERE (fromId = :entityId OR toId = :entityId) AND confidence >= :minConfidence ORDER BY confidence DESC")
    suspend fun getRelationsForEntity(entityId: String, minConfidence: Float = 0.4f): List<MemoryRelation>

    @Query("SELECT * FROM memory_relations WHERE fromId = :objectId")
    suspend fun getByFromId(objectId: String): List<MemoryRelation>

    @Query("SELECT * FROM memory_relations WHERE toId = :objectId")
    suspend fun getByToId(objectId: String): List<MemoryRelation>

    @Query("SELECT * FROM memory_relations WHERE fromType = :fromType AND fromId = :fromId")
    suspend fun getFromObject(fromType: String, fromId: String): List<MemoryRelation>

    @Query("SELECT * FROM memory_relations WHERE toType = :toType AND toId = :toId")
    suspend fun getToObject(toType: String, toId: String): List<MemoryRelation>

    @Query("SELECT * FROM memory_relations WHERE relationType = :relationType")
    suspend fun getByRelationType(relationType: RelationType): List<MemoryRelation>

    @Query("UPDATE memory_relations SET fromId = :targetId WHERE fromType = 'ENTITY' AND fromId = :sourceId")
    suspend fun repointFromEntity(sourceId: String, targetId: String)

    @Query("UPDATE memory_relations SET toId = :targetId WHERE toType = 'ENTITY' AND toId = :sourceId")
    suspend fun repointToEntity(sourceId: String, targetId: String)

    @Query("DELETE FROM memory_relations WHERE fromId = :objectId OR toId = :objectId")
    suspend fun deleteByObjectId(objectId: String)

    @Query("DELETE FROM memory_relations WHERE sourceSegmentId = :sourceSegmentId")
    suspend fun deleteBySourceSegmentId(sourceSegmentId: String)

    @Query("DELETE FROM memory_relations WHERE (fromType = :fromType AND fromId = :fromId) AND sourceSegmentId = :sourceSegmentId")
    suspend fun deleteSpecific(fromType: String, fromId: String, sourceSegmentId: String)

    @Query("DELETE FROM memory_relations WHERE sourceSegmentId IN (SELECT id FROM source_segments WHERE sourceId = :sourceId)")
    suspend fun deleteBySourceId(sourceId: String)

    @Query("DELETE FROM memory_relations")
    suspend fun clearAll()

    // -- Evaluation dashboard aggregates (Phase 8d) --

    @Query("SELECT COUNT(*) FROM memory_relations")
    suspend fun totalCount(): Int
}
