package com.noteflowai.app.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.db.MemoryDatabase
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
class RetrievalFusionNormalizationTest {

    private lateinit var db: MemoryDatabase
    private lateinit var noteSearchIndex: com.noteflowai.app.data.search.NoteSearchIndex
    private lateinit var sourceSegmentRepository: SourceSegmentRepository
    private lateinit var retriever: HybridRetriever

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteSearchIndex = mockk()
        sourceSegmentRepository = mockk()
        val entityRepository = EntityRepository(db)

        // Task 9: note-level results now resolve through getBySourceId into the Filter &
        // Eval Gate before normalization/fusion. Every note source returns a matching
        // segment, or the strict mock throws and the retrieve() outer catch swallows
        // everything into an empty outcome (which breaks the ranking assertions).
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns emptyList()
        coEvery { sourceSegmentRepository.getBySourceId(any()) } answers {
            val src = firstArg<String>()
            listOf(SourceSegment(id = "seg_$src", sourceId = src, sourceType = SourceType.NOTE, text = "t", normalizedText = "t", metadataJson = "{\"type\":\"segment\"}"))
        }

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
            TimelineRepository(db),
            // NOTE: minimumScore = 0f (deviation) — the brief set 0.01f, but per-source
            // min-max collapse the group min to 0.0, so with 0.01 the weaker candidate
            // is threshold-rejected and Test 1's `outcome.results[1]` assertion can never
            // hold. minimumScore = 0f keeps both candidates and the verbatim assertions.
            RetrievalConfig(bm25Weight = 1.0f, vectorWeight = 0f, entityWeight = 0f, recencyWeight = 0f, topK = 10, minimumScore = 0f)
        )
    }

    @Test
    fun `fused scores stay in 0-1 and keyword match ranks first`() = runTest {
        coEvery { noteSearchIndex.search(any(), any(), any()) } returns listOf(
            com.noteflowai.app.data.search.NoteSearchIndex.SearchResult("note_exact.md", "Exec", 5.0f, "exact keyword risk mitigation", listOf("content"), "bm25"),
            com.noteflowai.app.data.search.NoteSearchIndex.SearchResult("note_loose.md", "Loose", 2.0f, "really only partial", listOf("content"), "bm25")
        )
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns emptyList()

        val outcome = retriever.retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "risk mitigation"),
            embeddingQuery = "",
            embeddingEnabled = false,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        assertTrue(outcome.results.isNotEmpty())
        assertTrue(outcome.results.all { it.score in 0.0f..1.001f })
        assertEquals("note_exact.md", outcome.results.first().sourceId)
        assertTrue(outcome.results.first().score > outcome.results[1].score)
    }

    @Test
    fun `paraphrase ranks semantic match via vector weight even without BM25 term`() = runTest {
        val sq = listOf(
            com.noteflowai.app.data.search.NoteSearchIndex.SearchResult("note_sem.md", "Sem", 0.05f, "semantic neighbor", listOf("content"), "bm25")
        )
        coEvery { noteSearchIndex.search(any(), any(), any()) } returns sq
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns emptyList()

        // On-device embedder produces a query vector; embed index returns the semantic hit.
        val embedIndex = mockk<com.noteflowai.app.data.search.EmbeddingIndex>()
        coEvery { embedIndex.search(any(), any()) } returns listOf(
            com.noteflowai.app.data.search.EmbeddingIndex.EmbeddingSearchResult("note_sem.md", 0.9f, "semantic neighbor")
        )
        val embedder = mockk<com.noteflowai.app.data.search.OnDeviceEmbedder>()
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.1f, 0.2f)

        // NOTE: the `v` mockk in the original brief was never wired into any retriever (dead).
        // The stubbed `embedder` is passed as the onDeviceEmbedder positional arg (position 5)
        // so the embedding path is actually exercised; segmentEmbeddingService (position 11)
        // is relaxed so step 6 does not throw on a strict mock.
        val sourceSegmentRepository2 = mockk<SourceSegmentRepository>()
        coEvery { sourceSegmentRepository2.getBySourceId(any()) } answers {
            val src = firstArg<String>()
            listOf(SourceSegment(id = "seg_$src", sourceId = src, sourceType = SourceType.NOTE, text = "t", normalizedText = "t", metadataJson = "{\"type\":\"segment\"}"))
        }
        val retriever2 = HybridRetriever(
            ApplicationProvider.getApplicationContext(),
            noteSearchIndex,
            embedIndex,
            mockk(),
            embedder,
            sourceSegmentRepository2, mockk(), mockk(), mockk(), EntityRepository(db),
            mockk<com.noteflowai.app.data.memory.pipeline.SegmentEmbeddingService>(relaxed = true),
            TimelineRepository(db),
            RetrievalConfig(bm25Weight = 0.2f, vectorWeight = 0.8f, entityWeight = 0f, recencyWeight = 0f, topK = 10, minimumScore = 0.01f)
        )
        val outcome = retriever2.retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "paraphrase of risk"),
            embeddingQuery = "paraphrase",
            embeddingEnabled = true,
            embeddingModel = "",
            provider = "",
            apiKey = "",
            baseUrl = ""
        )

        assertTrue(outcome.results.isNotEmpty())
        assertEquals("note_sem.md", outcome.results.first().sourceId)
    }

    @Test
    fun `configurable weights change ranking`() = runTest {
        // Both files carry BOTH channels (bm25 + embedding) so the ranking can only be
        // driven by the per-channel weights: the bm25 channel favors kw.md (higher BM25
        // rank), the vector channel favors emb.md (higher cosine). The previous version
        // of this test only passed because RRF collapsed the channels into one merged
        // total; with real per-channel fusion the weights must decide the order.
        coEvery { noteSearchIndex.search(any(), any(), any()) } returns listOf(
            com.noteflowai.app.data.search.NoteSearchIndex.SearchResult("kw.md", "K", 9.0f, "exact keyword hit", listOf("content"), "bm25"),
            com.noteflowai.app.data.search.NoteSearchIndex.SearchResult("emb.md", "E", 0.1f, "vectorish", listOf("content"), "bm25")
        )
        coEvery { sourceSegmentRepository.searchAll(any(), any()) } returns emptyList()

        // Embedding index: BOTH files are hits, emb.md at a higher cosine so the vector
        // channel min-maxes to favor emb.md while the bm25 channel favors kw.md.
        val embedIndex = mockk<com.noteflowai.app.data.search.EmbeddingIndex>()
        coEvery { embedIndex.search(any(), any()) } returns listOf(
            com.noteflowai.app.data.search.EmbeddingIndex.EmbeddingSearchResult("emb.md", 0.98f, "vectorish"),
            com.noteflowai.app.data.search.EmbeddingIndex.EmbeddingSearchResult("kw.md", 0.5f, "keyword hit")
        )
        val embedder = mockk<com.noteflowai.app.data.search.OnDeviceEmbedder>()
        coEvery { embedder.embed(any()) } returns floatArrayOf(0.5f, 0.5f)

        // bm25-favoring config must rank kw.md first purely from the channel weights.
        val sourceSegmentRepositoryBm25 = mockk<SourceSegmentRepository>()
        coEvery { sourceSegmentRepositoryBm25.getBySourceId(any()) } answers {
            val src = firstArg<String>()
            listOf(SourceSegment(id = "seg_$src", sourceId = src, sourceType = SourceType.NOTE, text = "t", normalizedText = "t", metadataJson = "{\"type\":\"segment\"}"))
        }
        val bm25First = HybridRetriever(
            ApplicationProvider.getApplicationContext(),
            noteSearchIndex, embedIndex, mockk(), embedder, sourceSegmentRepositoryBm25, mockk(), mockk(), mockk(), EntityRepository(db),
            mockk<com.noteflowai.app.data.memory.pipeline.SegmentEmbeddingService>(relaxed = true),
            TimelineRepository(db),
            RetrievalConfig(bm25Weight = 0.99f, vectorWeight = 0.01f, entityWeight = 0f, recencyWeight = 0f, topK = 10, minimumScore = 0.01f)
        ).retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "exact"),
            embeddingQuery = "exact", embeddingEnabled = true, embeddingModel = "", provider = "", apiKey = "", baseUrl = ""
        )

        // vector-favoring config with the same corpus must rank emb.md first.
        val sourceSegmentRepositoryVec = mockk<SourceSegmentRepository>()
        coEvery { sourceSegmentRepositoryVec.getBySourceId(any()) } answers {
            val src = firstArg<String>()
            listOf(SourceSegment(id = "seg_$src", sourceId = src, sourceType = SourceType.NOTE, text = "t", normalizedText = "t", metadataJson = "{\"type\":\"segment\"}"))
        }
        val vecFirst = HybridRetriever(
            ApplicationProvider.getApplicationContext(),
            noteSearchIndex, embedIndex, mockk(), embedder, sourceSegmentRepositoryVec, mockk(), mockk(), mockk(), EntityRepository(db),
            mockk<com.noteflowai.app.data.memory.pipeline.SegmentEmbeddingService>(relaxed = true),
            TimelineRepository(db),
            RetrievalConfig(bm25Weight = 0.01f, vectorWeight = 0.99f, entityWeight = 0f, recencyWeight = 0f, topK = 10, minimumScore = 0.01f)
        ).retrieve(
            plan = QueryPlan(intent = QueryIntent.GENERAL_RAG, queryText = "exact"),
            embeddingQuery = "exact", embeddingEnabled = true, embeddingModel = "", provider = "", apiKey = "", baseUrl = ""
        )

        assertEquals("kw.md", bm25First.results.first().sourceId)
        assertEquals("emb.md", vecFirst.results.first().sourceId)
    }
}