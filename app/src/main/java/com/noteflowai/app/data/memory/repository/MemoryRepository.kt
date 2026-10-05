package com.noteflowai.app.data.memory.repository

import android.content.Context
import com.noteflowai.app.data.memory.dao.*
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.db.MemoryDatabaseModule
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.model.ProcessingStage
import com.noteflowai.app.data.memory.model.ProcessingState
import com.noteflowai.app.data.memory.model.ProcessingStatus
import com.noteflowai.app.data.memory.model.SourceType
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MemoryRepository(
    private val db: MemoryDatabase
) {
    constructor(context: Context) : this(MemoryDatabaseModule.getDatabase(context))

    private val dao: MemoryObjectDao = db.memoryObjectDao()
    private val segmentDao: SourceSegmentDao = db.sourceSegmentDao()
    private val mentionDao: EntityMentionDao = db.entityMentionDao()
    private val timelineDao: TimelineEntryDao = db.timelineEntryDao()
    private val decisionDao: DecisionDao = db.decisionDao()
    private val commitmentDao: CommitmentDao = db.commitmentDao()
    private val relationDao: MemoryRelationDao = db.memoryRelationDao()
    private val reviewDao: MemoryReviewItemDao = db.memoryReviewItemDao()
    private val citationDao: AnswerCitationDao = db.answerCitationDao()
    private val conflictDao: ConflictDao = db.conflictDao()
    private val statusDao: ProcessingStatusDao = db.processingStatusDao()
    private val entityDao: EntityDao = db.entityDao()

    suspend fun insert(memoryObject: MemoryObject) = withContext(Dispatchers.IO) {
        dao.insert(memoryObject)
    }

    suspend fun update(memoryObject: MemoryObject) = withContext(Dispatchers.IO) {
        dao.update(memoryObject)
    }

    suspend fun getById(id: String): MemoryObject? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    suspend fun getBySourceSegmentId(sourceSegmentId: String): List<MemoryObject> = withContext(Dispatchers.IO) {
        dao.getBySourceSegmentId(sourceSegmentId)
    }

    /**
     * Re-extraction dedup: the same normalized statement on the same segment
     * already exists, so the extractor must skip it instead of stacking a
     * duplicate decision/commitment/review item.
     */
    suspend fun getBySegmentAndStatement(
        sourceSegmentId: String,
        normalizedStatement: String
    ): MemoryObject? = withContext(Dispatchers.IO) {
        dao.getBySegmentAndStatement(sourceSegmentId, normalizedStatement)
    }

    suspend fun getBySourceId(sourceId: String): List<MemoryObject> = withContext(Dispatchers.IO) {
        dao.getBySourceId(sourceId)
    }

    suspend fun getDetected(): List<MemoryObject> = withContext(Dispatchers.IO) {
        dao.getDetected()
    }

    suspend fun getByTypeAndStatus(type: MemoryType, status: MemoryObjectStatus): List<MemoryObject> = withContext(Dispatchers.IO) {
        dao.getByTypeAndStatus(type, status)
    }

    suspend fun getByDateRange(startMs: Long, endMs: Long): List<MemoryObject> = withContext(Dispatchers.IO) {
        dao.getByDateRange(startMs, endMs)
    }

    /**
     * Build a map of note fileName -> set of extracted entity ids, for note-to-note
     * linking. NOTE source segments are keyed by note fileName (see NoteBlockAdapter),
     * so we join source_segments -> entity_mentions to find which entities each note
     * references. Returns an empty map when the memory layer has no note data.
     */
    suspend fun getNoteEntityMap(): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        val noteSegments = segmentDao.getBySourceType(SourceType.NOTE)
        if (noteSegments.isEmpty()) return@withContext emptyMap()

        // One batched mention query instead of one per segment (N+1). The
        // join excludes REJECTED mentions and REJECTED entities, so a rejected
        // link never connects two notes in the AutoLinker graph (guide §Phase 4).
        val segmentSource = noteSegments.associate { it.id to it.sourceId }
        val mentions = mentionDao.getActiveBySegmentIds(noteSegments.map { it.id })
        val noteToEntities = mutableMapOf<String, MutableSet<String>>()
        for (mention in mentions) {
            val sourceId = segmentSource[mention.sourceSegmentId] ?: continue
            noteToEntities.getOrPut(sourceId) { mutableSetOf() }.add(mention.entityId)
        }
        noteToEntities.mapValues { it.value.toSet() }
    }

    suspend fun confirm(memoryObjectId: String) = withContext(Dispatchers.IO) {
        val obj = dao.getById(memoryObjectId) ?: return@withContext
        dao.update(obj.copy(
            status = MemoryObjectStatus.CONFIRMED,
            confirmedAt = System.currentTimeMillis()
        ))
    }

    suspend fun cancel(memoryObjectId: String) = withContext(Dispatchers.IO) {
        val obj = dao.getById(memoryObjectId) ?: return@withContext
        dao.update(obj.copy(status = MemoryObjectStatus.CANCELLED))
    }

    suspend fun deleteBySourceId(sourceId: String) = withContext(Dispatchers.IO) {
        dao.deleteBySourceId(sourceId)
    }

    suspend fun deleteBySourceSegmentId(sourceSegmentId: String) = withContext(Dispatchers.IO) {
        dao.deleteBySourceSegmentId(sourceSegmentId)
    }

    /**
     * Purges all derived memory data (segments, mentions, unconfirmed entities, timeline,
     * memory objects, decisions, commitments, relations, reviews, citations, conflicts, status)
     * while preserving raw user markdown files and recordings.
     */
    suspend fun purgeDerivedMemoryData() = withContext(Dispatchers.IO) {
        db.withTransaction {
            segmentDao.clearAll()
            mentionDao.clearAll()
            entityDao.clearUnconfirmed()
            timelineDao.clearAll()
            dao.clearAll()
            decisionDao.clearAll()
            commitmentDao.clearAll()
            relationDao.clearAll()
            reviewDao.clearAll()
            citationDao.clearAll()
            conflictDao.clearAll()
            statusDao.clearAll()
        }
    }

    /**
     * Deletes all derived data for a specific source (e.g. note or recording).
     *
     * Runs atomically in a single transaction: either every table is cleaned or
     * none is, so a crash mid-delete can never leave a half-deleted source.
     *
     * Ordering is load-bearing: child tables whose deletes are expressed as
     * `sourceSegmentId IN (SELECT id FROM source_segments ...)` MUST be deleted
     * while the segments still exist — deleting segments first turns those
     * deletes into silent no-ops and orphans the rows. Segments go last.
     * Shared entities are intentionally left alone (other sources may reference
     * them); only this source's mentions/relations are removed.
     */
    suspend fun deleteDerivedDataForSource(sourceId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            deleteDerivedDataTx(sourceId)
        }
    }

    /**
     * Full derived-data wipe for a rebuild, with one exception: the pipeline
     * attempt counter is preserved. A plain delete would reset attempts to 0,
     * letting a poison source loop forever across rebuilds instead of tripping
     * `MAX_ATTEMPTS` and going `FAILED_PERMANENT`. The re-inserted PENDING row
     * resumes from the first stage on the next `processSource` call.
     */
    suspend fun resetSourceForRebuild(sourceId: String, sourceType: SourceType) =
        withContext(Dispatchers.IO) {
            val priorAttempts = statusDao.getBySourceId(sourceId)?.attempts ?: 0
            db.withTransaction {
                deleteDerivedDataTx(sourceId)
                if (priorAttempts > 0) {
                    statusDao.upsert(
                        ProcessingStatus(
                            sourceId = sourceId,
                            sourceType = sourceType,
                            currentStage = ProcessingStage.SEGMENTS_CREATED,
                            status = ProcessingState.PENDING,
                            attempts = priorAttempts
                        )
                    )
                }
            }
        }

    private suspend fun deleteDerivedDataTx(sourceId: String) {
        // Collect ids needed for JSON-keyed tables before deleting anything.
        val segmentIds = segmentDao.getBySourceId(sourceId).map { it.id }
        val derivedObjectIds =
            decisionDao.getIdsBySourceId(sourceId) +
                commitmentDao.getIdsBySourceId(sourceId) +
                dao.getIdsBySourceId(sourceId)

        // Children first (segment-subquery deletes require segments present).
        mentionDao.deleteBySourceId(sourceId)
        decisionDao.deleteBySourceId(sourceId)
        commitmentDao.deleteBySourceId(sourceId)
        relationDao.deleteBySourceId(sourceId)
        citationDao.deleteBySourceId(sourceId)
        timelineDao.deleteBySourceId(sourceId)
        dao.deleteBySourceId(sourceId)
        if (derivedObjectIds.isNotEmpty()) {
            reviewDao.deleteByObjectIds(derivedObjectIds)
        }
        for (segmentId in segmentIds) {
            // LIKE wildcards in system-generated IDs (`_`) must be escaped
            // or the DELETE over-matches sibling segments' conflicts.
            conflictDao.deleteBySourceSegmentId(escapeLike(segmentId))
        }
        statusDao.deleteBySourceId(sourceId)
        // Segments last.
        segmentDao.deleteBySourceId(sourceId)
    }
}
