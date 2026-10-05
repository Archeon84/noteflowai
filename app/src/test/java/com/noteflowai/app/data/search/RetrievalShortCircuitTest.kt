package com.noteflowai.app.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.Entity
import com.noteflowai.app.data.memory.model.EntityMention
import com.noteflowai.app.data.memory.model.EntityType
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.memory.timeline.TimelineRepository
import io.mockk.coEvery
import io.mockk.mockk
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
class RetrievalShortCircuitTest {

    private lateinit var db: MemoryDatabase
    private lateinit var noteSearchIndex: com.noteflowai.app.data.search.NoteSearchIndex
    private lateinit var sourceSegmentRepository: SourceSegmentRepository
    private lateinit var entityRepository: EntityRepository
    private lateinit var timelineRepository: TimelineRepository
    private lateinit var retriever: HybridRetriever

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        entityRepository = EntityRepository(db)
        timelineRepository = TimelineRepository(db)
        sourceSegmentRepository = mockk()
        noteSearchIndex = mockk()

        retriever = HybridRetriever(
            context,
            noteSearchIndex,
            mockk(), // embeddingIndex
            mockk(), // remoteEmbeddingClient
            mockk(), // onDeviceEmbedder
            sourceSegmentRepository,
            mockk(), // decisionRepository
            mockk(), // commitmentRepository
            mockk(), // memoryRepository
            entityRepository,
            mockk(), // segmentEmbeddingService
            timelineRepository,
            RetrievalConfig(topK = 10, minimumScore = 0.5f)
        )
    }

    @Test
    fun `no candidates short-circuits with NO_CANDIDATES`() = runTest {
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns emptyList()

        // noteSearchIndex is a mockk() — the empty returns must come from that mock.
        // The default mockk() returns emptyList() for List-returning members, so no
        // coEvery needed for the index.

        val outcome = retriever.retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "zzz absent"),
            embeddingQuery = "",
            embeddingEnabled = false,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        assertEquals(ShortCircuitReason.NO_CANDIDATES, outcome.shortCircuit)
        assertTrue(outcome.results.isEmpty())
    }

    @Test
    fun `all pruned short-circuits with ALL_PRUNED`() = runTest {
        // One segment candidate, rejected entity on it → prune drops it.
        entityRepository.insert(Entity(id = "e_r", type = EntityType.PERSON, canonicalName = "Rej", normalizedName = "rej"))
        entityRepository.insertWithMention(
            Entity(id = "e_r", type = EntityType.PERSON, canonicalName = "Rej", normalizedName = "rej"),
            EntityMention(entityId = "e_r", sourceSegmentId = "seg_rejected", mentionText = "m")
        )
        entityRepository.reject("e_r")

        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns listOf(
            SourceSegment(id = "seg_rejected", sourceId = "note_x", sourceType = SourceType.NOTE, text = "Rej mention", normalizedText = "rej mention", metadataJson = "{\"type\":\"segment\"}")
        )

        val outcome = retriever.retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "Rej"),
            embeddingQuery = "",
            embeddingEnabled = false,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        assertEquals(ShortCircuitReason.ALL_PRUNED, outcome.shortCircuit)
        assertTrue(outcome.trace?.prunedByRejectedEntity == 1)
    }

    @Test
    fun `note-level result with a rejected segment entity is pruned`() = runTest {
        entityRepository.insert(Entity(id = "e_x", type = EntityType.PERSON, canonicalName = "X", normalizedName = "x"))
        entityRepository.insertWithMention(
            Entity(id = "e_x", type = EntityType.PERSON, canonicalName = "X", normalizedName = "x"),
            EntityMention(entityId = "e_x", sourceSegmentId = "seg_rejected_note", mentionText = "m")
        )
        entityRepository.reject("e_x")

        // note-level BM25 hit for note_rejected.md
        coEvery { noteSearchIndex.search(any(), any(), any()) } returns listOf(
            com.noteflowai.app.data.search.NoteSearchIndex.SearchResult("note_rejected.md", "Rej", 5.0f, "rejected content", listOf("content"), "bm25")
        )
        // its segment resolves to a rejected entity
        coEvery { sourceSegmentRepository.getBySourceId("note_rejected.md") } returns listOf(
            SourceSegment(id = "seg_rejected_note", sourceId = "note_rejected.md", sourceType = SourceType.NOTE, text = "rejected content", normalizedText = "rejected content", metadataJson = "{\"type\":\"segment\"}")
        )
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns emptyList()

        val outcome = retriever.retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "rejected content"),
            embeddingQuery = "",
            embeddingEnabled = false,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        assertTrue(outcome.results.none { it.sourceId == "note_rejected.md" })
        assertEquals(ShortCircuitReason.ALL_PRUNED, outcome.shortCircuit)
    }
}