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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3 granular-stage ordering tests for [SourceProcessingPipeline].
 *
 * Verifies that the refined STAGE_ORDER runs in the user-facing granular sequence and that the
 * audio-only TRANSCRIBING marker is included for audio but skipped for every other source type.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SourceProcessingPipelineStageTest {

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

    private fun segment() = SourceSegment(
        id = "seg_1",
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

        coEvery { processingStatusRepository.getBySourceId(sourceId) } answers { captured.lastOrNull() }
        coEvery { processingStatusRepository.upsert(capture(captured)) } returns Unit
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { extractionService.extract(any()) } returns ExtractionResult(entitiesCreated = 1, decisionsCreated = 1, commitmentsCreated = 1, otherMemoryCreated = 1, errors = emptyList())
        coEvery { segmentEmbeddingService.embedSource(sourceId) } returns true
        coEvery { segmentIndexService.indexSource(sourceId) } returns true
        coEvery { relationDetectionService.detectForSource(sourceId) } returns 3
    }

    /** The stages that reached RUNNING during a run, in write order. */
    private fun runningStages(): List<ProcessingStage> =
        captured.filter { it.status == ProcessingState.RUNNING }.map { it.currentStage }

    @Test
    fun `note runs the granular stages in order with transcribing skipped`() = runTest {
        pipeline.processSource(sourceId, SourceType.NOTE)

        assertEquals(
            listOf(
                ProcessingStage.SEGMENTS_CREATED,
                ProcessingStage.EXTRACTING_ENTITIES,
                ProcessingStage.EXTRACTING_TIMELINE,
                ProcessingStage.BUILDING_SEMANTIC_INDEX,
                ProcessingStage.PREPARING_SEARCH
            ),
            runningStages()
        )
        // TRANSCRIBING is audio-only: it must never run for a note.
        assertTrue(runningStages().none { it == ProcessingStage.TRANSCRIBING })
        assertEquals(ProcessingState.COMPLETED, captured.last().status)
    }

    @Test
    fun `audio includes the transcribing marker stage`() = runTest {
        pipeline.processSource(sourceId, SourceType.AUDIO)

        assertEquals(
            listOf(
                ProcessingStage.SEGMENTS_CREATED,
                ProcessingStage.TRANSCRIBING,
                ProcessingStage.EXTRACTING_ENTITIES,
                ProcessingStage.EXTRACTING_TIMELINE,
                ProcessingStage.BUILDING_SEMANTIC_INDEX,
                ProcessingStage.PREPARING_SEARCH
            ),
            runningStages()
        )
        assertEquals(ProcessingState.COMPLETED, captured.last().status)
    }

    @Test
    fun `retry resumes from the granular failed stage`() = runTest {
        captured.add(
            ProcessingStatus(
                sourceId = sourceId,
                sourceType = SourceType.NOTE,
                currentStage = ProcessingStage.BUILDING_SEMANTIC_INDEX,
                status = ProcessingState.FAILED_RETRYABLE,
                attempts = 4
            )
        )

        pipeline.retryStage(sourceId, ProcessingStage.BUILDING_SEMANTIC_INDEX)

        // Resumes from BUILDING_SEMANTIC_INDEX; earlier stages are not re-run.
        assertEquals(
            listOf(
                ProcessingStage.BUILDING_SEMANTIC_INDEX,
                ProcessingStage.PREPARING_SEARCH
            ),
            runningStages()
        )
        assertEquals(ProcessingState.COMPLETED, captured.last().status)
    }
}
