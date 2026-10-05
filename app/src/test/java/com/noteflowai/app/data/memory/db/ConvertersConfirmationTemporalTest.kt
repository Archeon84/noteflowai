package com.noteflowai.app.data.memory.db

import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.TemporalPrecision
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Converter round-trip tests for the Phase 4 enums ([ConfirmationState], [TemporalPrecision]).
 *
 * Both are stored as `.name` strings via [Converters]; unknown legacy strings (should not
 * occur, but defensive) fall back to a safe default so persistence never crashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConvertersConfirmationTemporalTest {

    private val converters = Converters()

    @Test
    fun `confirmation states round-trip as names`() {
        ConfirmationState.entries.forEach { state ->
            assertEquals(state, converters.toConfirmationState(converters.fromConfirmationState(state)))
        }
    }

    @Test
    fun `unknown confirmation string falls back to suggested`() {
        assertEquals(ConfirmationState.SUGGESTED, converters.toConfirmationState("NOT_A_STATE"))
    }

    @Test
    fun `temporal precisions round-trip as names`() {
        TemporalPrecision.entries.forEach { precision ->
            assertEquals(precision, converters.toTemporalPrecision(converters.fromTemporalPrecision(precision)))
        }
    }

    @Test
    fun `unknown precision string falls back to unknown`() {
        assertEquals(TemporalPrecision.UNKNOWN, converters.toTemporalPrecision("NOT_A_PRECISION"))
    }
}