package com.noteflowai.app.data.memory.db

import com.noteflowai.app.data.memory.model.ProcessingStage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3 granular-stage converter tests.
 *
 * ProcessingStage is stored as its `.name` string. Rows persisted before Phase 3 carry the
 * legacy coarse names; [Converters.toProcessingStage] must map them forward onto the granular
 * stages so an in-flight source resumes at the equivalent step rather than restarting. Unknown
 * strings (should never occur, but defensive) fall back to SEGMENTS_CREATED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConvertersStageTest {

    private val converters = Converters()

    @Test
    fun `legacy stage names map forward to granular equivalents`() {
        assertEquals(ProcessingStage.EXTRACTING_ENTITIES, converters.toProcessingStage("EXTRACTION"))
        assertEquals(ProcessingStage.EXTRACTING_TIMELINE, converters.toProcessingStage("RELATION_DETECTION"))
        assertEquals(ProcessingStage.BUILDING_SEMANTIC_INDEX, converters.toProcessingStage("EMBEDDING"))
        assertEquals(ProcessingStage.PREPARING_SEARCH, converters.toProcessingStage("INDEXING"))
    }

    @Test
    fun `current granular names round-trip unchanged`() {
        assertEquals(
            ProcessingStage.EXTRACTING_TIMELINE,
            converters.toProcessingStage(converters.fromProcessingStage(ProcessingStage.EXTRACTING_TIMELINE))
        )
        assertEquals(
            ProcessingStage.BUILDING_SEMANTIC_INDEX,
            converters.toProcessingStage(converters.fromProcessingStage(ProcessingStage.BUILDING_SEMANTIC_INDEX))
        )
        assertEquals(
            ProcessingStage.COMPLETE,
            converters.toProcessingStage(converters.fromProcessingStage(ProcessingStage.COMPLETE))
        )
    }

    @Test
    fun `unknown stage string falls back to segments created`() {
        assertEquals(ProcessingStage.SEGMENTS_CREATED, converters.toProcessingStage("NOT_A_STAGE"))
    }
}
