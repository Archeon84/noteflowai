package com.noteflowai.app.data.capture

import com.noteflowai.app.data.memory.dao.ProcessingStatusDao
import com.noteflowai.app.data.memory.model.ProcessingStage
import com.noteflowai.app.data.memory.model.ProcessingState
import com.noteflowai.app.data.memory.model.ProcessingStatus
import com.noteflowai.app.data.memory.model.SourceType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessDeathRecoveryTest {

    @Test
    fun testGetIncompleteRetrievesInterruptedSources() {
        runBlocking {
            val dao = mockk<ProcessingStatusDao>()
            val interrupted = listOf(
                ProcessingStatus(
                    sourceId = "interrupted_note.md",
                    sourceType = SourceType.NOTE,
                    currentStage = ProcessingStage.EXTRACTING_ENTITIES,
                    status = ProcessingState.RUNNING,
                    attempts = 1,
                    error = null,
                    startedAt = 1000L,
                    updatedAt = 2000L
                )
            )

            coEvery { dao.getIncomplete() } returns interrupted

            val result = dao.getIncomplete()
            assertEquals(1, result.size)
            assertEquals("interrupted_note.md", result[0].sourceId)
            assertEquals(ProcessingState.RUNNING, result[0].status)
        }
    }
}
