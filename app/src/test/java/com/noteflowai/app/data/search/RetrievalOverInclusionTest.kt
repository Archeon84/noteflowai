package com.noteflowai.app.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.pipeline.SegmentEmbeddingService
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.memory.timeline.TimelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for over-inclusion: asking "what rag system can do?" returned
 * all RAG sources PLUS unrelated notes (ADHD, ABC Model) as source chips.
 *
 * Root cause: per-type min-max normalization maps a lone weak candidate to 0.5,
 * and fusion renormalizes by present weights only — so a weak single-channel
 * straggler fuses to ~0.5 and clears the default 0.28 threshold.
 *
 * Each test uses THREE BM25 hits so the note group is non-degenerate (with two
 * hits, min-max collapses the weaker to 0.0 — pre-existing behavior, unrelated
 * to this fix). The distractor is always alone in its type group.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetrievalOverInclusionTest {

    private lateinit var db: MemoryDatabase
    private lateinit var noteSearchIndex: NoteSearchIndex
    private lateinit var sourceSegmentRepository: SourceSegmentRepository

    private fun seg(id: String, sourceId: String, text: String) = SourceSegment(
        id = id, sourceId = sourceId, sourceType = SourceType.NOTE,
        text = text, normalizedText = text.lowercase(), metadataJson = "{\"type\":\"segment\"}"
    )

    private fun ragBm25Hits() = listOf(
        NoteSearchIndex.SearchResult("rag_system.md", "RAG System", 5.0f, "rag system capabilities", listOf("content"), "bm25"),
        NoteSearchIndex.SearchResult("rag_notes.md", "RAG Notes", 3.0f, "rag retrieval augmented", listOf("content"), "bm25"),
        NoteSearchIndex.SearchResult("rag_faq.md", "RAG FAQ", 2.0f, "rag questions answered", listOf("content"), "bm25")
    )

    private fun baseRetriever(
        embedIndex: EmbeddingIndex = mockk(),
        embedder: OnDeviceEmbedder = mockk(),
        segEmbedService: SegmentEmbeddingService = mockk()
    ): HybridRetriever {
        val context: Context = ApplicationProvider.getApplicationContext()
        return HybridRetriever(
            context,
            noteSearchIndex,
            embedIndex,
            mockk(), // remoteEmbeddingClient
            embedder,
            sourceSegmentRepository,
            mockk(), // decisionRepository
            mockk(), // commitmentRepository
            mockk(), // memoryRepository
            EntityRepository(db),
            segEmbedService,
            TimelineRepository(db),
            RetrievalConfig(topK = 10, minimumScore = 0.28f) // production defaults
        )
    }

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteSearchIndex = mockk()
        sourceSegmentRepository = mockk()

        // No-op prune gate (broad query: no dates/source types/entities) — every
        // note source resolves to a segment, like the real Filter & Eval Gate.
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns emptyList()
        coEvery { sourceSegmentRepository.getBySourceId(any()) } answers {
            val src = firstArg<String>()
            listOf(seg("seg_$src", src, "t"))
        }
        coEvery { sourceSegmentRepository.getByIds(any()) } answers {
            val ids = firstArg<List<String>>()
            ids.map { seg(it, "adhd_note.md", "coping strategies for focus") }
        }
    }

    @Test
    fun `weak single-channel segment distractor is rejected while strong matches survive`() = runTest {
        // The ADHD note appears ONLY as one weak segment-embedding hit
        // (cosine 0.30) — alone in the "segment_embedding" type group.
        coEvery { noteSearchIndex.search(any(), any(), any()) } returns ragBm25Hits()
        val embedder = mockk<OnDeviceEmbedder>()
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.5f, 0.5f)
        val embedIndex = mockk<EmbeddingIndex>()
        coEvery { embedIndex.search(any(), any()) } returns emptyList()
        val segEmbedService = mockk<SegmentEmbeddingService>()
        coEvery { segEmbedService.search(any(), any()) } returns listOf(
            SegmentEmbeddingService.SegmentEmbeddingHit(
                segmentId = "seg_adhd_1", sourceId = "adhd_note.md", score = 0.30f
            )
        )
        coEvery { sourceSegmentRepository.getBySourceId("adhd_note.md") } returns listOf(
            seg("seg_adhd_1", "adhd_note.md", "coping strategies for focus")
        )

        val outcome = baseRetriever(embedIndex, embedder, segEmbedService).retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "what rag system can do?"),
            embeddingQuery = "what rag system can do?",
            embeddingEnabled = true,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        val ids = outcome.results.map { it.sourceId }
        assertTrue("top RAG matches must survive, got: $ids", ids.contains("rag_system.md"))
        assertTrue(
            "unrelated ADHD note must not survive threshold, got: $ids",
            ids.none { it == "adhd_note.md" }
        )
    }

    @Test
    fun `local-only mode skips the remote embedding fallback`() = runTest {
        coEvery { noteSearchIndex.search(any(), any(), any()) } returns ragBm25Hits()
        val embedder = mockk<OnDeviceEmbedder>()
        coEvery { embedder.embed(any()) } returns null
        val remote = mockk<RemoteEmbeddingClient>(relaxed = true)
        val context: Context = ApplicationProvider.getApplicationContext()
        val retriever = HybridRetriever(
            context,
            noteSearchIndex,
            mockk(),
            remote,
            embedder,
            sourceSegmentRepository,
            mockk(),
            mockk(),
            mockk(),
            EntityRepository(db),
            mockk(),
            TimelineRepository(db),
            RetrievalConfig(topK = 10, minimumScore = 0.28f)
        )

        val outcome = retriever.retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "rag"),
            embeddingQuery = "rag",
            embeddingEnabled = true,
            embeddingModel = "",
            provider = "OpenAI",
            apiKey = "key",
            baseUrl = "https://api.openai.com/",
            localOnly = true
        )

        coVerify(exactly = 0) { remote.embed(any<String>(), any(), any(), any(), any()) }
        assertTrue(outcome.results.isNotEmpty())
    }

    @Test
    fun `substring segment hit on unrelated note is rejected`() = runTest {
        // Query "rag": BM25 finds RAG notes; the LIKE segment channel returns an
        // ADHD segment ("...coping strategies...") which carries the fixed 0.5
        // score and sits alone in the "segment" type group.
        coEvery { noteSearchIndex.search(any(), any(), any()) } returns ragBm25Hits()
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns listOf(
            seg("seg_adhd_1", "adhd_note.md", "coping strategies for focus")
        )
        coEvery { sourceSegmentRepository.getBySourceId("adhd_note.md") } returns listOf(
            seg("seg_adhd_1", "adhd_note.md", "coping strategies for focus")
        )

        val outcome = baseRetriever().retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "rag"),
            embeddingQuery = "",
            embeddingEnabled = false,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        val ids = outcome.results.map { it.sourceId }
        assertTrue("top RAG matches must survive, got: $ids", ids.contains("rag_system.md"))
        // The ADHD segment hit must not promote its whole note past the threshold.
        assertTrue(
            "unrelated ADHD note must not survive threshold, got: $ids",
            ids.none { it == "adhd_note.md" }
        )
    }

    @Test
    fun `computeSegmentRelevance rejects substring word match and scores exact word highly`() {
        val retriever = baseRetriever()
        // "rag" must NOT match inside "paragraph"
        val irrelevantScore = retriever.computeSegmentRelevance(
            "rag",
            "Reading a simple paragraph aloud takes far longer than expected."
        )
        assertEquals(0.0f, irrelevantScore, 0.0001f)

        // "rag" matches actual word "rag"
        val relevantScore = retriever.computeSegmentRelevance(
            "rag",
            "A RAG architecture consists of retrieval and generation."
        )
        assertTrue("relevant score must be high, got $relevantScore", relevantScore >= 0.8f)

        // "rag system" matches exact phrase
        val exactPhraseScore = retriever.computeSegmentRelevance(
            "rag system",
            "Building an enterprise RAG system with retrieval."
        )
        assertEquals(1.0f, exactPhraseScore, 0.0001f)
    }

    @Test
    fun `dyslexia note with paragraph containing rag substring is rejected for rag query`() = runTest {
        coEvery { noteSearchIndex.search(any(), any(), any()) } returns ragBm25Hits()
        // SQL LIKE '%rag%' matched "paragraph" in the Dyslexia note segment:
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns listOf(
            seg("seg_dyslexia_1", "What is Dyslexia.json", "Reading a simple paragraph aloud takes far longer than expected.")
        )
        coEvery { sourceSegmentRepository.getBySourceId("What is Dyslexia.json") } returns listOf(
            seg("seg_dyslexia_1", "What is Dyslexia.json", "Reading a simple paragraph aloud takes far longer than expected.")
        )

        val outcome = baseRetriever().retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "what is rag system?"),
            embeddingQuery = "",
            embeddingEnabled = false,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        val ids = outcome.results.map { it.sourceId }
        assertTrue("top RAG matches must survive, got: $ids", ids.contains("rag_system.md"))
        assertTrue(
            "dyslexia note matched via substring 'paragraph' must be rejected, got: $ids",
            ids.none { it == "What is Dyslexia.json" }
        )
    }
}
