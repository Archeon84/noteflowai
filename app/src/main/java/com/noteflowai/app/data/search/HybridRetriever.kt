package com.noteflowai.app.data.search

import android.content.Context
import android.util.Log
import com.noteflowai.app.data.memory.model.Commitment
import com.noteflowai.app.data.memory.model.ConflictStatus
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.DecisionStatus
import com.noteflowai.app.data.memory.model.Entity
import com.noteflowai.app.data.memory.model.EntityMention
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.pipeline.SegmentEmbeddingService
import com.noteflowai.app.data.memory.repository.CommitmentRepository
import com.noteflowai.app.data.memory.repository.DecisionRepository
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.repository.MemoryRepository
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.memory.timeline.TimelineRepository
import com.noteflowai.app.data.search.EmbeddingIndex
import com.noteflowai.app.data.search.NoteSearchIndex
import com.noteflowai.app.data.search.OnDeviceEmbedder
import com.noteflowai.app.data.search.RemoteEmbeddingClient

/**
 * Multi-source retrieval orchestrator for the Query Planner.
 * Combines BM25, embeddings, and structured memory DB retrieval.
 * Uses Reciprocal Rank Fusion to merge note channels, then per-source min-max
 * normalization and weighted fusion (guide §Phase 5) with threshold rejection;
 * every retrieve() returns a [RetrievalOutcome] carrying the ranked results,
 * a short-circuit reason, and Filter & Eval Gate telemetry (populated in Task 5).
 */
