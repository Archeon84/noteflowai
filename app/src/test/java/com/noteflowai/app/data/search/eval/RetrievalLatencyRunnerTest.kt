package com.noteflowai.app.data.search.eval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.search.HybridRetriever
import com.noteflowai.app.data.search.RetrievalConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetrievalLatencyRunnerTest {

    @Test
    fun `report is written with median and p95 latency`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val retriever = mockk<HybridRetriever>(relaxed = true)
        coEvery { retriever.retrieve(any(), any(), any(), any(), any(), any(), any()) } answers {
            Thread.sleep(5)
            com.noteflowai.app.data.search.RetrievalOutcome(emptyList())
        }

        val reportFile = RetrievalLatencyRunner().run(
            context = context,
            retriever = retriever,
            config = RetrievalConfig(),
            parameters = Parameters(embeddingQuery = "q", embeddingEnabled = false, embeddingModel = "", provider = "", apiKey = "", baseUrl = ""),
            fixtures = RetrievalEvaluator.loadFixtures(context).take(3)
        )

        assertTrue(reportFile.exists())
        val json = reportFile.readText()
        assertTrue(json.contains("medianLatencyMs"))
        assertTrue(json.contains("p95LatencyMs"))
    }
}