package com.noteflowai.app.data.memory.pipeline

import android.content.Context
import android.util.Log
import com.noteflowai.app.data.memory.dao.EntityMentionDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.dao.MemoryRelationDao
import com.noteflowai.app.data.memory.db.MemoryDatabaseModule
import com.noteflowai.app.data.memory.model.MemoryRelation
import com.noteflowai.app.data.memory.model.RelationType
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Detects conservative, deterministic relations between memory objects and
 * entities for a source (§7 RELATION_DETECTION stage).
 *
 * No LLM is involved. Two link kinds are created, both low-confidence so the
 * user confirms before anything becomes authoritative:
 *
 *  - MENTIONS: every memory object is linked to each entity mentioned in the
 *    same source segment it was extracted from.
 *  - RELATED_TO: every pair of memory objects extracted from the same segment
 *    is linked to each other.
 *
 * Re-running for a source is idempotent: existing relations for the source's
 * segments are deleted before fresh links are inserted.
 */
class RelationDetectionService(
    private val context: Context,
    private val sourceSegmentRepository: SourceSegmentRepository,
    private val memoryObjectDao: MemoryObjectDao,
    private val entityMentionDao: EntityMentionDao,
    private val memoryRelationDao: MemoryRelationDao
) {

    companion object {
        private const val TAG = "RelationDetectionService"

        private const val ENTITY_TYPE = "ENTITY"
        private const val CONFIDENCE_MENTIONS = 0.5f
        private const val CONFIDENCE_RELATED = 0.4f

        fun getInstance(context: Context): RelationDetectionService = RelationDetectionService(
            context.applicationContext,
            SourceSegmentRepository(context),
            MemoryDatabaseModule.provideMemoryObjectDao(context),
            MemoryDatabaseModule.provideEntityMentionDao(context),
            MemoryDatabaseModule.provideMemoryRelationDao(context)
        )
    }

    /**
     * Detect relations for all segments of [sourceId]. Returns the number of
     * relations created (0 is a valid success). Never throws.
     */
    suspend fun detectForSource(sourceId: String): Int = withContext(Dispatchers.IO) {
        try {
            val segments = sourceSegmentRepository.getBySourceId(sourceId)
            if (segments.isEmpty()) {
                Log.w(TAG, "No segments for relation detection: $sourceId")
                return@withContext 0
            }

            // Idempotency: drop previous relations for this source's segments.
            memoryRelationDao.deleteBySourceId(sourceId)

            val toInsert = mutableListOf<MemoryRelation>()
            // Canonical pair key to avoid duplicate RELATED_TO edges per segment.
            val pairKeys = mutableSetOf<String>()

            // Batched fetch: the old per-segment query pair was N+1 on the
            // segment count. One query per table, grouped in memory.
            val segmentIds = segments.map { it.id }
            val objectsBySegment = memoryObjectDao.getBySourceId(sourceId)
                .groupBy { it.sourceSegmentId }
            val mentionsBySegment = if (segmentIds.isEmpty()) {
                emptyMap()
            } else {
                entityMentionDao.getActiveBySegmentIds(segmentIds)
                    .groupBy { it.sourceSegmentId }
            }

            for (segment in segments) {
                val objects = objectsBySegment[segment.id].orEmpty()
                if (objects.isEmpty()) continue

                // Active mentions only: a REJECTED link must never gain fresh
                // MENTIONS edges (Phase-4 "rejected never links").
                val entityIds = mentionsBySegment[segment.id].orEmpty()
                    .map { it.entityId }.distinct()

                for (obj in objects) {
                    for (entityId in entityIds) {
                        toInsert.add(
                            MemoryRelation(
                                id = "rel_${UUID.randomUUID()}",
                                fromType = obj.type.name,
                                fromId = obj.id,
                                toType = ENTITY_TYPE,
                                toId = entityId,
                                relationType = RelationType.MENTIONS,
                                sourceSegmentId = segment.id,
                                confidence = CONFIDENCE_MENTIONS
                            )
                        )
                    }
                }

                for (i in objects.indices) {
                    for (j in i + 1 until objects.size) {
                        val a = objects[i]
                        val b = objects[j]
                        val key = listOf(a.id, b.id).sorted().joinToString("|")
                        if (pairKeys.add(key)) {
                            toInsert.add(
                                MemoryRelation(
                                    id = "rel_${UUID.randomUUID()}",
                                    fromType = a.type.name,
                                    fromId = a.id,
                                    toType = b.type.name,
                                    toId = b.id,
                                    relationType = RelationType.RELATED_TO,
                                    sourceSegmentId = segment.id,
                                    confidence = CONFIDENCE_RELATED
                                )
                            )
                        }
                    }
                }
            }

            if (toInsert.isNotEmpty()) {
                memoryRelationDao.insertAll(toInsert)
            }
            Log.i(TAG, "Detected ${toInsert.size} relations for source: $sourceId")
            toInsert.size
        } catch (e: Exception) {
            Log.e(TAG, "Relation detection failed for source: $sourceId", e)
            0
        }
    }
}
