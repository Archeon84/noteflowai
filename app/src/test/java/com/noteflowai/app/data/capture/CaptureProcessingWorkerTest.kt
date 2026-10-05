package com.noteflowai.app.data.capture

import androidx.work.ListenableWorker
import com.noteflowai.app.data.memory.model.ProcessingStage
import com.noteflowai.app.data.memory.model.ProcessingState
import com.noteflowai.app.data.memory.model.ProcessingStatus
import com.noteflowai.app.data.memory.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the pure outcome-mapping logic of [CaptureProcessingWorker]: how persisted pipeline
 * state maps to a WorkManager [ListenableWorker.Result].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureProcessingWorkerTest {

    private fun status(state: ProcessingState): ProcessingStatus = ProcessingStatus(
        sourceId = "note_1",
        sourceType = SourceType.NOTE,
        currentStage = ProcessingStage.PREPARING_SEARCH,
        status = state,
        attempts = 2,
        updatedAt = System.currentTimeMillis()
    )

    @Test
    fun `completed pipeline state maps to success`() {
        val result = CaptureProcessingWorker.outcomeFor(status(ProcessingState.COMPLETED), isStopped = false)
        assertTrue(result is ListenableWorker.Result.Success)
    }

    @Test
    fun `permanent failure maps to failure`() {
        val result = CaptureProcessingWorker.outcomeFor(status(ProcessingState.FAILED_PERMANENT), isStopped = false)
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `retryable failure maps to retry`() {
        val result = CaptureProcessingWorker.outcomeFor(status(ProcessingState.FAILED_RETRYABLE), isStopped = false)
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `running state maps to retry (not terminal)`() {
        val result = CaptureProcessingWorker.outcomeFor(status(ProcessingState.RUNNING), isStopped = false)
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `missing status row maps to retry`() {
        val result = CaptureProcessingWorker.outcomeFor(null, isStopped = false)
        assertTrue(result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `stopped worker maps to retry regardless of state`() {
        val result = CaptureProcessingWorker.outcomeFor(status(ProcessingState.COMPLETED), isStopped = true)
        assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
        assertTrue(result is ListenableWorker.Result.Retry)
    }
}