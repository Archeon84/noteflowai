package com.noteflowai.app.data.memory.timeline

import android.content.Context
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.model.TemporalPrecision
import com.noteflowai.app.data.memory.model.TimelineEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * One-time backfill of [TimelineEntry] rows for notes processed before timeline
 * extraction existed (guide §Phase 4).
 *
 * Derives EXACT entries only from date-bearing structured data: [decision
 * decidedAt], [commitment dueAt], and memory-object [dueAt]/[reviewAt].
 * Dateless objects (a QUESTION's [extractedAt] is extraction time, not event
 * time) are never backfilled — fabricating EXACT entries for them poisoned
 * the timeline. Every derived entry is SUGGESTED, carries the source row's
 * confidence, and keeps [TimelineEntry.originalExtraction] null because it
 * was not machine extracted.
 *
 * Idempotent per entry: an entry whose (title, segment, date) already exists
 * for the source is skipped, so a re-run never stacks backfill rows on top
 * of extracted ones. Runs inside a single transaction per source.
 */
class TimelineBackfillService(context: Context) {

    private val db: MemoryDatabase = MemoryDatabase.getInstance(context)
    private val timelineRepo = TimelineRepository(context)

    data class BackfillResult(
        val sourcesProcessed: Int = 0,
        val entriesAdded: Int = 0
    )

    /**
     * Backfill every source that has structured memory data but no timeline rows
     * yet. Returns per-source counts (sources touched, entries inserted) for the
     * rebuild final report.
     */
    suspend fun backfillFromExisting(): BackfillResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // Collect candidate rows with their source segment ids and confidence.
        data class Candidate(val title: String, val dateMs: Long?, val segmentId: String, val confidence: Float)
        val candidates = mutableListOf<Candidate>()
        db.decisionDao().getAll().forEach { d ->
            d.decidedAt?.let { candidates.add(Candidate(d.statement, it, d.sourceSegmentId, d.confidence)) }
        }
        db.commitmentDao().getAll().forEach { c ->
            c.dueAt?.let { candidates.add(Candidate(c.action, it, c.sourceSegmentId, c.confidence)) }
        }
        db.memoryObjectDao()
            .getAll()
            .filter { it.type != MemoryType.DECISION && it.type != MemoryType.COMMITMENT }
            .forEach { m ->
                // Only genuinely dated objects: extraction time is not event time.
                val dateMs = m.dueAt ?: m.reviewAt ?: return@forEach
                candidates.add(Candidate(m.statement, dateMs, m.sourceSegmentId, m.confidence))
            }

        if (candidates.isEmpty()) return@withContext BackfillResult()

        // Map segment id -> source id in one batch query.
        val segmentIds = candidates.map { it.segmentId }.distinct()
        val segmentsBySegmentId = db.sourceSegmentDao()
            .getByIds(segmentIds)
            .associateBy { it.id }

        // Group candidates by source so idempotency is checked per source.
        val bySource = candidates
            .filter { segmentsBySegmentId.containsKey(it.segmentId) }
            .groupBy { segmentsBySegmentId.getValue(it.segmentId).sourceId }

        var sourcesProcessed = 0
        var entriesAdded = 0

        bySource.forEach { (sourceId, rows) ->
            // Per-entry idempotency: one extracted entry must not block
            // backfill of the source's other entries, and re-runs must not
            // stack duplicates on top of extracted rows.
            val existingKeys = timelineRepo.getBySourceNoteId(sourceId)
                .map { "${it.title.lowercase(java.util.Locale.ROOT).trim()}|${it.sourceSegmentId}|${it.startMs}" }
                .toSet()
            val entries = rows
                // Dedup within the source: same (title, segment, date) is a repeat.
                .distinctBy { "${it.title}|${it.segmentId}|${it.dateMs}" }
                .filter { "${it.title.lowercase(java.util.Locale.ROOT).trim()}|${it.segmentId}|${it.dateMs}" !in existingKeys }
                .map { (title, dateMs, segmentId, confidence) ->
                    TimelineEntry(
                        id = "bck_${UUID.randomUUID()}",
                        title = title.trim(),
                        startMs = dateMs,
                        endMs = dateMs,
                        precision = TemporalPrecision.EXACT,
                        sourceSegmentId = segmentId,
                        sourceNoteId = sourceId,
                        confidence = confidence.coerceIn(0f, 1f),
                        confirmation = ConfirmationState.SUGGESTED,
                        originalExtraction = null,
                        createdAt = now,
                        updatedAt = now
                    )
                }
            if (entries.isEmpty()) return@forEach
            timelineRepo.insertAll(entries)
            sourcesProcessed++
            entriesAdded += entries.size
        }

        BackfillResult(sourcesProcessed, entriesAdded)
    }
}