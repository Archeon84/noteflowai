package com.noteflowai.app.data.eval

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PerformanceBenchmarkTest {

    @Test
    fun testNoteSaveLatencyUnderTarget() {
        runBlocking {
            val tempDir = File.createTempFile("bench", "dir").also { it.delete(); it.mkdirs() }
            val runner = PerformanceBenchmarkRunner()

            val saveLatencyMs = runner.measureNoteSaveLatency(tempDir, sampleCount = 10)
            assertTrue("Note save latency $saveLatencyMs ms must be < 500 ms", saveLatencyMs < 500L)

            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSearchLatencyUnderTarget() {
        runBlocking {
            val runner = PerformanceBenchmarkRunner()
            val searchLatencyMs = runner.measureSearchLatency(sampleCount = 20)
            assertTrue("Search query latency $searchLatencyMs ms must be < 150 ms", searchLatencyMs < 150L)
        }
    }
}
