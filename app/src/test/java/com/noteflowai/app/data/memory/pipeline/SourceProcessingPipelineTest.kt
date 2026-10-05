package com.noteflowai.app.data.memory.pipeline

import com.noteflowai.app.data.ConnectivityChecker
import com.noteflowai.app.data.memory.extraction.ExtractionResult
import com.noteflowai.app.data.memory.extraction.MemoryExtractionService
import com.noteflowai.app.data.memory.model.ProcessingStage
import com.noteflowai.app.data.memory.model.ProcessingState
import com.noteflowai.app.data.memory.model.ProcessingStatus
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.ProcessingStatusRepository
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.settings.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Unit tests for SourceProcessingPipeline (§7 ingestion pipeline).
 *
 * All seven collaborators are mocked. The status repository is backed by an
 * in-memory list so stage transitions and restart recovery can be asserted
 * without a real Room database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SourceProcessingPipelineTest {

    private lateinit var processingStatusRepository: ProcessingStatusRepository
    private lateinit var sourceSegmentRepository: SourceSegmentRepository
    private lateinit var extractionService: MemoryExtractionService
    private lateinit var segmentEmbeddingService: SegmentEmbeddingService
    private lateinit var segmentIndexService: SegmentIndexService
    private lateinit var relationDetectionService: RelationDetectionService
    private lateinit var settingsManager: SettingsManager
    private lateinit var connectivityChecker: ConnectivityChecker
    private lateinit var pipeline: SourceProcessingPipeline

    private val captured = mutableListOf<ProcessingStatus>()
    private val sourceId = "note_1"
    private val sourceType = SourceType.NOTE

    private fun segment(id: String = "seg_1") = SourceSegment(
        id = id,
        sourceId = sourceId,
        sourceType = SourceType.NOTE,
        text = "the quick brown fox jumps over the lazy dog",
        normalizedText = "the quick brown fox jumps over the lazy dog"
    )

    @Before
    fun setUp() {
        processingStatusRepository = mockk()
        sourceSegmentRepository = mockk()
        extractionService = mockk()
        segmentEmbeddingService = mockk()
        segmentIndexService = mockk()
        relationDetectionService = mockk()
        settingsManager = mockk()
        connectivityChecker = mockk()

        every { settingsManager.enableMemoryExtraction } returns flowOf(true)
        every { settingsManager.enableSegmentIndexing } returns flowOf(true)
        every { connectivityChecker.hasNetwork() } returns true
        every { connectivityChecker.startWatching(any()) } returns Unit

        pipeline = SourceProcessingPipeline(
            processingStatusRepository,
            sourceSegmentRepository,
            extractionService,
            segmentEmbeddingService,
            segmentIndexService,
            relationDetectionService,
            settingsManager,
            connectivityChecker
        )
    }

    /** Simulate the processing_status table with an in-memory list. */
    private fun stubStatusStore() {
        coEvery { processingStatusRepository.getBySourceId(sourceId) } answers { captured.lastOrNull() }
        coEvery { processingStatusRepository.upsert(capture(captured)) } returns Unit
    }

    private fun stubHappyPathStages() {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { extractionService.extract(any()) } returns ExtractionResult(entitiesCreated = 1, decisionsCreated = 1, commitmentsCreated = 1, otherMemoryCreated = 1, errors = emptyList())
        coEvery { segmentEmbeddingService.embedSource(sourceId) } returns true
        coEvery { segmentIndexService.indexSource(sourceId) } returns true
        coEvery { relationDetectionService.detectForSource(sourceId) } returns 3
    }

    @Test
    fun `fresh source runs through all enabled stages to complete`() = runTest {
        stubStatusStore()
        stubHappyPathStages()

        pipeline.processSource(sourceId, sourceType)

        val final = captured.last()
        assertEquals(ProcessingState.COMPLETED, final.status)
        assertEquals(ProcessingStage.COMPLETE, final.currentStage)
        assertNotEquals(null, final.completedAt)
        // Attempts incremented once per RUNNING transition (5 real stages).
        assertEquals(5, final.attempts)
        coVerify(exactly = 1) { extractionService.extract(any()) }
        coVerify(exactly = 1) { segmentEmbeddingService.embedSource(sourceId) }
        coVerify(exactly = 1) { segmentIndexService.indexSource(sourceId) }
        coVerify(exactly = 1) { relationDetectionService.detectForSource(sourceId) }
    }

    @Test
    fun `already completed source is skipped without touching stages`() = runTest {
        stubStatusStore()
        captured.add(
            ProcessingStatus(
                sourceId = sourceId,
                sourceType = sourceType,
                currentStage = ProcessingStage.COMPLETE,
                status = ProcessingState.COMPLETED,
                completedAt = 1_000L
            )
        )

        pipeline.processSource(sourceId, sourceType)

        assertEquals(1, captured.size)
        coVerify(exactly = 0) { extractionService.extract(any()) }
        coVerify(exactly = 0) { segmentIndexService.indexSource(any()) }
    }

    @Test
    fun `cancelled source is skipped`() = runTest {
        stubStatusStore()
        captured.add(
            ProcessingStatus(
                sourceId = sourceId,
                sourceType = sourceType,
                currentStage = ProcessingStage.EXTRACTING_ENTITIES,
                status = ProcessingState.CANCELLED
            )
        )

        pipeline.processSource(sourceId, sourceType)

        assertEquals(1, captured.size)
        coVerify(exactly = 0) { extractionService.extract(any()) }
    }

    @Test
    fun `extraction failure marks retryable and stops the pipeline`() = runTest {
        stubStatusStore()
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { extractionService.extract(any()) } returns ExtractionResult(entitiesCreated = 0, decisionsCreated = 0, commitmentsCreated = 0, otherMemoryCreated = 0, errors = listOf("LLM error"))

        pipeline.processSource(sourceId, sourceType)

        val final = captured.last()
        assertEquals(ProcessingState.FAILED_RETRYABLE, final.status)
        assertEquals(ProcessingStage.EXTRACTING_ENTITIES, final.currentStage)
        assertEquals("LLM error", final.error)
        coVerify(exactly = 0) { segmentEmbeddingService.embedSource(any()) }
        coVerify(exactly = 0) { segmentIndexService.indexSource(any()) }
        coVerify(exactly = 0) { relationDetectionService.detectForSource(any()) }
    }

    @Test
    fun `no segments is a permanent failure`() = runTest {
        stubStatusStore()
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns emptyList()

        pipeline.processSource(sourceId, sourceType)

        val final = captured.last()
        assertEquals(ProcessingState.FAILED_PERMANENT, final.status)
        assertEquals(ProcessingStage.EXTRACTING_ENTITIES, final.currentStage)
        coVerify(exactly = 0) { extractionService.extract(any()) }
    }

    @Test
    fun `disabled extraction stage is skipped and relation detection gated off`() = runTest {
        stubStatusStore()
        every { settingsManager.enableMemoryExtraction } returns flowOf(false)
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { segmentEmbeddingService.embedSource(sourceId) } returns true
        coEvery { segmentIndexService.indexSource(sourceId) } returns true

        pipeline.processSource(sourceId, sourceType)

        val final = captured.last()
        assertEquals(ProcessingState.COMPLETED, final.status)
        coVerify(exactly = 0) { extractionService.extract(any()) }
        coVerify(exactly = 0) { relationDetectionService.detectForSource(any()) }
        coVerify(exactly = 1) { segmentIndexService.indexSource(sourceId) }
        // Disabled stages park as SKIPPED (resumable), never COMPLETED, and
        // must not stamp completedAt.
        val skippedExtraction = captured.firstOrNull {
            it.currentStage == ProcessingStage.EXTRACTING_ENTITIES
        }
        assertEquals(ProcessingState.SKIPPED, skippedExtraction?.status)
        assertEquals(null, skippedExtraction?.completedAt)
    }

    @Test
    fun `disabled indexing stage skips embedding and indexing`() = runTest {
        stubStatusStore()
        every { settingsManager.enableSegmentIndexing } returns flowOf(false)
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { extractionService.extract(any()) } returns ExtractionResult(entitiesCreated = 1, decisionsCreated = 0, commitmentsCreated = 0, otherMemoryCreated = 0, errors = emptyList())
        coEvery { relationDetectionService.detectForSource(sourceId) } returns 0

        pipeline.processSource(sourceId, sourceType)

        val final = captured.last()
        assertEquals(ProcessingState.COMPLETED, final.status)
        coVerify(exactly = 0) { segmentEmbeddingService.embedSource(any()) }
        coVerify(exactly = 0) { segmentIndexService.indexSource(any()) }
        coVerify(exactly = 1) { extractionService.extract(any()) }
    }

    @Test
    fun `retry resumes from the failed stage without re-running earlier stages`() = runTest {
        stubStatusStore()
        captured.add(
            ProcessingStatus(
                sourceId = sourceId,
                sourceType = sourceType,
                currentStage = ProcessingStage.PREPARING_SEARCH,
                status = ProcessingState.FAILED_RETRYABLE,
                attempts = 4
            )
        )
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { extractionService.extract(any()) } returns ExtractionResult(entitiesCreated = 1, decisionsCreated = 0, commitmentsCreated = 0, otherMemoryCreated = 0, errors = emptyList())
        coEvery { segmentEmbeddingService.embedSource(sourceId) } returns true
        coEvery { segmentIndexService.indexSource(sourceId) } returns true
        coEvery { relationDetectionService.detectForSource(sourceId) } returns 0

        pipeline.processSource(sourceId, sourceType)

        val final = captured.last()
        assertEquals(ProcessingState.COMPLETED, final.status)
        coVerify(exactly = 0) { extractionService.extract(any()) }
        coVerify(exactly = 1) { segmentIndexService.indexSource(sourceId) }
        // EXTRACTING_TIMELINE precedes PREPARING_SEARCH in the granular order, so resuming from
        // PREPARING_SEARCH does not re-run relation detection (an earlier stage).
        coVerify(exactly = 0) { relationDetectionService.detectForSource(sourceId) }
    }

    @Test
    fun `recover pending flips stale running rows and re-enqueues every source`() = runTest {
        stubStatusStore()
        val otherId = "doc_1"
        coEvery { processingStatusRepository.getBySourceId(otherId) } returns null
        coEvery { processingStatusRepository.getIncomplete() } returns listOf(
            ProcessingStatus(
                sourceId = sourceId,
                sourceType = sourceType,
                currentStage = ProcessingStage.EXTRACTING_ENTITIES,
                status = ProcessingState.RUNNING,
                attempts = 1
            ),
            ProcessingStatus(
                sourceId = otherId,
                sourceType = SourceType.DOCUMENT,
                currentStage = ProcessingStage.SEGMENTS_CREATED,
                status = ProcessingState.PENDING
            )
        )
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { sourceSegmentRepository.getBySourceId(otherId) } returns listOf(segment("seg_2"))
        coEvery { extractionService.extract(any()) } returns ExtractionResult(entitiesCreated = 1, decisionsCreated = 0, commitmentsCreated = 0, otherMemoryCreated = 0, errors = emptyList())
        coEvery { segmentEmbeddingService.embedSource(any()) } returns true
        coEvery { segmentIndexService.indexSource(any()) } returns true
        coEvery { relationDetectionService.detectForSource(any()) } returns 0

        pipeline.recoverPending()

        // The stale RUNNING row was flipped to FAILED_RETRYABLE before processing.
        assertTrue(captured.any { it.sourceId == sourceId && it.status == ProcessingState.FAILED_RETRYABLE })
        coVerify(exactly = 2) { extractionService.extract(any()) }
    }

    @Test
    fun `concurrent duplicate calls process the source only once`() = runTest {
        stubStatusStore()
        val gate = CompletableDeferred<Unit>()
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { extractionService.extract(any()) } coAnswers {
            gate.await()
            ExtractionResult(entitiesCreated = 1, decisionsCreated = 0, commitmentsCreated = 0, otherMemoryCreated = 0, errors = emptyList())
        }
        coEvery { segmentEmbeddingService.embedSource(sourceId) } returns true
        coEvery { segmentIndexService.indexSource(sourceId) } returns true
        coEvery { relationDetectionService.detectForSource(sourceId) } returns 0

        val first = launch { pipeline.processSource(sourceId, sourceType) }
        runCurrent() // let the first call reach the extraction gate

        // Second call is a no-op while the first is in flight.
        pipeline.processSource(sourceId, sourceType)
        gate.complete(Unit)
        first.join()

        coVerify(exactly = 1) { extractionService.extract(any()) }
    }

    @Test
    fun `retry stage resets the row and re-enqueues the source`() = runTest {
        stubStatusStore()
        captured.add(
            ProcessingStatus(
                sourceId = sourceId,
                sourceType = sourceType,
                currentStage = ProcessingStage.PREPARING_SEARCH,
                status = ProcessingState.FAILED_RETRYABLE,
                attempts = 4
            )
        )
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { segmentEmbeddingService.embedSource(sourceId) } returns true
        coEvery { segmentIndexService.indexSource(sourceId) } returns true
        coEvery { relationDetectionService.detectForSource(sourceId) } returns 0

        pipeline.retryStage(sourceId, ProcessingStage.BUILDING_SEMANTIC_INDEX)

        val final = captured.last()
        assertEquals(ProcessingState.COMPLETED, final.status)
        coVerify(exactly = 0) { extractionService.extract(any()) }
        coVerify(exactly = 1) { segmentEmbeddingService.embedSource(sourceId) }
        coVerify(exactly = 1) { segmentIndexService.indexSource(sourceId) }
    }

    @Test
    fun `offline extraction fails fast without calling the LLM`() = runTest {
        stubStatusStore()
        every { connectivityChecker.hasNetwork() } returns false

        pipeline.processSource(sourceId, sourceType)

        val final = captured.last()
        assertEquals(ProcessingState.FAILED_RETRYABLE, final.status)
        assertEquals(ProcessingStage.EXTRACTING_ENTITIES, final.currentStage)
        assertEquals("No network connectivity", final.error)
        coVerify(exactly = 0) { extractionService.extract(any()) }
        coVerify(exactly = 0) { segmentEmbeddingService.embedSource(any()) }
        coVerify(exactly = 0) { segmentIndexService.indexSource(any()) }
        coVerify(exactly = 0) { relationDetectionService.detectForSource(any()) }
    }

    @Test
    fun `source at max attempts escalates to permanent failure`() = runTest {
        stubStatusStore()
        captured.add(
            ProcessingStatus(
                sourceId = sourceId,
                sourceType = sourceType,
                currentStage = ProcessingStage.EXTRACTING_ENTITIES,
                status = ProcessingState.FAILED_RETRYABLE,
                attempts = SourceProcessingPipeline.MAX_ATTEMPTS
            )
        )

        pipeline.processSource(sourceId, sourceType)

        val final = captured.last()
        assertEquals(ProcessingState.FAILED_PERMANENT, final.status)
        assertEquals(ProcessingStage.EXTRACTING_ENTITIES, final.currentStage)
        assertEquals("Max attempts reached", final.error)
        coVerify(exactly = 0) { extractionService.extract(any()) }
        coVerify(exactly = 0) { segmentIndexService.indexSource(any()) }
    }

    @Test
    fun `recover pending defers a recently failed retryable source`() = runTest {
        stubStatusStore()
        val now = System.currentTimeMillis()
        coEvery { processingStatusRepository.getIncomplete() } returns listOf(
            ProcessingStatus(
                sourceId = sourceId,
                sourceType = sourceType,
                currentStage = ProcessingStage.EXTRACTING_ENTITIES,
                status = ProcessingState.FAILED_RETRYABLE,
                attempts = 1,
                updatedAt = now
            )
        )

        pipeline.recoverPending()

        // Within the backoff window: the source is left alone, no transition
        // is written and no stage runs.
        assertEquals(0, captured.size)
        coVerify(exactly = 0) { extractionService.extract(any()) }
    }

    @Test
    fun `recover pending re-enqueues a retryable source past the backoff window`() = runTest {
        stubStatusStore()
        val now = System.currentTimeMillis()
        coEvery { processingStatusRepository.getIncomplete() } returns listOf(
            ProcessingStatus(
                sourceId = sourceId,
                sourceType = sourceType,
                currentStage = ProcessingStage.EXTRACTING_ENTITIES,
                status = ProcessingState.FAILED_RETRYABLE,
                attempts = 1,
                updatedAt = now - SourceProcessingPipeline.RETRY_BACKOFF_MS - 1_000L
            )
        )
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { extractionService.extract(any()) } returns ExtractionResult(entitiesCreated = 1, decisionsCreated = 0, commitmentsCreated = 0, otherMemoryCreated = 0, errors = emptyList())
        coEvery { segmentEmbeddingService.embedSource(sourceId) } returns true
        coEvery { segmentIndexService.indexSource(sourceId) } returns true
        coEvery { relationDetectionService.detectForSource(sourceId) } returns 0

        pipeline.recoverPending()

        val final = captured.last()
        assertEquals(ProcessingState.COMPLETED, final.status)
        coVerify(exactly = 1) { extractionService.extract(any()) }
    }
}
