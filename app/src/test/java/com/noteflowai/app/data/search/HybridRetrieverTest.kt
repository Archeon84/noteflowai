package com.noteflowai.app.data.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.DecisionStatus
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.pipeline.SegmentEmbeddingService
import com.noteflowai.app.data.memory.repository.CommitmentRepository
import com.noteflowai.app.data.memory.repository.DecisionRepository
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.repository.MemoryRepository
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.memory.timeline.TimelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
 * Unit tests for the batch segment lookup in HybridRetriever (Phase 8a perf).
 *
 * Verifies that decision retrieval resolves segments through a single
 * [SourceSegmentRepository.getByIds] batch query instead of one
 * [SourceSegmentRepository.getById] call per decision (N+1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HybridRetrieverTest {

    private lateinit var context: Context
    private lateinit var noteSearchIndex: NoteSearchIndex
    private lateinit var embeddingIndex: EmbeddingIndex
    private lateinit var remoteEmbeddingClient: RemoteEmbeddingClient
    private lateinit var onDeviceEmbedder: OnDeviceEmbedder
    private lateinit var sourceSegmentRepository: SourceSegmentRepository
    private lateinit var decisionRepository: DecisionRepository
    private lateinit var commitmentRepository: CommitmentRepository
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var entityRepository: EntityRepository
    private lateinit var segmentEmbeddingService: SegmentEmbeddingService
    private lateinit var timelineRepository: TimelineRepository
    private lateinit var retriever: HybridRetriever

    private val sourceSegmentId = "seg_1"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        noteSearchIndex = mockk()
        embeddingIndex = mockk()
        remoteEmbeddingClient = mockk()
        onDeviceEmbedder = mockk()
        sourceSegmentRepository = mockk()
        decisionRepository = mockk()
        commitmentRepository = mockk()
        memoryRepository = mockk()
        entityRepository = mockk()
        segmentEmbeddingService = mockk()
        timelineRepository = mockk()

        retriever = HybridRetriever(
            context,
            noteSearchIndex,
            embeddingIndex,
            remoteEmbeddingClient,
            onDeviceEmbedder,
            sourceSegmentRepository,
            decisionRepository,
            commitmentRepository,
            memoryRepository,
            entityRepository,
            segmentEmbeddingService,
            timelineRepository
        )
    }

    @Test
    fun `decision lookup resolves segments via one batch getByIds query`() = runTest {
        val decision = Decision(
            id = "dec_1",
            memoryObjectId = "mem_1",
            statement = "we should migrate to postgres",
            reason = "scalability",
            status = DecisionStatus.CONFIRMED,
            sourceSegmentId = sourceSegmentId,
            confidence = 0.9f
        )
        coEvery { decisionRepository.getActive() } returns listOf(decision)
        coEvery { sourceSegmentRepository.getByIds(any()) } returns listOf(
            SourceSegment(
                id = sourceSegmentId,
                sourceId = "src_1",
                sourceType = SourceType.NOTE,
                text = "we should migrate to postgres",
                normalizedText = "we should migrate to postgres"
            )
        )

        val plan = QueryPlan(intent = QueryIntent.DECISION_LOOKUP, queryText = "postgres migration")
        val results = retriever.retrieve(plan, "", false, "", "", "", "").results

        // The batch path is exercised exactly once; no per-decision getById.
        coVerify(exactly = 1) { sourceSegmentRepository.getByIds(any()) }
        coVerify(exactly = 0) { sourceSegmentRepository.getById(any()) }

        assertEquals(1, results.size)
        val result = results[0]
        assertEquals(sourceSegmentId, result.sourceSegmentId)
        assertEquals("dec_1", result.memoryObjectId)
        assertEquals(SourceType.NOTE, result.sourceType)
        assertTrue(result.text.contains("postgres"))
    }

    @Test
    fun `decision lookup with no referenced ids skips the batch query`() = runTest {
        coEvery { decisionRepository.getActive() } returns emptyList()

        val plan = QueryPlan(intent = QueryIntent.DECISION_LOOKUP, queryText = "anything")
        val results = retriever.retrieve(plan, "", false, "", "", "", "").results

        coVerify(exactly = 0) { sourceSegmentRepository.getByIds(any()) }
        assertTrue(results.isEmpty())
    }
}
