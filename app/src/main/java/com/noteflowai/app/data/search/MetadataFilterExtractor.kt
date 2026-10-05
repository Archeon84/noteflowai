package com.noteflowai.app.data.search

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.timeline.TimelineRepository

/**
 * Hard pre-fusion metadata prune (guide §Phase 5, Filter & Eval Gate). Pure,
 * deterministic, and unit-tested in isolation. Operates on candidate
 * [SourceSegment]s before any score normalization or fusion:
 *
 *  1. date range — chunk with a known interval outside [dateFrom, dateTo] is
 *     dropped; unknown-time chunks are kept (conservative, scoped only to plain
 *     temporal queries);
 *  2. source type — chunk whose sourceType is not in the query's sourceTypes is
 *     dropped;
 *  3. rejected entity — ANY single REJECTED mention or REJECTED entity on a
 *     segment drops the WHOLE chunk (full-chunk rejection, no partial weighting);
 *  4. required timeline — when plan.requiresTimeline, a chunk survives only if
 *     its source note has a timeline row with a decidable anchor (startMs != null).
 */
class MetadataFilterExtractor {

    /**
     * Filter set carried out of extraction, exposed for eval/telemetry.
     */
    data class RetrievalFilters(
        val dateFrom: Long? = null,
        val dateTo: Long? = null,
        val sourceTypes: List<SourceType> = emptyList(),
        val entityNames: List<String> = emptyList(),
        val requireTimeline: Boolean = false,
        val rejectedSegmentIds: Set<String> = emptySet()
    )

    /**
     * Prune [candidates] against [plan]. Returns the survivors plus per-stage
     * counts in a [RetrievalTrace]. Batches repo lookups once per call (no N+1).
     */
    suspend fun prune(
        plan: QueryPlan,
        candidates: List<SourceSegment>,
        entityRepository: EntityRepository,
        timelineRepository: TimelineRepository
    ): Pair<List<SourceSegment>, RetrievalTrace> {
        if (candidates.isEmpty()) return Pair(emptyList(), RetrievalTrace(candidatesGenerated = 0))

        var prunedByDate = 0
        var prunedBySourceType = 0
        var prunedByRejectedEntity = 0
        var prunedByTimeline = 0

        // Rejected-segment set: segments whose mentions touch a REJECTED entity or
        // are themselves REJECTED. One batch for the whole candidate set.
        // Fail-open on repo errors: an unfiltered list beats an empty one,
        // and a throw here used to collapse the whole outcome to silent empty.
        val rejectedSegmentIds = try {
            buildRejectedSegmentSet(candidates, entityRepository)
        } catch (e: Exception) {
            android.util.Log.w("MetadataFilterExtractor", "Rejected-set lookup failed, skipping: ${e.message}")
            emptySet()
        }

        // Timeline-covered set (only needed when requireTimeline). Keyed by
        // sourceNoteId, which is the segment's sourceId by construction, so
        // the candidate.sourceId lookup below is domain-correct.
        val timelineFlags: Map<String, Boolean> = if (plan.requiresTimeline) {
            try {
                timelineRepository.getAll()
                    .groupBy { it.sourceNoteId }
                    .mapValues { (_, rows) -> rows.any { it.startMs != null } }
            } catch (e: Exception) {
                android.util.Log.w("MetadataFilterExtractor", "Timeline lookup failed, skipping: ${e.message}")
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val kept = mutableListOf<SourceSegment>()
        for (candidate in candidates) {
            if (candidate.id in rejectedSegmentIds) { prunedByRejectedEntity++; continue }

            if (plan.sourceTypes.isNotEmpty() && candidate.sourceType !in plan.sourceTypes) {
                prunedBySourceType++; continue
            }

            if (plan.requiresTimeline) {
                val anchored = timelineFlags[candidate.sourceId] ?: false
                if (!anchored) { prunedByTimeline++; continue }
            }

            if (plan.dateFrom != null || plan.dateTo != null) {
                val start = candidate.startMs
                val end = candidate.endMs ?: start
                if (start != null && end != null && outsideRange(start, end, plan.dateFrom, plan.dateTo)) {
                    prunedByDate++; continue
                }
            }

            kept.add(candidate)
        }

        val trace = RetrievalTrace(
            candidatesGenerated = candidates.size,
            prunedByDate = prunedByDate,
            prunedBySourceType = prunedBySourceType,
            prunedByRejectedEntity = prunedByRejectedEntity,
            prunedByTimeline = prunedByTimeline,
            survivedPrune = kept.size
        )
        return Pair(kept, trace)
    }

    /**
     * Segments that must be dropped whole-chunk because they carry a REJECTED
     * mention or a mention of a REJECTED entity. Does it in exactly two batched
     * repository lookups over the whole candidate set (no per-segment N+1): the
     * "all" mention rows vs the active subset (the entities-join excludes REJECTED
     * entities and REJECTED mentions). A segment whose counts differ is rejected.
     */
    private suspend fun buildRejectedSegmentSet(
        candidates: List<SourceSegment>,
        entityRepository: EntityRepository
    ): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val segmentIds = candidates.map { it.id }
        val all = entityRepository.getMentionsBySegmentIds(segmentIds)
            .groupBy { it.sourceSegmentId }
        val active = entityRepository.getActiveMentionsBySegmentIds(segmentIds)
            .groupBy { it.sourceSegmentId }
        val rejected = mutableSetOf<String>()
        for (segmentId in segmentIds) {
            if ((all[segmentId]?.size ?: 0) != (active[segmentId]?.size ?: 0)) {
                rejected.add(segmentId)
            }
        }
        return rejected
    }

    private fun outsideRange(startMs: Long, endMs: Long, from: Long?, to: Long?): Boolean {
        val lo = from ?: Long.MIN_VALUE
        val hi = to ?: Long.MAX_VALUE
        // Fully outside [lo, hi]: end < lo or start > hi.
        return endMs < lo || startMs > hi
    }
}