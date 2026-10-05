package com.noteflowai.app.data.memory.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Phase 3 presentation mapping [ProcessingStatus.toUiModel].
 *
 * Verifies that each durable pipeline state translates to the user-facing status vocabulary a
 * card / detail screen renders, including the granular running stages and local-vs-cloud flag.
 */
class ProcessingUiStatusTest {

    private fun row(
        status: ProcessingState,
        stage: ProcessingStage = ProcessingStage.SEGMENTS_CREATED,
        error: String? = null
    ) = ProcessingStatus(
        sourceId = "note_1",
        sourceType = SourceType.NOTE,
        currentStage = stage,
        status = status,
        error = error
    )

    @Test
    fun `completed maps to ready and is terminal`() {
        val m = row(ProcessingState.COMPLETED, ProcessingStage.COMPLETE).toUiModel()
        assertEquals(UserFacingStatus.READY, m.status)
        assertTrue(m.isTerminal)
        assertFalse(m.canRetry)
        assertFalse(m.canCancel)
        assertFalse(m.isCloud)
    }

    @Test
    fun `permanent failure maps to failed with a message and retries`() {
        val m = row(ProcessingState.FAILED_PERMANENT, ProcessingStage.EXTRACTING_ENTITIES, "Bad data").toUiModel()
        assertEquals(UserFacingStatus.FAILED, m.status)
        assertTrue(m.isTerminal)
        assertTrue(m.canRetry)
        assertEquals("Bad data", m.message)
        assertEquals(ProcessingStage.EXTRACTING_ENTITIES, m.stage)
        assertFalse(m.canCancel)
    }

    @Test
    fun `retryable failure maps to needs attention with retry`() {
        val m = row(ProcessingState.FAILED_RETRYABLE, ProcessingStage.PREPARING_SEARCH, "LLM error").toUiModel()
        assertEquals(UserFacingStatus.NEEDS_ATTENTION, m.status)
        assertTrue(m.isTerminal)
        assertTrue(m.canRetry)
        assertEquals("LLM error", m.detail)
        assertEquals(ProcessingStage.PREPARING_SEARCH, m.stage)
    }

    @Test
    fun `cancelled maps to needs attention with resume semantics`() {
        val m = row(ProcessingState.CANCELLED, ProcessingStage.EXTRACTING_TIMELINE).toUiModel()
        assertEquals(UserFacingStatus.NEEDS_ATTENTION, m.status)
        assertTrue(m.canRetry)
        assertFalse(m.canCancel)
        assertTrue(m.isTerminal)
        assertEquals(ProcessingStage.EXTRACTING_TIMELINE, m.stage)
    }

    @Test
    fun `pending maps to saved locally and is cancellable`() {
        val m = row(ProcessingState.PENDING, ProcessingStage.SEGMENTS_CREATED).toUiModel()
        assertEquals(UserFacingStatus.SAVED_LOCALLY, m.status)
        assertTrue(m.canCancel)
        assertFalse(m.canRetry)
        assertFalse(m.isTerminal)
        assertNull(m.message)
    }

    @Test
    fun `running transcribing maps to transcribing and is local`() {
        val m = row(ProcessingState.RUNNING, ProcessingStage.TRANSCRIBING).toUiModel()
        assertEquals(UserFacingStatus.TRANSCRIBING, m.status)
        assertTrue(m.canCancel)
        assertFalse(m.isCloud)
    }

    @Test
    fun `running extraction stages map to granular statuses and are cloud`() {
        val entities = row(ProcessingState.RUNNING, ProcessingStage.EXTRACTING_ENTITIES).toUiModel()
        assertEquals(UserFacingStatus.EXTRACTING_ENTITIES, entities.status)
        assertTrue(entities.isCloud)

        val timeline = row(ProcessingState.RUNNING, ProcessingStage.EXTRACTING_TIMELINE).toUiModel()
        assertEquals(UserFacingStatus.EXTRACTING_TIMELINE, timeline.status)
        assertTrue(timeline.isCloud)
    }

    @Test
    fun `running indexing stages map to granular statuses and are on device`() {
        val semantic = row(ProcessingState.RUNNING, ProcessingStage.BUILDING_SEMANTIC_INDEX).toUiModel()
        assertEquals(UserFacingStatus.BUILDING_SEMANTIC_INDEX, semantic.status)
        assertFalse(semantic.isCloud)

        val search = row(ProcessingState.RUNNING, ProcessingStage.PREPARING_SEARCH).toUiModel()
        assertEquals(UserFacingStatus.PREPARING_SEARCH, search.status)
        assertFalse(search.isCloud)
        assertTrue(search.canCancel)
    }

    @Test
    fun `running unknown stage falls back to generic processing`() {
        val m = row(ProcessingState.RUNNING, ProcessingStage.SEGMENTS_CREATED).toUiModel()
        assertEquals(UserFacingStatus.PROCESSING, m.status)
        assertTrue(m.canCancel)
        assertFalse(m.isCloud)
    }
}
