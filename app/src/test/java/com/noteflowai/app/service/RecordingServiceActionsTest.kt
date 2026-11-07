package com.noteflowai.app.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingServiceActionsTest {

    @Test
    fun actionConstants_areDistinctAndWellFormed() {
        assertEquals("com.noteflowai.app.STOP_RECORDING", RecordingService.ACTION_STOP)
        assertEquals("com.noteflowai.app.PAUSE_RECORDING", RecordingService.ACTION_PAUSE)
        assertEquals("com.noteflowai.app.RESUME_RECORDING", RecordingService.ACTION_RESUME)

        assertNotEquals(RecordingService.ACTION_STOP, RecordingService.ACTION_PAUSE)
        assertNotEquals(RecordingService.ACTION_PAUSE, RecordingService.ACTION_RESUME)
    }

    @Test
    fun sharedFlows_tryEmitSucceedsWithBufferCapacity() = runTest {
        val pauseEmitted = RecordingService.pauseRequests.tryEmit(Unit)
        assertTrue(pauseEmitted)

        val resumeEmitted = RecordingService.resumeRequests.tryEmit(Unit)
        assertTrue(resumeEmitted)

        val stopEmitted = RecordingService.stopRequests.tryEmit(Unit)
        assertTrue(stopEmitted)
    }
}
