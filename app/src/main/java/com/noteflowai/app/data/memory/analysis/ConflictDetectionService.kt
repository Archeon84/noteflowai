package com.noteflowai.app.data.memory.analysis

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.noteflowai.app.data.memory.dao.CommitmentDao
import com.noteflowai.app.data.memory.dao.DecisionDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.Conflict
import com.noteflowai.app.data.memory.model.ConflictStatus
import com.noteflowai.app.data.memory.model.DecisionStatus
import com.noteflowai.app.data.memory.repository.ConflictRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Rule-based conflict detection for memory objects, decisions, and commitments.
 *
 * Detects:
 * - Decision conflicts: reversed/superseded decisions coexisting with active ones on similar topics
 * - Deadline conflicts: commitments with different due dates for similar actions
 * - Status conflicts: memory objects with contradictory statuses
 *
 * Uses conservative language: "Possible conflict", "These sources differ".
 */
class ConflictDetectionService(
    private val decisionDao: DecisionDao,
    private val commitmentDao: CommitmentDao,
    private val memoryObjectDao: MemoryObjectDao,
    private val conflictRepository: ConflictRepository
) {

    constructor(context: Context) : this(
        MemoryDatabase.getInstance(context).decisionDao(),
        MemoryDatabase.getInstance(context).commitmentDao(),
        MemoryDatabase.getInstance(context).memoryObjectDao(),
        ConflictRepository(context)
    )

    private val gson = Gson()

    companion object {
        private const val TAG = "ConflictDetectionService"

        /** Hoisted: was recompiled on every pairwise comparison. */
        private val WORD_SPLIT = Regex("\\W+")

        @Volatile
        private var INSTANCE: ConflictDetectionService? = null

        fun getInstance(context: Context): ConflictDetectionService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConflictDetectionService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Significant-word blocking keys. Pairwise loops only compare items
     * sharing a key (or a project), turning the full cross-product into
     * small buckets — the naive O(n²) word-overlap over entire tables blew
     * up CPU/memory on large libraries. Safe prefilter: every pair the old
     * similarity checks accepted shares a word (overlap needs ≥1) or a
     * project, so bucketing never drops a true positive.
     */
    private fun blockingKeys(text: String, projectId: String? = null): Set<String> {
        val keys = text.lowercase().split(WORD_SPLIT).filter { it.length >= 4 }.toMutableSet()
        if (projectId != null) keys.add("project:$projectId")
        return keys
    }

    private fun <T> blockByKeys(
        items: List<T>,
        idOf: (T) -> String,
        keysOf: (T) -> Set<String>
    ): Map<String, List<T>> {
        val blocks = mutableMapOf<String, MutableList<T>>()
        for (item in items) {
            for (key in keysOf(item)) {
                blocks.getOrPut(key) { mutableListOf() }.add(item)
            }
        }
        return blocks
    }

    /** Bucket union for one item, minus itself. Empty when it shares nothing. */
    private fun <T> candidatesFor(
        selfId: String,
        keys: Set<String>,
        blocks: Map<String, List<T>>,
        idOf: (T) -> String
    ): List<T> {
        if (keys.isEmpty()) return emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<T>()
        for (key in keys) {
            for (item in blocks[key].orEmpty()) {
                val id = idOf(item)
                if (id != selfId && seen.add(id)) {
                    out.add(item)
                }
            }
        }
        return out
    }

    /**
     * Canonical JSON for an id pair. `pendingCountFor` dedups on exact string
     * match, so the pair must be order-insensitive — otherwise the same two
     * objects in reverse loop/DB order evade dedup and stack a duplicate
     * PENDING conflict on every scan.
     */
    private fun stableIdPair(a: String, b: String): String =
        gson.toJson(listOf(a, b).sorted())

    private fun stableSegmentPair(a: String?, b: String?): String =
        gson.toJson(listOfNotNull(a, b).sorted())

    /**
     * Detect conflicts by scanning existing decisions and commitments.
     * Returns newly detected conflicts (not previously detected).
     */
    suspend fun detectConflicts(): List<Conflict> = withContext(Dispatchers.IO) {
        val detectedConflicts = mutableListOf<Conflict>()

        // Detect decision conflicts
        detectedConflicts.addAll(detectDecisionConflicts())

        // Detect deadline conflicts
        detectedConflicts.addAll(detectDeadlineConflicts())

        // Detect status conflicts
        detectedConflicts.addAll(detectStatusConflicts())

        // Persist new conflicts, skipping ones already pending to avoid
        // duplicates when detection runs repeatedly on unchanged data. Only
        // actually-persisted conflicts are returned, so callers counting the
        // return value do not over-report "new" conflicts on every run.
        val newlyDetected = mutableListOf<Conflict>()
        for (conflict in detectedConflicts) {
            try {
                val dupes = conflictRepository.pendingCountFor(conflict.conflictType, conflict.objectIds)
                if (dupes == 0) {
                    conflictRepository.insert(conflict)
                    newlyDetected.add(conflict)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist conflict: ${e.message}")
            }
        }

        newlyDetected
    }

    /**
     * Detect conflicts for a source during pipeline execution.
     * Runs conservative deterministic conflict detection across decisions and commitments.
     */
    suspend fun detectForSource(sourceId: String): List<Conflict> = withContext(Dispatchers.IO) {
        detectConflicts()
    }

    /**
     * Detect decisions that have been reversed or superseded while active decisions
     * on similar topics still exist.
     */
    private suspend fun detectDecisionConflicts(): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()

        val reversedDecisions = decisionDao.getByStatus(DecisionStatus.REVERSED)
        val supersededDecisions = decisionDao.getByStatus(DecisionStatus.SUPERSEDED)
        val activeDecisions = decisionDao.getActive()
        // Block active decisions by token once; each reversed/superseded row
        // only compares against buckets it shares a word (or project) with.
        val activeBlocks = blockByKeys(activeDecisions, { it.id }, { blockingKeys(it.statement, it.projectEntityId) })

        // Check reversed decisions against active ones
        for (reversed in reversedDecisions) {
            for (active in candidatesFor(reversed.id, blockingKeys(reversed.statement, reversed.projectEntityId), activeBlocks, { it.id })) {
                if (areRelatedDecisions(reversed, active)) {
                    val conflict = Conflict(
                        id = UUID.randomUUID().toString(),
                        conflictType = "DECISION_CONFLICT",
                        objectIds = stableIdPair(reversed.id, active.id),
                        sourceSegmentIds = stableSegmentPair(reversed.sourceSegmentId, active.sourceSegmentId),
                        firstObservedAt = minOf(reversed.createdAt, active.createdAt),
                        latestObservedAt = maxOf(reversed.updatedAt, active.updatedAt),
                        confidence = 0.7f,
                        status = ConflictStatus.PENDING
                    )
                    conflicts.add(conflict)
                }
            }
        }

        // Check superseded decisions against active ones
        for (superseded in supersededDecisions) {
            for (active in candidatesFor(superseded.id, blockingKeys(superseded.statement, superseded.projectEntityId), activeBlocks, { it.id })) {
                if (areRelatedDecisions(superseded, active)) {
                    val conflict = Conflict(
                        id = UUID.randomUUID().toString(),
                        conflictType = "DECISION_CONFLICT",
                        objectIds = stableIdPair(superseded.id, active.id),
                        sourceSegmentIds = stableSegmentPair(superseded.sourceSegmentId, active.sourceSegmentId),
                        firstObservedAt = minOf(superseded.createdAt, active.createdAt),
                        latestObservedAt = maxOf(superseded.updatedAt, active.updatedAt),
                        confidence = 0.8f,
                        status = ConflictStatus.PENDING
                    )
                    conflicts.add(conflict)
                }
            }
        }

        return conflicts
    }

    /**
     * Detect commitments with different due dates for similar actions/projects.
     */
    private suspend fun detectDeadlineConflicts(): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()

        val activeCommitments = commitmentDao.getActive()
        val overdueCommitments = commitmentDao.getOverdue()

        // Group active commitments by project
        val byProject = activeCommitments.filter { it.projectEntityId != null }
            .groupBy { it.projectEntityId }

        for ((projectId, commitments) in byProject) {
            if (commitments.size < 2) continue

            // Check for different due dates on same project
            val withDueDates = commitments.filter { it.dueAt != null }
            if (withDueDates.size >= 2) {
                val deadlines = withDueDates.map { it.dueAt!! }.distinct()
                if (deadlines.size > 1) {
                    // Sorted: the dedup key is order-sensitive JSON, so DB
                    // ordering must not produce a "new" conflict per run.
                    val orderedIds = withDueDates.map { it.id }.sorted()
                    val orderedSegments = withDueDates.map { it.sourceSegmentId }.sorted()
                    val conflict = Conflict(
                        id = UUID.randomUUID().toString(),
                        conflictType = "DEADLINE_CONFLICT",
                        objectIds = gson.toJson(orderedIds),
                        sourceSegmentIds = gson.toJson(orderedSegments),
                        firstObservedAt = withDueDates.minOf { it.createdAt },
                        latestObservedAt = withDueDates.maxOf { it.updatedAt },
                        confidence = 0.6f,
                        status = ConflictStatus.PENDING
                    )
                    conflicts.add(conflict)
                }
            }
        }

        // Check overdue vs not-overdue for same action keywords, blocked by
        // shared words instead of the full cross-product.
        val activeNotOverdue = activeCommitments.filter { it.dueAt == null || it.dueAt > System.currentTimeMillis() }
        val activeActionBlocks = blockByKeys(activeNotOverdue, { it.id }, { blockingKeys(it.action) })
        for (overdue in overdueCommitments) {
            for (active in candidatesFor(overdue.id, blockingKeys(overdue.action), activeActionBlocks, { it.id })) {
                if (areSimilarActions(overdue.action, active.action) && overdue.id != active.id) {
                    val conflict = Conflict(
                        id = UUID.randomUUID().toString(),
                        conflictType = "DEADLINE_CONFLICT",
                        objectIds = stableIdPair(overdue.id, active.id),
                        sourceSegmentIds = stableSegmentPair(overdue.sourceSegmentId, active.sourceSegmentId),
                        firstObservedAt = minOf(overdue.createdAt, active.createdAt),
                        latestObservedAt = maxOf(overdue.updatedAt, active.updatedAt),
                        confidence = 0.5f,
                        status = ConflictStatus.PENDING
                    )
                    conflicts.add(conflict)
                }
            }
        }

        return conflicts
    }

    /**
     * Detect memory objects with contradictory statuses for similar content.
     */
    private suspend fun detectStatusConflicts(): List<Conflict> {
        val conflicts = mutableListOf<Conflict>()

        // Get completed and live memory objects of same type. Nothing in the
        // codebase ever sets ACTIVE (all confirm paths set CONFIRMED), so
        // comparing against ACTIVE alone made this detector dead in steady
        // state. Include both so real contradictions are flagged.
        val completedObjects = memoryObjectDao.getByStatuses(
            listOf(com.noteflowai.app.data.memory.model.MemoryObjectStatus.COMPLETED)
        )
        val activeObjects = memoryObjectDao.getByStatuses(
            listOf(
                com.noteflowai.app.data.memory.model.MemoryObjectStatus.CONFIRMED,
                com.noteflowai.app.data.memory.model.MemoryObjectStatus.ACTIVE
            )
        )
        val activeStatementBlocks = blockByKeys(activeObjects, { it.id }, { blockingKeys(it.statement) })

        for (completed in completedObjects) {
            for (active in candidatesFor(completed.id, blockingKeys(completed.statement), activeStatementBlocks, { it.id })) {
                if (completed.type == active.type &&
                    areSimilarStatements(completed.statement, active.statement) &&
                    completed.id != active.id
                ) {
                    val conflict = Conflict(
                        id = UUID.randomUUID().toString(),
                        conflictType = "STATUS_CONFLICT",
                        objectIds = stableIdPair(completed.id, active.id),
                        sourceSegmentIds = stableSegmentPair(completed.sourceSegmentId, active.sourceSegmentId),
                        firstObservedAt = minOf(completed.extractedAt, active.extractedAt),
                        latestObservedAt = maxOf(completed.extractedAt, active.extractedAt),
                        confidence = 0.5f,
                        status = ConflictStatus.PENDING
                    )
                    conflicts.add(conflict)
                }
            }
        }

        return conflicts
    }

    /**
     * Simple heuristic to check if two decisions are related.
     * Checks project match or statement word overlap.
     */
    private fun areRelatedDecisions(a: com.noteflowai.app.data.memory.model.Decision, b: com.noteflowai.app.data.memory.model.Decision): Boolean {
        // Same project
        if (a.projectEntityId != null && a.projectEntityId == b.projectEntityId) return true
        // Statement word overlap
        return areSimilarStatements(a.statement, b.statement)
    }

    /**
     * Check if two action strings are similar (simple word overlap).
     */
    private fun areSimilarActions(a: String, b: String): Boolean {
        val wordsA = a.lowercase().split(WORD_SPLIT).filter { it.length > 3 }.toSet()
        val wordsB = b.lowercase().split(WORD_SPLIT).filter { it.length > 3 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val overlap = wordsA.intersect(wordsB).size
        return overlap >= minOf(wordsA.size, wordsB.size) * 0.5
    }

    /**
     * Check if two statements are similar (simple word overlap).
     */
    private fun areSimilarStatements(a: String, b: String): Boolean {
        val wordsA = a.lowercase().split(WORD_SPLIT).filter { it.length > 3 }.toSet()
        val wordsB = b.lowercase().split(WORD_SPLIT).filter { it.length > 3 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val overlap = wordsA.intersect(wordsB).size
        return overlap >= minOf(wordsA.size, wordsB.size) * 0.4
    }

    suspend fun resolveConflict(conflictId: String, resolution: String) {
        conflictRepository.resolveConflict(conflictId, resolution)
    }

    suspend fun dismissConflict(conflictId: String) {
        conflictRepository.dismissConflict(conflictId)
    }

    suspend fun confirmConflict(conflictId: String) {
        conflictRepository.confirmConflict(conflictId)
    }
}
