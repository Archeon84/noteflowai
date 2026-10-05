package com.noteflowai.app.data.memory.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.noteflowai.app.data.memory.dao.CommitmentDao
import com.noteflowai.app.data.memory.dao.DecisionDao
import com.noteflowai.app.data.memory.dao.EntityDao
import com.noteflowai.app.data.memory.dao.EntityMentionDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.dao.MemoryRelationDao
import com.noteflowai.app.data.memory.dao.MemoryReviewItemDao
import com.noteflowai.app.data.memory.dao.SourceSegmentDao
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.Entity
import com.noteflowai.app.data.memory.model.EntityMention
import com.noteflowai.app.data.memory.model.EntityType
import com.noteflowai.app.data.memory.model.SourceSegment
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class EntityRepository private constructor(
    private val db: MemoryDatabase,
    private val entityDao: EntityDao,
    private val mentionDao: EntityMentionDao,
    private val memoryObjectDao: MemoryObjectDao,
    private val decisionDao: DecisionDao,
    private val commitmentDao: CommitmentDao,
    private val relationDao: MemoryRelationDao,
    private val reviewDao: MemoryReviewItemDao,
    private val segmentDao: SourceSegmentDao
) {

    constructor(context: Context) : this(MemoryDatabase.getInstance(context))

    /** Test seam: build the repository over an injected (e.g. in-memory) database. */
    internal constructor(db: MemoryDatabase) : this(
        db,
        db.entityDao(),
        db.entityMentionDao(),
        db.memoryObjectDao(),
        db.decisionDao(),
        db.commitmentDao(),
        db.memoryRelationDao(),
        db.memoryReviewItemDao(),
        db.sourceSegmentDao()
    )

    private val gson = Gson()

    suspend fun insert(entity: Entity) = withContext(Dispatchers.IO) {
        entityDao.insert(entity)
    }

    suspend fun update(entity: Entity) = withContext(Dispatchers.IO) {
        entityDao.update(entity.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun getById(id: String): Entity? = withContext(Dispatchers.IO) {
        entityDao.getById(id)
    }

    suspend fun getByNormalizedName(normalizedName: String): Entity? = withContext(Dispatchers.IO) {
        entityDao.getByNormalizedName(normalizedName.lowercase(java.util.Locale.ROOT).trim())
    }

    suspend fun getByType(type: EntityType): List<Entity> = withContext(Dispatchers.IO) {
        entityDao.getByType(type)
    }

    suspend fun getAll(): List<Entity> = withContext(Dispatchers.IO) {
        entityDao.getAll()
    }

    suspend fun getUnconfirmed(): List<Entity> = withContext(Dispatchers.IO) {
        entityDao.getUnconfirmed()
    }

    suspend fun getConfirmed(): List<Entity> = withContext(Dispatchers.IO) {
        entityDao.getConfirmed()
    }

    suspend fun search(query: String, limit: Int = 20): List<Entity> = withContext(Dispatchers.IO) {
        entityDao.search(com.noteflowai.app.data.memory.dao.escapeLike(query.lowercase(java.util.Locale.ROOT)), limit)
    }

    /**
     * Search restricted to non-rejected entities, for retrieval (guide §Phase 4:
     * rejected links no longer affect retrieval).
     * Unions canonical/normalized name matches with alias-expanded matches.
     */
    suspend fun searchActive(query: String, limit: Int = 20): List<Entity> = withContext(Dispatchers.IO) {
        val escaped = com.noteflowai.app.data.memory.dao.escapeLike(query.lowercase(java.util.Locale.ROOT))
        val byName = entityDao.searchActive(escaped, limit)
        val byAlias = entityDao.searchActiveByAlias(escaped, limit)
        (byName + byAlias)
            .distinctBy { it.id }
            .sortedByDescending { it.confidence }
            .take(limit)
    }

    /**
     * Mentions that have not been link-removed, for retrieval.
     */
    suspend fun getActiveMentions(entityId: String): List<EntityMention> = withContext(Dispatchers.IO) {
        mentionDao.getActiveByEntityId(entityId)
    }

    /**
     * Every mention on any of [segmentIds] (including REJECTED), for the Filter &
     * Eval Gate prune validator.
     */
    suspend fun getMentionsBySegmentIds(segmentIds: List<String>): List<EntityMention> = withContext(Dispatchers.IO) {
        if (segmentIds.isEmpty()) emptyList() else mentionDao.getAllBySegmentIds(segmentIds)
    }

    /**
     * Active (non-rejected) mentions on any of [segmentIds], for the Filter & Eval
     * Gate prune validator. Joins entities so a REJECTED entity's mentions are
     * excluded alongside REJECTED mention rows.
     */
    suspend fun getActiveMentionsBySegmentIds(segmentIds: List<String>): List<EntityMention> = withContext(Dispatchers.IO) {
        if (segmentIds.isEmpty()) emptyList() else mentionDao.getActiveBySegmentIds(segmentIds)
    }

    suspend fun getActiveByType(type: EntityType): List<Entity> = withContext(Dispatchers.IO) {
        entityDao.getActiveByType(type)
    }

    /**
     * 1-hop neighbor entities for graph expansion (Fix 1).
     * Returns active (non-rejected) entities connected via memory_relations.
     */
    suspend fun getNeighborEntities(entityId: String, minConfidence: Float = 0.4f): List<Entity> = withContext(Dispatchers.IO) {
        val neighborIds = relationDao.getNeighborEntityIds(entityId, minConfidence)
        neighborIds.mapNotNull { entityDao.getById(it) }
            .filter { it.confirmation != ConfirmationState.REJECTED }
    }

    /**
     * Active relations connected to [entityId] for graph context enrichment (Fix 5).
     */
    suspend fun getRelationsForEntity(entityId: String, minConfidence: Float = 0.4f): List<com.noteflowai.app.data.memory.model.MemoryRelation> = withContext(Dispatchers.IO) {
        relationDao.getRelationsForEntity(entityId, minConfidence)
    }

    suspend fun confirm(entityId: String) = withContext(Dispatchers.IO) {
        val entity = entityDao.getById(entityId) ?: return@withContext
        entityDao.update(entity.copy(
            confirmation = ConfirmationState.CONFIRMED,
            userConfirmed = true,
            updatedAt = System.currentTimeMillis()
        ))
    }

    /**
     * Durable reject (guide §Phase 4): mark the entity REJECTED and clear its previous
     * confirmation flag. Mentions and source segments are kept; retrieval and rebuild
     * both honor the REJECTED state (rejected entities never re-attract mentions and
     * never surface in results).
     */
    suspend fun reject(entityId: String) = withContext(Dispatchers.IO) {
        val entity = entityDao.getById(entityId) ?: return@withContext
        entityDao.update(entity.copy(
            confirmation = ConfirmationState.REJECTED,
            userConfirmed = false,
            updatedAt = System.currentTimeMillis()
        ))
    }

    /**
     * Remove a single mention link without touching the source note (guide §Phase 4).
     * The mention row is marked REJECTED rather than deleted so the composite primary key
     * stays occupied and a rebuild cannot re-insert a fresh mention over it.
     */
    suspend fun removeLink(entityId: String, sourceSegmentId: String) = withContext(Dispatchers.IO) {
        mentionDao.reject(entityId, sourceSegmentId)
    }

    /**
     * Insert an entity and its mention atomically in a Room transaction.
     * If an entity with the same normalized name already exists, link to it instead.
     *
     * Skips inserting when the target is rejected:
     *  - an existing REJECTED mention row keeps its seat and is never overwritten;
     *  - a REJECTED entity never receives a new mention (rebuild skip-rejected behavior).
     */
    suspend fun insertWithMention(entity: Entity, mention: EntityMention) = withContext(Dispatchers.IO) {
        // withTransaction (not runInTransaction + runBlocking, which risks
        // deadlock): Room's supported suspend-transaction path.
        db.withTransaction {
            // Normalize at the boundary: direct DAO access bypassed the
            // repository's lowercase/trim, so "Alice" and "alice" duplicated.
            val normalized = entity.copy(normalizedName = entity.normalizedName.lowercase(java.util.Locale.ROOT).trim())
            val existing = entityDao.getByNormalizedName(normalized.normalizedName)
            val actualEntity = existing ?: normalized
            if (actualEntity.confirmation == ConfirmationState.REJECTED) {
                return@withTransaction
            }
            val existingMention = mentionDao.getByEntityId(actualEntity.id)
                .any { it.sourceSegmentId == mention.sourceSegmentId }
            if (existingMention) {
                return@withTransaction
            }
            if (existing == null) {
                entityDao.insert(normalized)
            }
            mentionDao.insert(mention.copy(entityId = actualEntity.id))
        }
    }

    suspend fun getMentions(entityId: String): List<EntityMention> = withContext(Dispatchers.IO) {
        mentionDao.getByEntityId(entityId)
    }

    /**
     * Mentions of an entity with their source segments, for the detail evidence list.
     */
    suspend fun getMentionsWithSegments(entityId: String): List<Pair<EntityMention, SourceSegment?>> =
        withContext(Dispatchers.IO) {
            val mentions = mentionDao.getByEntityId(entityId)
            val segmentIds = mentions.map { it.sourceSegmentId }.distinct()
            val segments = if (segmentIds.isEmpty()) {
                emptyMap()
            } else {
                segmentDao.getByIds(segmentIds).associateBy { it.id }
            }
            mentions.map { it to segments[it.sourceSegmentId] }
        }

    /**
     * Rename an entity, keeping the previous canonical name as an alias.
     */
    suspend fun rename(entityId: String, newName: String) = withContext(Dispatchers.IO) {
        val entity = entityDao.getById(entityId) ?: return@withContext
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == entity.canonicalName) return@withContext
        val aliases = parseAliases(entity.aliasesJson).toMutableSet()
        aliases.add(entity.canonicalName)
        entityDao.update(entity.copy(
            canonicalName = trimmed,
            normalizedName = trimmed.lowercase(java.util.Locale.ROOT),
            aliasesJson = serializeAliases(aliases.toList()),
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun addAlias(entityId: String, alias: String) = withContext(Dispatchers.IO) {
        val entity = entityDao.getById(entityId) ?: return@withContext
        val trimmed = alias.trim()
        if (trimmed.isEmpty() || trimmed == entity.canonicalName) return@withContext
        val aliases = parseAliases(entity.aliasesJson).toMutableSet()
        aliases.add(trimmed)
        entityDao.update(entity.copy(
            aliasesJson = serializeAliases(aliases.toList()),
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun removeAlias(entityId: String, alias: String) = withContext(Dispatchers.IO) {
        val entity = entityDao.getById(entityId) ?: return@withContext
        val aliases = parseAliases(entity.aliasesJson).toMutableSet()
        if (aliases.remove(alias)) {
            entityDao.update(entity.copy(
                aliasesJson = serializeAliases(aliases.toList()),
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    /**
     * Merge [sourceId] into [targetId]: move mentions, merge aliases, repoint every
     * reference across memory objects, decisions, commitments, relations, and review
     * items, then delete the source entity. Runs atomically.
     */
    suspend fun merge(targetId: String, sourceId: String) = withContext(Dispatchers.IO) {
        if (targetId == sourceId) return@withContext
        db.runInTransaction {
            kotlinx.coroutines.runBlocking {
                val source = entityDao.getById(sourceId) ?: return@runBlocking
                val target = entityDao.getById(targetId) ?: return@runBlocking

                // Move mentions; skip segments the target already mentions.
                val targetSegments = mentionDao.getByEntityId(targetId).map { it.sourceSegmentId }.toSet()
                mentionDao.getByEntityId(sourceId).forEach { mention ->
                    if (mention.sourceSegmentId !in targetSegments) {
                        mentionDao.insert(mention.copy(entityId = targetId))
                    }
                }
                mentionDao.deleteByEntityId(sourceId)

                // Merge aliases: source canonical name + source aliases into target.
                val aliases = parseAliases(target.aliasesJson).toMutableSet()
                aliases.add(source.canonicalName)
                aliases.addAll(parseAliases(source.aliasesJson))
                aliases.remove(target.canonicalName)

                // Repoint references across tables.
                memoryObjectDao.repointProjectEntityId(sourceId, targetId)
                memoryObjectDao.repointOwnerEntityId(sourceId, targetId)
                decisionDao.repointProjectEntityId(sourceId, targetId)
                commitmentDao.repointProjectEntityId(sourceId, targetId)
                commitmentDao.repointOwnerEntityId(sourceId, targetId)
                relationDao.repointFromEntity(sourceId, targetId)
                relationDao.repointToEntity(sourceId, targetId)
                reviewDao.repointEntityReference(sourceId, targetId)

                entityDao.update(target.copy(
                    aliasesJson = serializeAliases(aliases.toList()),
                    confirmation = ConfirmationState.CONFIRMED,
                    userConfirmed = true,
                    updatedAt = System.currentTimeMillis()
                ))
                entityDao.deleteById(sourceId)
            }
        }
    }

    /**
     * Create a new entity from [sourceId] and move the selected mentions to it.
     * Returns the new entity id, or null if no mentions were selected.
     */
    suspend fun split(
        sourceId: String,
        newName: String,
        newType: EntityType,
        mentionSegmentIds: List<String>
    ): String? = withContext(Dispatchers.IO) {
        val trimmedName = newName.trim()
        if (mentionSegmentIds.isEmpty() || trimmedName.isEmpty()) return@withContext null
        val source = entityDao.getById(sourceId) ?: return@withContext null
        val selected = mentionDao.getByEntityId(sourceId).filter { it.sourceSegmentId in mentionSegmentIds }
        if (selected.isEmpty()) return@withContext null

        val newId = UUID.randomUUID().toString()
        db.runInTransaction {
            kotlinx.coroutines.runBlocking {
                entityDao.insert(Entity(
                    id = newId,
                    type = newType,
                    canonicalName = trimmedName,
                    aliasesJson = null,
                    normalizedName = trimmedName.lowercase(),
                    confidence = source.confidence,
                    userConfirmed = false
                ))
                selected.forEach { mention ->
                    mentionDao.delete(mention.entityId, mention.sourceSegmentId)
                    mentionDao.insert(mention.copy(entityId = newId))
                }
            }
        }
        newId
    }

    /**
     * Delete an entity along with its mentions and pending review items.
     * Source segments are untouched.
     */
    suspend fun deleteById(id: String) = withContext(Dispatchers.IO) {
        db.runInTransaction {
            kotlinx.coroutines.runBlocking {
                mentionDao.deleteByEntityId(id)
                reviewDao.deleteByEntityId(id)
                entityDao.deleteById(id)
            }
        }
    }

    private fun parseAliases(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            gson.fromJson<List<String>>(json, object : TypeToken<List<String>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeAliases(aliases: List<String>): String = gson.toJson(aliases)
}
