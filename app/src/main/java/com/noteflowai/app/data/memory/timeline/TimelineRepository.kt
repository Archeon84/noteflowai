package com.noteflowai.app.data.memory.timeline

import android.content.Context
import com.google.gson.Gson
import com.noteflowai.app.data.memory.dao.TimelineEntryDao
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.extraction.ExtractedTimelineEntry
import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.TemporalPrecision
import com.noteflowai.app.data.memory.model.TimelineEntry
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Persistence for [TimelineEntry] (guide §Phase 4).
 *
 * Wraps [TimelineEntryDao] with a dedup-by-(title, segment, startMs) guard for
 * re-extraction idempotency, source-wide delete for rebuild replacement, and the
 * domain edits (confirm / reject / edit) the timeline review screen performs.
 */
class TimelineRepository private constructor(
    private val db: MemoryDatabase,
    private val dao: TimelineEntryDao
) {

    constructor(context: Context) : this(MemoryDatabase.getInstance(context))

    /** Test seam: build the repository over an injected (e.g. in-memory) database. */
    internal constructor(db: MemoryDatabase) : this(db, db.timelineEntryDao())

    suspend fun insert(entry: TimelineEntry) = withContext(Dispatchers.IO) {
        dao.insert(entry)
    }

    suspend fun insertAll(entries: List<TimelineEntry>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        db.withTransaction {
            entries.forEach { dao.insert(it) }
        }
    }

    suspend fun update(entry: TimelineEntry) = withContext(Dispatchers.IO) {
        dao.update(entry.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun getById(id: String): TimelineEntry? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    suspend fun getAll(): List<TimelineEntry> = withContext(Dispatchers.IO) {
        dao.getAll()
    }

    /**
     * Reactive observation with duplicate consecutive emissions suppressed
     * (same rationale as ProcessingStatusRepository.observeAll).
     */
    fun observeAll(): Flow<List<TimelineEntry>> = dao.observeAll().distinctUntilChanged()

    suspend fun getByConfirmation(confirmation: ConfirmationState): List<TimelineEntry> =
        withContext(Dispatchers.IO) {
            dao.getByConfirmation(confirmation)
        }

    suspend fun getByDateRange(startMs: Long, endMs: Long): List<TimelineEntry> =
        withContext(Dispatchers.IO) {
            dao.getByDateRange(startMs, endMs)
        }

    suspend fun getBySegmentIds(segmentIds: List<String>): List<TimelineEntry> =
        withContext(Dispatchers.IO) {
            if (segmentIds.isEmpty()) emptyList() else dao.getBySegmentIds(segmentIds)
        }

    suspend fun confirm(id: String) = withContext(Dispatchers.IO) {
        val entry = dao.getById(id) ?: return@withContext
        dao.update(entry.copy(
            confirmation = ConfirmationState.CONFIRMED,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun reject(id: String) = withContext(Dispatchers.IO) {
        val entry = dao.getById(id) ?: return@withContext
        dao.update(entry.copy(
            confirmation = ConfirmationState.REJECTED,
            updatedAt = System.currentTimeMillis()
        ))
    }

    /**
     * User edit of title / date range; recomputes [TemporalPrecision] so a single edited
     * date never lands on UNKNOWN (guide §Phase 4 timeline review).
     */
    suspend fun edit(id: String, newTitle: String, startMs: Long?, endMs: Long?) = withContext(Dispatchers.IO) {
        val entry = dao.getById(id) ?: return@withContext
        dao.update(entry.copy(
            title = newTitle.trim().ifEmpty { entry.title },
            startMs = startMs,
            endMs = endMs,
            precision = precisionFor(startMs, endMs),
            confirmation = ConfirmationState.CONFIRMED,
            updatedAt = System.currentTimeMillis()
        ))
    }

    /**
     * Replace a source's timeline rows with freshly extracted ones (rebuild idempotency):
     * deletes the old derived entries first so re-extraction cannot stack duplicates.
     */
    suspend fun replaceSource(noteId: String, entries: List<TimelineEntry>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        // Delete inside the same transaction: an insert failure after a
        // standalone delete used to mean total timeline loss for the source.
        db.withTransaction {
            dao.deleteBySourceId(noteId)
            entries.forEach { dao.insert(it) }
        }
    }

    /** Delete a source's timeline rows (used before re-extraction in the rebuild worker). */
    suspend fun deleteBySourceId(noteId: String) = withContext(Dispatchers.IO) {
        dao.deleteBySourceId(noteId)
    }

    suspend fun countBySourceNoteId(noteId: String): Int = withContext(Dispatchers.IO) {
        dao.countBySourceNoteId(noteId)
    }

    suspend fun getBySourceNoteId(noteId: String): List<TimelineEntry> = withContext(Dispatchers.IO) {
        dao.getBySourceNoteId(noteId)
    }

    /**
     * Dedup-safe insert of a single extracted entry: skips when a row with the same
     * (normalized title, source segment, startMs) already exists for the note.
     */
    suspend fun insertFromExtraction(entry: ExtractedTimelineEntry, segment: SourceSegment): TimelineEntry? =
        withContext(Dispatchers.IO) {
            val startMs = parseDate(entry.start_date)
            val endMs = parseDate(entry.end_date)
            val precision = runCatching { TemporalPrecision.valueOf(entry.precision) }
                .getOrDefault(TemporalPrecision.UNKNOWN)

            // Dedup guard: an identical (title, segment, startMs) row already exists.
            val dup = dao.getBySourceNoteId(segment.sourceId).any {
                it.title.equals(entry.title, ignoreCase = true) &&
                    it.sourceSegmentId == segment.id &&
                    it.startMs == startMs
            }
            if (dup) return@withContext null

            val now = System.currentTimeMillis()
            val row = TimelineEntry(
                id = "tim_${UUID.randomUUID()}",
                title = entry.title.trim(),
                startMs = startMs,
                endMs = endMs,
                precision = precision,
                sourceSegmentId = segment.id,
                sourceNoteId = segment.sourceId,
                confidence = entry.confidence.coerceIn(0f, 1f),
                originalExtraction = entryToJson(entry),
                createdAt = now,
                updatedAt = now
            )
            dao.insert(row)
            row
        }

    /**
     * Batched sibling of [insertFromExtraction]: fetches each source's
     * existing rows once (instead of one full source scan per entry) and
     * inserts the non-duplicates in a single transaction. Returns the
     * inserted rows.
     */
    suspend fun insertManyFromExtraction(
        entries: List<ExtractedTimelineEntry>,
        segmentsById: Map<String, SourceSegment>
    ): List<TimelineEntry> = withContext(Dispatchers.IO) {
        val seen = mutableSetOf<String>()
        val existingBySource = mutableMapOf<String, List<TimelineEntry>>()
        val toInsert = mutableListOf<TimelineEntry>()
        val now = System.currentTimeMillis()
        for (entry in entries) {
            val segment = entry.source_segment_id?.let { segmentsById[it] } ?: continue
            val startMs = parseDate(entry.start_date)
            val key = "${entry.title.lowercase(java.util.Locale.ROOT).trim()}|${segment.id}|$startMs"
            if (!seen.add(key)) continue
            val existing = existingBySource.getOrPut(segment.sourceId) {
                dao.getBySourceNoteId(segment.sourceId)
            }
            val dup = existing.any {
                it.title.equals(entry.title, ignoreCase = true) &&
                    it.sourceSegmentId == segment.id &&
                    it.startMs == startMs
            }
            if (dup) continue
            val precision = runCatching { TemporalPrecision.valueOf(entry.precision) }
                .getOrDefault(TemporalPrecision.UNKNOWN)
            toInsert.add(
                TimelineEntry(
                    id = "tim_${UUID.randomUUID()}",
                    title = entry.title.trim(),
                    startMs = startMs,
                    endMs = parseDate(entry.end_date),
                    precision = precision,
                    sourceSegmentId = segment.id,
                    sourceNoteId = segment.sourceId,
                    confidence = entry.confidence.coerceIn(0f, 1f),
                    originalExtraction = entryToJson(entry),
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        if (toInsert.isNotEmpty()) {
            db.withTransaction {
                toInsert.forEach { dao.insert(it) }
            }
        }
        toInsert
    }

    private fun entryToJson(entry: ExtractedTimelineEntry): String =
        Gson().toJson(
            mapOf(
                "title" to entry.title,
                "start_date" to entry.start_date,
                "end_date" to entry.end_date,
                "precision" to entry.precision,
                "confidence" to entry.confidence,
                "source_segment_id" to entry.source_segment_id
            )
        )

    /**
     * Derive [TemporalPrecision] from an edited start/end pair: equal single dates are
     * EXACT; two distinct dates DAY_RANGE; neither nullable but unknown otherwise.
     */
    private fun precisionFor(startMs: Long?, endMs: Long?): TemporalPrecision = when {
        startMs == null && endMs == null -> TemporalPrecision.UNKNOWN
        startMs == null -> TemporalPrecision.UNKNOWN
        endMs == null || endMs == startMs -> TemporalPrecision.EXACT
        else -> TemporalPrecision.DAY_RANGE
    }

    private fun parseDate(dateStr: String?): Long? =
        com.noteflowai.app.data.memory.extraction.LenientDates.parseToMillis(dateStr)
}