class HybridRetriever(
    private val context: Context,
    private val noteSearchIndex: NoteSearchIndex,
    private val embeddingIndex: EmbeddingIndex,
    private val remoteEmbeddingClient: RemoteEmbeddingClient,
    private val onDeviceEmbedder: OnDeviceEmbedder,
    private val sourceSegmentRepository: SourceSegmentRepository,
    private val decisionRepository: DecisionRepository,
    private val commitmentRepository: CommitmentRepository,
    private val memoryRepository: MemoryRepository,
    private val entityRepository: EntityRepository,
    private val segmentEmbeddingService: SegmentEmbeddingService,
    private val timelineRepository: TimelineRepository,
    private val retrievalConfig: RetrievalConfig = RetrievalConfig(),
    private val noteContentResolver: (suspend (String) -> String?)? = null
) {
    companion object {
        private const val TAG = "HybridRetriever"
        private const val BM25_MAX = 30
        private const val EMBEDDING_MAX = 30
        private const val RRF_K = 60
        private const val RRF_MERGED_MAX = 40
        private const val FILTERED_MAX = 20

        /**
         * Penalty applied to fused scores that derive ONLY from degenerate
         * channels (a channel present on a single result, or equal across all
         * results, carries no comparative information — min-max maps it to 0.5
         * regardless of absolute quality). A lone weak candidate (e.g. one
         * substring segment hit, one low-cosine embedding hit) otherwise fuses
         * to ~0.5 and clears the default 0.28 threshold, promoting unrelated
         * notes to source chips. 0.5 × 0.5 = 0.25 < 0.28 rejects them while
         * results with at least one competitive channel are untouched.
         */
        private const val LONE_CANDIDATE_FACTOR = 0.5f

        /**
         * Discount for note results whose source has no segments and therefore
         * skipped the Filter & Eval Gate (no rows to check dates/types against).
         * 0.7 keeps genuinely strong title matches (fused ~0.9 → 0.63, still
         * clearing the 0.28 floor comfortably) while weak filter-evading
         * stragglers (~0.4 → 0.28) sit at the admission border instead of
         * outranking gate-verified evidence.
         */
        private const val UNSEGMENTED_PENALTY = 0.7f
    }

    /**
     * Retrieve results based on the query plan.
     */
    suspend fun retrieve(
        plan: QueryPlan,
        embeddingQuery: String,
        embeddingEnabled: Boolean,
        embeddingModel: String,
        provider: String,
        apiKey: String,
        baseUrl: String,
        // Local-Only Mode: skip the remote embedding fallback so query text
        // never leaves the device (the OkHttp firewall is a second layer, but
        // it is only installed after MainViewModel runs — this fails closed
        // even for background callers).
        localOnly: Boolean = false
    ): RetrievalOutcome {
        return try {
            when (plan.intent) {
                QueryIntent.DECISION_LOOKUP -> RetrievalOutcome(finishIntent(retrieveDecisions(plan)))
                QueryIntent.COMMITMENT_LOOKUP -> RetrievalOutcome(finishIntent(retrieveCommitments(plan)))
                QueryIntent.ENTITY_LOOKUP -> RetrievalOutcome(
                    finishIntent(retrieveEntities(plan, embeddingQuery, embeddingEnabled, embeddingModel, provider, apiKey, baseUrl, localOnly))
                )
                QueryIntent.PROJECT_LOOKUP -> RetrievalOutcome(
                    finishIntent(retrieveProjects(plan, embeddingQuery, embeddingEnabled, embeddingModel, provider, apiKey, baseUrl, localOnly))
                )
                QueryIntent.CHANGE_ANALYSIS -> RetrievalOutcome(finishIntent(retrieveChangeAnalysis(plan)))
                QueryIntent.CONFLICT_ANALYSIS -> RetrievalOutcome(
                    retrieveConflicts(plan, embeddingQuery, embeddingEnabled, embeddingModel, provider, apiKey, baseUrl, localOnly)
                )
                QueryIntent.SOURCE_SUMMARY -> RetrievalOutcome(
                    finishIntent(retrieveSourceSummary(plan, embeddingQuery, embeddingEnabled, embeddingModel, provider, apiKey, baseUrl, localOnly))
                )
                else -> retrieveGeneral(plan, embeddingQuery, embeddingEnabled, embeddingModel, provider, apiKey, baseUrl, localOnly)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Retrieval failed for intent ${plan.intent}: ${e.message}", e)
            RetrievalOutcome(emptyList())
        }
    }

    /**
     * Intent-path post-processing: clamp the hand-rolled confidence boosts
     * into the RetrievalResult [0,1] contract (1.5x/1.4x/1.3x multipliers
     * used to exceed it), order by score, and apply the configured topK
     * under the shared result cap.
     */
    private fun finishIntent(results: List<RetrievalResult>): List<RetrievalResult> =
        results.map { it.copy(score = it.score.coerceIn(0f, 1f)) }
            .sortedByDescending { it.score }
            .take(retrievalConfig.topK.coerceAtMost(FILTERED_MAX))

    // ── Intent-specific retrieval strategies ──────────────────────

    private suspend fun retrieveDecisions(plan: QueryPlan): List<RetrievalResult> {
        val results = mutableListOf<RetrievalResult>()

        // One getActive() per query, partitioned in memory — the old code hit
        // the table three times for the same rows.
        var activeCache: List<Decision>? = null
        suspend fun active(): List<Decision> {
            if (activeCache == null) {
                activeCache = decisionRepository.getActive()
            }
            return activeCache!!
        }
        // Get decisions: confirmed/active first, then optionally unconfirmed
        val decisions = if (plan.dateFrom != null && plan.dateTo != null) {
            decisionRepository.getByDateRange(plan.dateFrom, plan.dateTo)
        } else {
            active()
        }

        // Batch-fetch segments once instead of one Room query per decision.
        val decisionIds = decisions.map { it.sourceSegmentId }.toMutableList()
        if (plan.includeUnconfirmed) {
            decisionIds.addAll(
                active()
                    .filter { it.status == DecisionStatus.DETECTED }
                    .map { it.sourceSegmentId }
            )
        }
        val segments = segmentsByIds(decisionIds)

        for ((rank, decision) in decisions.withIndex()) {
            val segment = segments[decision.sourceSegmentId]
            results.add(
                RetrievalResult(
                    sourceSegmentId = decision.sourceSegmentId,
                    memoryObjectId = decision.id,
                    sourceId = segment?.sourceId ?: decision.sourceSegmentId,
                    text = buildString {
                        append(decision.statement)
                        decision.reason?.let { append(". Reason: $it") }
                    },
                    sourceType = segment?.sourceType,
                    score = decision.confidence * 1.5f, // Boost confirmed decisions
                    rank = rank,
                    startMs = segment?.startMs,
                    endMs = segment?.endMs,
                    pageNumber = segment?.pageNumber,
                    metadata = mapOf(
                        "type" to "decision",
                        "status" to decision.status.name,
                        "decidedAt" to (decision.decidedAt?.toString() ?: "")
                    )
                )
            )
        }

        // Include unconfirmed if requested (reuses the single fetched list).
        if (plan.includeUnconfirmed) {
            val detected = active().filter {
                it.status == DecisionStatus.DETECTED
            }
            for ((i, decision) in detected.withIndex()) {
                if (decision.id !in results.mapNotNull { it.memoryObjectId }) {
                    val segment = segments[decision.sourceSegmentId]
                    results.add(
                        RetrievalResult(
                            sourceSegmentId = decision.sourceSegmentId,
                            memoryObjectId = decision.id,
                            sourceId = segment?.sourceId ?: decision.sourceSegmentId,
                            text = decision.statement,
                            sourceType = segment?.sourceType,
                            score = decision.confidence * 0.8f, // Lower score for unconfirmed
                            rank = decisions.size + i,
                            metadata = mapOf(
                                "type" to "decision",
                                "status" to "DETECTED",
                                "unconfirmed" to "true"
                            )
                        )
                    )
                }
            }
        }

        return results.take(FILTERED_MAX)
    }

    private suspend fun retrieveCommitments(plan: QueryPlan): List<RetrievalResult> {
        val results = mutableListOf<RetrievalResult>()

        // Active + overdue commitments
        val active = commitmentRepository.getActive()
        val overdue = commitmentRepository.getOverdue()
        val allCommitments = (active + overdue).distinctBy { it.id }

        val segments = segmentsByIds(allCommitments.map { it.sourceSegmentId })
        for ((rank, commitment) in allCommitments.withIndex()) {
            val segment = segments[commitment.sourceSegmentId]
            val isOverdue = commitment.status.name == "OVERDUE"
            results.add(
                RetrievalResult(
                    sourceSegmentId = commitment.sourceSegmentId,
                    memoryObjectId = commitment.id,
                    sourceId = segment?.sourceId ?: commitment.sourceSegmentId,
                    text = buildString {
                        append(commitment.action)
                        commitment.ownerText?.let { append(" (Owner: $it)") }
                        commitment.dueAt?.let { append(" (Due: $it)") }
                    },
                    sourceType = segment?.sourceType,
                    score = commitment.confidence * if (isOverdue) 1.4f else 1.2f,
                    rank = rank,
                    startMs = segment?.startMs,
                    endMs = segment?.endMs,
                    pageNumber = segment?.pageNumber,
                    metadata = mapOf(
                        "type" to "commitment",
                        "status" to commitment.status.name,
                        "owner" to (commitment.ownerText ?: ""),
                        "dueAt" to (commitment.dueAt?.toString() ?: ""),
                        "isOverdue" to isOverdue.toString()
                    )
                )
            )
        }

        return results.take(FILTERED_MAX)
    }

    private suspend fun retrieveEntities(
        plan: QueryPlan,
        embeddingQuery: String,
        embeddingEnabled: Boolean,
        embeddingModel: String,
        provider: String,
        apiKey: String,
        baseUrl: String,
        localOnly: Boolean = false
    ): List<RetrievalResult> {
        val results = mutableListOf<RetrievalResult>()

        // Collect candidate mentions first so all segment lookups batch into one query.
        data class MentionCandidate(val entity: Entity, val mention: EntityMention)
        val candidates = mutableListOf<MentionCandidate>()
        for (entityName in plan.entities) {
            // searchActive + getActiveMentions exclude REJECTED entities/mentions so a
            // rejected entity or link never affects retrieval (guide §Phase 4).
            val entities = entityRepository.searchActive(entityName, limit = 5)
            for (entity in entities) {
                entityRepository.getActiveMentions(entity.id).take(3).forEach { mention ->
                    candidates.add(MentionCandidate(entity, mention))
                }
            }
        }

        val segments = segmentsByIds(candidates.map { it.mention.sourceSegmentId })
        for (candidate in candidates) {
            val segment = segments[candidate.mention.sourceSegmentId]
            if (segment != null) {
                results.add(
                    RetrievalResult(
                        sourceSegmentId = segment.id,
                        sourceId = segment.sourceId,
                        text = segment.text,
                        sourceType = segment.sourceType,
                        score = candidate.mention.confidence * 1.3f,
                        rank = results.size,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        pageNumber = segment.pageNumber,
                        metadata = mapOf(
                            "type" to "entity_mention",
                            "entityName" to candidate.entity.canonicalName,
                            "entityType" to candidate.entity.type.name,
                            "mentionText" to candidate.mention.mentionText
                        )
                    )
                )
            }
        }

        // Fallback to note search if no entity results — with the real
        // embedding flags, so the semantic channel survives the fallback.
        if (results.isEmpty()) {
            return retrieveGeneral(plan, plan.queryText, embeddingEnabled, embeddingModel, provider, apiKey, baseUrl, localOnly).results
        }

        return results.take(FILTERED_MAX)
    }

    private suspend fun retrieveProjects(
        plan: QueryPlan,
        embeddingQuery: String,
        embeddingEnabled: Boolean,
        embeddingModel: String,
        provider: String,
        apiKey: String,
        baseUrl: String,
        localOnly: Boolean = false
    ): List<RetrievalResult> {
        val results = mutableListOf<RetrievalResult>()

        // Search for project entities (non-rejected only — rejected entities never surface)
        val projectEntities = entityRepository.searchActive(plan.queryText, limit = 10)
            .filter { it.type.name == "PROJECT" }

        // Fetch each entity's rows once and reuse them below — the old code
        // queried getByProject twice per entity (collect, then re-query).
        val decisionsByProject = mutableMapOf<String, List<Decision>>()
        val commitmentsByProject = mutableMapOf<String, List<Commitment>>()
        val referencedIds = mutableListOf<String>()
        for (entity in projectEntities) {
            val decisions = decisionRepository.getByProject(entity.id)
            val commitments = commitmentRepository.getByProject(entity.id)
            decisionsByProject[entity.id] = decisions
            commitmentsByProject[entity.id] = commitments
            referencedIds.addAll(decisions.take(3).map { it.sourceSegmentId })
            referencedIds.addAll(commitments.take(3).map { it.sourceSegmentId })
        }
        val segments = segmentsByIds(referencedIds)

        for (entity in projectEntities) {
            // Get decisions for this project
            val decisions = decisionsByProject.getValue(entity.id)
            for (decision in decisions.take(3)) {
                val segment = segments[decision.sourceSegmentId]
                results.add(
                    RetrievalResult(
                        sourceSegmentId = decision.sourceSegmentId,
                        memoryObjectId = decision.id,
                        sourceId = segment?.sourceId ?: decision.sourceSegmentId,
                        text = decision.statement,
                        sourceType = segment?.sourceType,
                        score = decision.confidence * 1.2f,
                        rank = results.size,
                        metadata = mapOf(
                            "type" to "project_decision",
                            "projectName" to entity.canonicalName
                        )
                    )
                )
            }

            // Get commitments for this project
            val commitments = commitmentsByProject.getValue(entity.id)
            for (commitment in commitments.take(3)) {
                val segment = segments[commitment.sourceSegmentId]
                results.add(
                    RetrievalResult(
                        sourceSegmentId = commitment.sourceSegmentId,
                        memoryObjectId = commitment.id,
                        sourceId = segment?.sourceId ?: commitment.sourceSegmentId,
                        text = commitment.action,
                        sourceType = segment?.sourceType,
                        score = commitment.confidence,
                        rank = results.size,
                        metadata = mapOf(
                            "type" to "project_commitment",
                            "projectName" to entity.canonicalName
                        )
                    )
                )
            }
        }

        // Fallback to general search if no project results — with the real
        // embedding flags, so the semantic channel survives the fallback.
        if (results.isEmpty()) {
            return retrieveGeneral(plan, plan.queryText, embeddingEnabled, embeddingModel, provider, apiKey, baseUrl, localOnly).results
        }

        return results.take(FILTERED_MAX)
    }

    private suspend fun retrieveChangeAnalysis(plan: QueryPlan): List<RetrievalResult> {
        val results = mutableListOf<RetrievalResult>()

        // Get chronological decision timeline
        val timeline = decisionRepository.getTimeline()
        val segments = segmentsByIds(timeline.map { it.sourceSegmentId })
        for ((rank, decision) in timeline.withIndex()) {
            val segment = segments[decision.sourceSegmentId]
            results.add(
                RetrievalResult(
                    sourceSegmentId = decision.sourceSegmentId,
                    memoryObjectId = decision.id,
                    sourceId = segment?.sourceId ?: decision.sourceSegmentId,
                    text = decision.statement,
                    sourceType = segment?.sourceType,
                    score = 1.0f, // Equal weight for timeline
                    rank = rank,
                    startMs = segment?.startMs,
                    metadata = mapOf(
                        "type" to "timeline_decision",
                        "decidedAt" to (decision.decidedAt?.toString() ?: ""),
                        "status" to decision.status.name
                    )
                )
            )
        }

        // Also get notes in date range if specified
        if (plan.dateFrom != null && plan.dateTo != null) {
            val memoryObjects = memoryRepository.getByDateRange(plan.dateFrom, plan.dateTo).take(10)
            val memorySegments = segmentsByIds(memoryObjects.map { it.sourceSegmentId })
            // Hoisted dedup set: timeline decisions already added their memoryObjectIds.
            val usedObjectIds = results.mapNotNull { it.memoryObjectId }.toMutableSet()
            for (obj in memoryObjects) {
                val segment = memorySegments[obj.sourceSegmentId]
                if (segment != null && obj.id !in usedObjectIds) {
                    usedObjectIds.add(obj.id)
                    results.add(
                        RetrievalResult(
                            sourceSegmentId = obj.sourceSegmentId,
                            memoryObjectId = obj.id,
                            sourceId = obj.sourceId,
                            text = obj.statement,
                            sourceType = segment.sourceType,
                            score = 0.8f,
                            rank = results.size,
                            metadata = mapOf(
                                "type" to "timeline_memory",
                                "memoryType" to obj.type.name,
                                "extractedAt" to obj.extractedAt.toString()
                            )
                        )
                    )
                }
            }
        }

        return results.take(FILTERED_MAX)
    }

    private suspend fun retrieveConflicts(
        plan: QueryPlan,
        embeddingQuery: String,
        embeddingEnabled: Boolean,
        embeddingModel: String,
        provider: String,
        apiKey: String,
        baseUrl: String,
        localOnly: Boolean = false
    ): List<RetrievalResult> {
        val conflictDao = com.noteflowai.app.data.memory.db.MemoryDatabase.getInstance(context).conflictDao()
        val conflicts = (conflictDao.getPending() + conflictDao.getByStatus(ConflictStatus.CONFIRMED))
            .distinctBy { it.id }

        if (conflicts.isEmpty()) {
            return retrieveGeneral(plan, embeddingQuery, embeddingEnabled, embeddingModel, provider, apiKey, baseUrl, localOnly).results
        }

        val results = mutableListOf<RetrievalResult>()
        for ((rank, conflict) in conflicts.withIndex()) {
            val segmentIds = runCatching {
                com.google.gson.Gson().fromJson(conflict.sourceSegmentIds, Array<String>::class.java)?.toList() ?: emptyList()
            }.getOrDefault(emptyList())
            val representativeSegmentId = segmentIds.firstOrNull() ?: continue
            val segment = segmentsByIds(listOf(representativeSegmentId))[representativeSegmentId]
            results.add(
                RetrievalResult(
                    sourceSegmentId = representativeSegmentId,
                    sourceId = segment?.sourceId ?: representativeSegmentId,
                    text = "Conflict (${conflict.conflictType}): sources differ on this point.",
                    sourceType = segment?.sourceType,
                    score = conflict.confidence * 1.1f,
                    rank = rank,
                    metadata = mapOf(
                        "type" to "conflict",
                        "conflictType" to conflict.conflictType,
                        "status" to conflict.status.name,
                        "firstObservedAt" to conflict.firstObservedAt.toString(),
                        "latestObservedAt" to conflict.latestObservedAt.toString()
                    )
                )
            )
        }
        return results.take(FILTERED_MAX)
    }

    private suspend fun retrieveSourceSummary(
        plan: QueryPlan,
        embeddingQuery: String,
        embeddingEnabled: Boolean,
        embeddingModel: String,
        provider: String,
        apiKey: String,
        baseUrl: String,
        localOnly: Boolean = false
    ): List<RetrievalResult> {
        val results = mutableListOf<RetrievalResult>()

        for (sourceType in plan.sourceTypes) {
            val segments = sourceSegmentRepository.getBySourceType(sourceType)
            for ((rank, segment) in segments.withIndex()) {
                results.add(
                    RetrievalResult(
                        sourceSegmentId = segment.id,
                        sourceId = segment.sourceId,
                        text = segment.text,
                        sourceType = segment.sourceType,
                        score = 1.0f,
                        rank = rank,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        pageNumber = segment.pageNumber,
                        metadata = mapOf(
                            "type" to "source_summary",
                            "sourceType" to sourceType.name
                        )
                    )
                )
            }
        }

        // Fallback to general search if no source type results — with the
        // real embedding flags, so the semantic channel survives the fallback.
        if (results.isEmpty()) {
            return retrieveGeneral(plan, plan.queryText, embeddingEnabled, embeddingModel, provider, apiKey, baseUrl, localOnly).results
        }

        return results.take(FILTERED_MAX)
    }

    // ── General retrieval with RRF fusion ─────────────────────────

    private suspend fun retrieveGeneral(
        plan: QueryPlan,
        embeddingQuery: String,
        embeddingEnabled: Boolean,
        embeddingModel: String,
        provider: String,
        apiKey: String,
        baseUrl: String,
        localOnly: Boolean = false
    ): RetrievalOutcome {
        val results = mutableListOf<RetrievalResult>()
        val segmentCandidates = mutableListOf<SourceSegment>()

        // 1. BM25 search
        val bm25Results = try {
            noteSearchIndex.search(plan.queryText, maxResults = BM25_MAX)
        } catch (e: Exception) {
            Log.w(TAG, "BM25 search failed: ${e.message}")
            emptyList()
        }

        // 2. Embedding search (if enabled) — on-device first, remote fallback.
        // The query vector is reused below for segment-level semantic search.
        // Local-Only Mode disables the remote fallback: query text must not
        // leave the device (BM25 + on-device vectors still apply).
        var queryVector: FloatArray? = null
        val embeddingResults = if (embeddingEnabled && embeddingQuery.isNotBlank()) {
            try {
                queryVector = onDeviceEmbedder.embed(embeddingQuery)
                if (queryVector == null && !localOnly) {
                    queryVector = remoteEmbeddingClient.embed(embeddingQuery, provider, apiKey, baseUrl, embeddingModel)
                } else if (queryVector == null) {
                    Log.i(TAG, "Local-Only Mode: skipping remote embedding fallback")
                }

                if (queryVector != null) {
                    embeddingIndex.search(queryVector!!, maxResults = EMBEDDING_MAX)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Embedding search failed: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }

        // 3. Reciprocal Rank Fusion
        val merged = reciprocalRankFusion(bm25Results, embeddingResults)

        // 4. Convert to RetrievalResult, carrying the per-channel raw scores through
        // so the weighted fusion and score normalization operate on calibrated metrics
        // (cosine similarity and BM25 score) while RRF dictates candidate selection. Blank-text
        // notes stay in the candidate list (trace/short-circuit fidelity);
        // the prompt boundary (PromptAssembler) drops them so a bare fileName
        // is never cited as evidence.
        for ((rank, pair) in merged.withIndex()) {
            val (fileName, ch) = pair
            val content = noteContentResolver?.invoke(fileName) ?: ""
            results.add(
                RetrievalResult(
                    sourceSegmentId = "note_$fileName",
                    sourceId = fileName,
                    text = content,
                    sourceType = SourceType.NOTE,
                    score = ch.rrfScore,
                    bm25Score = ch.rawBm25,
                    vectorScore = ch.rawVector,
                    rank = rank,
                    metadata = mapOf(
                        "type" to "note",
                        "fileName" to fileName
                    )
                )
            )
        }

        // 5. Also search source segments directly
        val segmentResults = try {
            sourceSegmentRepository.searchAll(plan.queryText, limit = 20)
        } catch (e: Exception) {
            Log.w(TAG, "Segment search failed: ${e.message}")
            emptyList()
        }

        // Hoisted dedup set instead of recomputing results.map per iteration.
        val existingIds = results.map { it.sourceSegmentId }.toMutableSet()
        for (segment in segmentResults) {
            if (segment.id !in existingIds) {
                val relevance = computeSegmentRelevance(plan.queryText, segment.text)
                if (relevance <= 0f) continue
                existingIds.add(segment.id)
                segmentCandidates.add(segment)
                results.add(
                    RetrievalResult(
                        sourceSegmentId = segment.id,
                        sourceId = segment.sourceId,
                        text = segment.text,
                        sourceType = segment.sourceType,
                        score = relevance,
                        bm25Score = relevance,
                        rank = results.size,
                        startMs = segment.startMs,
                        endMs = segment.endMs,
                        pageNumber = segment.pageNumber,
                        metadata = mapOf("type" to "segment")
                    )
                )
            }
        }

        // 6. Segment embedding search (memory-layer semantic retrieval). Reuses
        // the query vector from step 2; merges into the same result list.
        if (queryVector != null) {
            try {
                val hits = segmentEmbeddingService.search(queryVector!!, maxResults = 10)
                val hitSegments = segmentsByIds(hits.map { it.segmentId })
                for (hit in hits) {
                    if (hit.segmentId !in existingIds) {
                        existingIds.add(hit.segmentId)
                        // Null-segment hits carry text="" and are pruned
                        // downstream anyway — skip them here so they are never
                        // counted as candidates or evidence.
                        val seg = hitSegments[hit.segmentId] ?: continue
                        segmentCandidates.add(seg)
                        results.add(
                            RetrievalResult(
                                sourceSegmentId = hit.segmentId,
                                sourceId = hit.sourceId,
                                text = seg.text,
                                sourceType = seg.sourceType,
                                score = hit.score,
                                vectorScore = hit.score,
                                rank = results.size,
                                startMs = seg.startMs,
                                endMs = seg.endMs,
                                pageNumber = seg.pageNumber,
                                metadata = mapOf("type" to "segment_embedding")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Segment embedding search failed: ${e.message}")
            }
        }

        // ── Filter & Eval Gate (hard pre-fusion prune) ─────────────
        // Resolve note-level results to their segments for the Filter & Eval Gate.
        // A note result (metadata "type" = "note") carries no direct SourceSegment row,
        // so its source is resolved to segments (via getBySourceId) and pruned the same
        // way as the segment candidates; the note result is dropped if all of its
        // segments fail the gate.
        val noteSources = results.filter { it.metadata?.get("type") == "note" }.map { it.sourceId }.distinct()
        val noteSegments = if (noteSources.isNotEmpty()) {
            noteSources.flatMap { sourceSegmentRepository.getBySourceId(it) }
        } else emptyList()
        val noteSegmentBySource = noteSegments.groupBy { it.sourceId }

        // Combine with segment candidates and prune the merged list.
        val allSegmentCandidates = (segmentCandidates + noteSegments).distinctBy { it.id }
        val (survivingSegments, pruneTrace) = MetadataFilterExtractor().prune(
            plan, allSegmentCandidates, entityRepository, timelineRepository
        )
        val survivingIds = survivingSegments.map { it.id }.toSet()
        val survivingNoteSources = (survivingSegments + noteSegments)
            .filter { it.id in survivingIds }
            .map { it.sourceId }.toSet()

        val unsegmentedSources = noteSources.filter { noteSegmentBySource[it].isNullOrEmpty() }.toSet()
        val allowedUnsegmentedSources = if (plan.sourceTypes.isNotEmpty() && SourceType.NOTE !in plan.sourceTypes || plan.requiresTimeline) {
            emptySet()
        } else {
            unsegmentedSources
        }

        val prunedResults = results.filter { r ->
            val t = r.metadata?.get("type")
            when (t) {
                "note" -> r.sourceId in survivingNoteSources || r.sourceId in allowedUnsegmentedSources
                else -> r.sourceSegmentId in survivingIds
            }
        }

        // 7. Per-source min-max normalization + fused scoring (the general path only;
        // the intent-specific paths keep their own scoring, adapted via RetrievalOutcome).
        // Lone-candidate results (every present channel degenerate) are penalized so
        // weak single-channel stragglers cannot ride a 0.5 normalization past the
        // threshold. Unsegmented notes skip the prune gate (no segment rows to
        // check) but fuse at UNSEGMENTED_PENALTY so gate-verified evidence
        // outranks unverified-but-lexically-strong notes on equal strength.
        val unsegmentedKeys = allowedUnsegmentedSources.map { "note_$it" }.toSet()
        val (normalized, loneKeys) = normalize(prunedResults)
        val fused = fuse(normalized, loneKeys, unsegmentedKeys)

        // 8. Threshold rejection + rerank (move the surviving list by fused score).
        val above = fused.filter { it.score >= retrievalConfig.minimumScore }
            .sortedByDescending { it.score }
            .take(retrievalConfig.topK.coerceAtMost(FILTERED_MAX))

        // 9. Entity context enrichment: look up confirmed entities whose mentions
        // appear in the top results and prepend a compact context entry so the
        // LLM sees structured graph data even on GENERAL queries.
        val withEntities = enrichWithEntityContext(above)
        val enriched = enrichWithTimelineContext(withEntities)

        return RetrievalOutcome(
            results = enriched,
            shortCircuit = when {
                prunedResults.isEmpty() && results.isEmpty() -> ShortCircuitReason.NO_CANDIDATES
                prunedResults.isEmpty() -> ShortCircuitReason.ALL_PRUNED
                above.isEmpty() -> ShortCircuitReason.BELOW_THRESHOLD
                else -> null
            },
            trace = combineTrace(results.size, pruneTrace, enriched.size)
        )
    }

    /**
     * Combine the Filter & Eval Gate prune trace with the post-threshold final count.
     * Copies the per-stage prune counters; [candidatesGenerated] reflects the full
     * pre-prune result list, [belowThreshold] the gap between prune survivors and the
     * final post-threshold list, and [finalCount] the final result size.
     */
    private fun combineTrace(candidatesGenerated: Int, prune: RetrievalTrace, finalCount: Int): RetrievalTrace =
        prune.copy(
            candidatesGenerated = candidatesGenerated,
            belowThreshold = prune.survivedPrune - finalCount.coerceAtLeast(0),
            finalCount = finalCount
        )

    /**
     * Look up confirmed entities whose mentions appear in the top retrieval results
     * and prepend a compact context entry. This surfaces graph knowledge on GENERAL
     * queries without touching intent routing.
     *
     * At most 5 distinct entities are surfaced, plus up to 3 1-hop neighbor entities
     * connected via knowledge graph relations (Fix 1). Direct relations are rendered
     * under each entity (Fix 5). The synthetic entry has a fixed score of 1.0 so it
     * sorts to the front, and its metadata type is "entity_context".
     */
    private suspend fun enrichWithEntityContext(results: List<RetrievalResult>): List<RetrievalResult> {
        if (results.isEmpty()) return results
        try {
            val segmentIds = results.mapNotNull { r ->
                r.sourceSegmentId.takeIf {
                    it.isNotBlank() &&
                        !it.startsWith("note_") &&
                        !it.startsWith("entity_context_") &&
                        !it.startsWith("timeline_context_")
                }
            }.distinct().take(10)
            if (segmentIds.isEmpty()) return results

            val mentions = entityRepository.getActiveMentionsBySegmentIds(segmentIds)
            if (mentions.isEmpty()) return results

            val entityIds = mentions.map { it.entityId }.distinct().take(5)
            val primaryEntities = entityIds.mapNotNull { entityRepository.getById(it) }
                .filter { it.confirmation == com.noteflowai.app.data.memory.model.ConfirmationState.CONFIRMED || it.userConfirmed }

            if (primaryEntities.isEmpty()) return results

            // 1-hop neighbor traversal (Fix 1)
            val primaryIdSet = primaryEntities.map { it.id }.toSet()
            val neighbors = primaryEntities.flatMap { entityRepository.getNeighborEntities(it.id) }
                .filter { it.id !in primaryIdSet }
                .distinctBy { it.id }
                .take(3)

            val allEntities = primaryEntities + neighbors

            val contextText = buildString {
                appendLine("[Entity Context]")
                for (entity in allEntities) {
                    val isNeighbor = entity.id !in primaryIdSet
                    val prefix = if (isNeighbor) "• [Related] " else "• "
                    append("$prefix${entity.canonicalName} (${entity.type.name})")
                    if (!entity.aliasesJson.isNullOrBlank()) {
                        val aliases = runCatching {
                            com.google.gson.Gson().fromJson(entity.aliasesJson, Array<String>::class.java)?.toList()
                        }.getOrNull()
                        if (!aliases.isNullOrEmpty()) append(" aka ${aliases.joinToString(", ")}")
                    }
                    appendLine()

                    // Knowledge graph edge retrieval (Fix 5)
                    val relations = runCatching {
                        entityRepository.getRelationsForEntity(entity.id)
                    }.getOrDefault(emptyList()).take(3)

                    for (rel in relations) {
                        val otherId = if (rel.fromId == entity.id) rel.toId else rel.fromId
                        val otherEntity = entityRepository.getById(otherId)
                        val otherName = otherEntity?.canonicalName ?: otherId
                        appendLine("  → ${rel.relationType} → $otherName")
                    }
                }
            }.trim()

            val contextResult = RetrievalResult(
                sourceSegmentId = "entity_context_${System.currentTimeMillis()}",
                sourceId = "knowledge_graph",
                text = contextText,
                sourceType = SourceType.NOTE,
                score = 1.0f,
                rank = 0,
                metadata = mapOf("type" to "entity_context")
            )
            return listOf(contextResult) + results.mapIndexed { i, r -> r.copy(rank = i + 1) }
        } catch (e: Exception) {
            Log.w(TAG, "Entity context enrichment failed: ${e.message}")
            return results
        }
    }

    /**
     * Look up timeline entries matching segment IDs from the top retrieval results
     * and prepend a compact context entry. This surfaces temporal knowledge on GENERAL
     * queries without touching intent routing (Fix 3).
     *
     * At most 3 timeline entries are surfaced. The synthetic entry has a fixed score
     * of 0.95f so it sits alongside entity context, and its metadata type is "timeline_context".
     */
    private suspend fun enrichWithTimelineContext(results: List<RetrievalResult>): List<RetrievalResult> {
        if (results.isEmpty()) return results
        try {
            val segmentIds = results.mapNotNull { r ->
                r.sourceSegmentId.takeIf {
                    it.isNotBlank() &&
                        !it.startsWith("note_") &&
                        !it.startsWith("entity_context_") &&
                        !it.startsWith("timeline_context_")
                }
            }.distinct().take(10)
            if (segmentIds.isEmpty()) return results

            val entries = timelineRepository.getBySegmentIds(segmentIds)
                .filter { it.confirmation != com.noteflowai.app.data.memory.model.ConfirmationState.REJECTED }
                .take(3)

            if (entries.isEmpty()) return results

            val contextText = buildString {
                appendLine("[Timeline Context]")
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                for (entry in entries) {
                    val dateStr = entry.startMs?.let { fmt.format(java.util.Date(it)) } ?: "unknown"
                    appendLine("• ${entry.title} (${entry.precision.name}: $dateStr)")
                }
            }.trim()

            val contextResult = RetrievalResult(
                sourceSegmentId = "timeline_context_${System.currentTimeMillis()}",
                sourceId = "timeline",
                text = contextText,
                sourceType = SourceType.NOTE,
                score = 0.95f,
                rank = 0,
                metadata = mapOf("type" to "timeline_context")
            )
            return listOf(contextResult) + results.mapIndexed { i, r -> r.copy(rank = i + 1) }
        } catch (e: Exception) {
            Log.w(TAG, "Timeline context enrichment failed: ${e.message}")
            return results
        }
    }

    /**
     * Batch-load segments for a list of ids into an id-keyed map.
     * Replaces per-item [sourceSegmentRepository.getById] calls (N+1).
     */
    private suspend fun segmentsByIds(ids: List<String>): Map<String, SourceSegment> {
        if (ids.isEmpty()) return emptyMap()
        return sourceSegmentRepository.getByIds(ids.distinct()).associateBy { it.id }
    }

    // ── Score normalization + weighted fusion (guide §Phase 5) ──────

    /**
     * Min-max normalize each result's raw score into [0,1] per candidate source.
     * Sources are distinguished by metadata["type"]. A single-result source maps to
     * 0.5 (no scale to normalize against); equal scores map to 0.5.
     *
     * Each non-null per-channel field is normalized independently with the same rules:
     * a channel present on a single result (or equal across results) in a group maps
     * to 0.5, and null channels stay null. The [RetrievalResult.score] field is
     * normalized as today but recomputed by [fuse] and ignored by the final ordering.
     *
     * Returns the normalized results plus the ids of lone candidates — results whose
     * EVERY present channel was degenerate (singleton or all-equal). Callers pass
     * these to [fuse] for the lone-candidate penalty.
     */
    private fun normalize(raw: List<RetrievalResult>): Pair<List<RetrievalResult>, Set<String>> {
        if (raw.isEmpty()) return raw to emptySet()
        val bySource = raw.groupBy { it.metadata?.get("type") ?: "note" }
        val out = mutableListOf<RetrievalResult>()
        val loneKeys = mutableSetOf<String>()
        for ((_, group) in bySource) {
            val (scores, scoresDegenerate) = normalizeChannel(group.map { it.score }, isNaturallyBounded = true)
            val (bm25, bm25Degenerate) = normalizeChannel(group.map { it.bm25Score })
            val (vector, vectorDegenerate) = normalizeChannel(group.map { it.vectorScore }, isNaturallyBounded = true)
            val (entity, entityDegenerate) = normalizeChannel(group.map { it.entityBoost }, isNaturallyBounded = true)
            val (recency, recencyDegenerate) = normalizeChannel(group.map { it.recencyBoost }, isNaturallyBounded = true)
            group.forEachIndexed { i, r ->
                // Lone candidate: every channel it carries was degenerate, so its
                // normalized values carry no comparative information.
                val channels = listOf(
                    r.score to scoresDegenerate,
                    r.bm25Score to bm25Degenerate,
                    r.vectorScore to vectorDegenerate,
                    r.entityBoost to entityDegenerate,
                    r.recencyBoost to recencyDegenerate
                )
                val present = channels.filter { (v, _) -> v != null }
                if (present.isNotEmpty() && present.all { (_, degenerate) -> degenerate }) {
                    val isStrongCandidate = (r.vectorScore != null && r.vectorScore >= 0.40f) ||
                            (r.bm25Score != null && r.bm25Score >= 0.60f) ||
                            (r.score >= 0.60f)
                    if (!isStrongCandidate) {
                        loneKeys.add(r.sourceSegmentId)
                    }
                }
                out.add(
                    r.copy(
                        score = scores[i] ?: 0f,
                        bm25Score = bm25[i],
                        vectorScore = vector[i],
                        entityBoost = entity[i],
                        recencyBoost = recency[i]
                    )
                )
            }
        }
        return out to loneKeys
    }

    /**
     * Min-max normalize one channel's values into [0,1]. A null value stays null (the
     * channel is absent for that result); if no value is present nothing changes; a
     * single present value or all-equal values map to 0.5 (no scale to compare),
     * or retain their original bounded values if [isNaturallyBounded] is true.
     *
     * Returns the normalized values and whether the channel was degenerate
     * (singleton or all-equal present values).
     */
    private fun normalizeChannel(
        values: List<Float?>,
        isNaturallyBounded: Boolean = false
    ): Pair<List<Float?>, Boolean> {
        val present = values.filterNotNull()
        if (present.isEmpty()) return values to false
        val min = present.minOrNull()!!
        val max = present.maxOrNull()!!
        if (present.size == 1 || max == min) {
            if (isNaturallyBounded) {
                return values.map { v -> v?.coerceIn(0f, 1f) } to true
            }
            return values.map { v -> if (v != null) 0.5f else null } to true
        }
        val range = max - min
        return values.map { v -> v?.let { ((it - min) / range).coerceIn(0f, 1f) } } to false
    }

    /**
     * Dynamically computes lexical relevance for direct segment matches against the query.
     * Uses word-token matching with word boundaries to prevent false-positive substring
     * hits (e.g. "rag" matching inside "paragraph").
     */
    internal fun computeSegmentRelevance(queryText: String, segmentText: String): Float {
        val q = queryText.lowercase(java.util.Locale.ROOT).trim()
        val s = segmentText.lowercase(java.util.Locale.ROOT)
        if (q.isEmpty() || s.isEmpty()) return 0.0f
        val rawTokens = q.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (rawTokens.isEmpty()) return 0.0f
        val tokens = rawTokens.filter { !StopWords.contains(it) }.ifEmpty { rawTokens }

        val segmentTokens = s.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        if (segmentTokens.isEmpty()) return 0.0f

        val matchedCount = tokens.count { token ->
            token in segmentTokens ||
                (token.length > 3 && (segmentTokens.contains("${token}s") || (token.endsWith("s") && segmentTokens.contains(token.dropLast(1)))))
        }
        if (matchedCount == 0) return 0.0f

        val tokenCoverage = matchedCount.toFloat() / tokens.size
        val exactPhrase = tokens.size > 1 &&
            Regex("\\b" + tokens.joinToString("[\\s\\p{Punct}]+") { Regex.escape(it) } + "\\b").containsMatchIn(s)
        val singleTokenExact = tokens.size == 1 && matchedCount == 1

        val exactBonus = when {
            exactPhrase -> 0.20f
            singleTokenExact -> 0.15f
            else -> 0.0f
        }
        return (0.35f + 0.45f * tokenCoverage + exactBonus).coerceIn(0.1f, 1.0f)
    }

    /**
     * Weighted fusion: fused = bm25×w_bm25 + vector×w_vec + entity×w_ent + recency×w_rec.
     * Each channel is a REAL per-channel score (kept separate through RRF and min-max
     * normalized per source), so a weight genuinely controls its channel. The weights
     * sum to 1.0 by the RetrievalConfig Global Constraint, but a result may only carry
     * some channels (e.g. a note with only bm25+vector, applied = 0.8); the weighted
     * sum is renormalized by the sum of the PRESENT weights so the fused score stays in
     * [0,1] and absent channels are skipped rather than counted as zero. A result with
     * no present channels fuses to 0.0.
     *
     * Results in [loneKeys] (every present channel degenerate — lone weak candidates
     * with no comparative support) are scaled by [LONE_CANDIDATE_FACTOR] so they
     * cannot clear the threshold on normalization inflation alone.
     */
    private fun fuse(
        ranked: List<RetrievalResult>,
        loneKeys: Set<String> = emptySet(),
        unsegmentedKeys: Set<String> = emptySet()
    ): List<RetrievalResult> {
        return ranked.map { r ->
            var weightedSum = 0f
            var applied = 0f
            r.bm25Score?.let { weightedSum += it * retrievalConfig.bm25Weight; applied += retrievalConfig.bm25Weight }
            r.vectorScore?.let { weightedSum += it * retrievalConfig.vectorWeight; applied += retrievalConfig.vectorWeight }
            r.entityBoost?.let { weightedSum += it * retrievalConfig.entityWeight; applied += retrievalConfig.entityWeight }
            r.recencyBoost?.let { weightedSum += it * retrievalConfig.recencyWeight; applied += retrievalConfig.recencyWeight }
            var fused = if (applied > 0f) weightedSum / applied else 0f
            if (r.sourceSegmentId in loneKeys) fused *= LONE_CANDIDATE_FACTOR
            if (r.sourceSegmentId in unsegmentedKeys) fused *= UNSEGMENTED_PENALTY
            r.copy(score = fused.coerceIn(0f, 1f))
        }
    }

    // ── Reciprocal Rank Fusion ───────────────────────────────────

    /**
     * Per-file RRF candidate information.
     * [rrfScore] is the combined reciprocal rank fusion score used for candidate ordering.
     * [rawBm25] and [rawVector] preserve the true underlying feature scores (Okapi BM25
     * score and dense vector cosine similarity) so that downstream normalization and
     * weighted linear fusion operate on calibrated metrics rather than RRF rank fractions.
     */
    private data class RrfChannels(
        val rrfScore: Float,
        val rawBm25: Float?,
        val rawVector: Float?
    )

    private fun reciprocalRankFusion(
        bm25Results: List<NoteSearchIndex.SearchResult>,
        embeddingResults: List<EmbeddingIndex.EmbeddingSearchResult>
    ): List<Pair<String, RrfChannels>> {
        val bm25Rrf = mutableMapOf<String, Float>()
        val vectorRrf = mutableMapOf<String, Float>()
        val rawBm25Map = mutableMapOf<String, Float>()
        val rawVectorMap = mutableMapOf<String, Float>()

        // BM25 contributions
        for ((rank, result) in bm25Results.withIndex()) {
            val rrfScore = 1.0f / (RRF_K + rank + 1)
            bm25Rrf[result.fileName] = (bm25Rrf[result.fileName] ?: 0f) + rrfScore
            rawBm25Map[result.fileName] = result.score
        }

        // Embedding contributions
        for ((rank, result) in embeddingResults.withIndex()) {
            val rrfScore = 1.0f / (RRF_K + rank + 1)
            vectorRrf[result.fileName] = (vectorRrf[result.fileName] ?: 0f) + rrfScore
            rawVectorMap[result.fileName] = result.score
        }

        // Union of files across channels; bm25-first insertion order is preserved (the
        // same tie-breaking the single merged map had), and the merged ordering stays
        // by total RRF score desc with the same take(RRF_MERGED_MAX) bound.
        val files = (bm25Rrf.keys + vectorRrf.keys).distinct()
        return files
            .map { f ->
                val totalRrf = (bm25Rrf[f] ?: 0f) + (vectorRrf[f] ?: 0f)
                f to RrfChannels(
                    rrfScore = totalRrf,
                    rawBm25 = rawBm25Map[f],
                    rawVector = rawVectorMap[f]
                )
            }
            .sortedByDescending { it.second.rrfScore }
            .take(RRF_MERGED_MAX)
            .map { it.first to it.second }
    }

    private fun Float?.orZero(): Float = this ?: 0f
}
