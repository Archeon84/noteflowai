package com.noteflowai.app.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.Entity
import com.noteflowai.app.data.memory.model.EntityMention
import com.noteflowai.app.data.memory.model.EntityType
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.model.TemporalPrecision
import com.noteflowai.app.data.memory.model.TimelineEntry
import com.noteflowai.app.data.memory.model.TemporalPrecision.EXACT
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.timeline.TimelineRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetadataFilterExtractorTest {

    private lateinit var db: MemoryDatabase
    private lateinit var entities: EntityRepository
    private lateinit var timeline: TimelineRepository
    private lateinit var extractor: MetadataFilterExtractor

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        entities = EntityRepository(db)
        timeline = TimelineRepository(db)
        extractor = MetadataFilterExtractor()
    }

    private fun seg(id: String, sourceId: String = "note_$id", sourceType: SourceType = SourceType.NOTE, startMs: Long? = null, endMs: Long? = null) =
        SourceSegment(id = id, sourceId = sourceId, sourceType = sourceType, text = "text $id", normalizedText = "text $id", startMs = startMs, endMs = endMs)

    private fun mention(entityId: String, segmentId: String) = EntityMention(entityId = entityId, sourceSegmentId = segmentId, mentionText = "m")

    private suspend fun rejectEntity(name: String, segmentId: String) {
        entities.insert(Entity(id = "e_$name", type = EntityType.PERSON, canonicalName = name, normalizedName = name.lowercase()))
        entities.insertWithMention(
            Entity(id = "e_$name", type = EntityType.PERSON, canonicalName = name, normalizedName = name.lowercase()),
            mention("e_$name", segmentId)
        )
        entities.reject("e_$name")
    }

    private fun timelineEntry(segmentId: String, noteId: String, startMs: Long?) = TimelineEntry(
        id = "t_$segmentId", title = "t", startMs = startMs, endMs = startMs, precision = EXACT,
        sourceSegmentId = segmentId, sourceNoteId = noteId, confidence = 0.9f,
        confirmation = ConfirmationState.CONFIRMED
    )

    @Test
    fun `required timeline drops chunks without a decidable anchor`() = runTest {
        timeline.insert(timelineEntry("s_decidable", "note_s_decidable", 1_700_000_000_000L))
        val candidates = listOf(
            seg("s_no_row", "note_s_no_row"),
            seg("s_undecidable", "note_s_undecidable"),
            seg("s_decidable", "note_s_decidable")
        )
        val plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "when", requiresTimeline = true)

        val (kept, trace) = extractor.prune(plan, candidates, entities, timeline)

        assertEquals(listOf("s_decidable"), kept.map { it.id })
        assertEquals(2, trace.prunedByTimeline)
    }

    @Test
    fun `date range keeps unknown-time chunks but drops out-of-range`() = runTest {
        val candidates = listOf(
            seg("s_unknown", startMs = null),
            seg("s_in", "note_s_in", startMs = 1_700_000_000_000L, endMs = 1_700_000_007_000L),
            seg("s_out", "note_s_out", startMs = 1_600_000_000_000L, endMs = 1_600_000_007_000L)
        )
        val plan = QueryPlan(
            intent = QueryIntent.GENERAL_RAG, queryText = "q",
            dateFrom = 1_650_000_000_000L, dateTo = 1_750_000_000_000L
        )

        val (kept, trace) = extractor.prune(plan, candidates, entities, timeline)

        val keptIds = kept.map { it.id }
        assertTrue("s_unknown" in keptIds)
        assertTrue("s_in" in keptIds)
        assertTrue("s_out" !in keptIds)
        assertEquals(1, trace.prunedByDate)
    }

    @Test
    fun `single rejected entity drops the whole chunk`() = runTest {
        // seg_poisoned has one active mention of Alice plus one rejected Bob mention.
        entities.insert(Entity(id = "e_alice", type = EntityType.PERSON, canonicalName = "Alice", normalizedName = "alice"))
        entities.insertWithMention(
            Entity(id = "e_alice", type = EntityType.PERSON, canonicalName = "Alice", normalizedName = "alice"),
            mention("e_alice", "seg_poisoned")
        )
        rejectEntity("Bob", "seg_poisoned")
        val candidates = listOf(seg("seg_clean"), seg("seg_poisoned"))

        val (kept, trace) = extractor.prune(plan(), candidates, entities, timeline)

        assertTrue(kept.map { it.id } == listOf("seg_clean"))
        assertEquals(1, trace.prunedByRejectedEntity)
    }

    @Test
    fun `source type mismatch drops non-matching chunks`() = runTest {
        val candidates = listOf(
            seg("s_note", sourceType = SourceType.NOTE),
            seg("s_pdf", sourceType = SourceType.PDF)
        )
        val plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "q", sourceTypes = listOf(SourceType.NOTE))

        val (kept, trace) = extractor.prune(plan, candidates, entities, timeline)

        assertEquals(listOf("s_note"), kept.map { it.id })
        assertEquals(1, trace.prunedBySourceType)
    }

    private fun plan() = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "q")
}