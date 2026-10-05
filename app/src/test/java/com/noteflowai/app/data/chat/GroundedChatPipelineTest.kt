package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.search.OnDeviceEmbedder
import com.noteflowai.app.data.search.RetrievalConfig
import com.noteflowai.app.data.search.RetrievalResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroundedChatPipelineTest {

    private val testScope = TestScope()
    private val segmentRepo = mockk<SourceSegmentRepository>()
    private val embedder = mockk<OnDeviceEmbedder>()
    private val preCallRefuser = mockk<PreCallRefuser>()

    private val text = "The supplier was ACME Corporation, based in Berlin."
    private val retrieval = RetrievalResult(
        sourceSegmentId = "seg-1",
        sourceId = "note.md",
        text = text,
        score = 0.9f,
        rank = 0
    )

    private fun pipeline(refuser: PreCallRefuser = preCallRefuser): GroundedChatPipeline {
        every { embedder.isReady() } returns true
        coEvery { embedder.embed(any()) } returns FloatArray(384)
        coEvery { segmentRepo.getById("seg-1") } returns
            SourceSegment(id = "seg-1", sourceId = "note.md", text = text, normalizedText = text.lowercase(), sourceType = SourceType.NOTE)
        return GroundedChatPipeline(
            scope = testScope,
            sourceSegmentRepository = segmentRepo,
            embedder = embedder,
            preCallRefuser = refuser,
            retrievalConfig = RetrievalConfig()
        )
    }

    @Test
    fun `happy path resolves to Complete with FullyValidated disposition`() = runTest {
        coEvery { preCallRefuser.decide(any(), any()) } returns PreCallRefuser.PreCallDecision.PROCEED
        val json = """
        {"answer":"The supplier was ACME Corporation.",
         "citations":[{"id":"cite-1","sourceType":"NOTE","sourceId":"note.md","chunkId":"seg-1","quoteStart":0,"quoteEnd":11}],
         "claims":[{"text":"The supplier was ACME Corporation","citationIds":["cite-1"],"uncertainty":"LOW"}]}
        """.trimIndent()
        val outcome = pipeline().run(listOf(retrieval), json, attempt = 1)
        assertTrue(outcome is GroundedChatPipeline.Result.Complete)
        val complete = outcome as GroundedChatPipeline.Result.Complete
        assertEquals(GroundingDisposition.FULLY_VALIDATED, complete.disposition)
        assertEquals(1, complete.validatedResponse?.validClaims)
    }

    @Test
    fun `malformed answer resolves to Complete with UNRESPONSIVE disposition`() = runTest {
        coEvery { preCallRefuser.decide(any(), any()) } returns PreCallRefuser.PreCallDecision.PROCEED
        val outcome = pipeline().run(listOf(retrieval), "{ not json", attempt = 1)
        assertTrue(outcome is GroundedChatPipeline.Result.Complete)
        val complete = outcome as GroundedChatPipeline.Result.Complete
        assertEquals(GroundingDisposition.UNRESPONSIVE, complete.disposition)
    }

    @Test
    fun `runWithRetry spends attempt 2 on malformed json then completes`() = runTest {
        coEvery { preCallRefuser.decide(any(), any()) } returns PreCallRefuser.PreCallDecision.PROCEED
        val valid = """
        {"answer":"The supplier was ACME Corporation.",
         "citations":[{"id":"cite-1","sourceType":"NOTE","sourceId":"note.md","chunkId":"seg-1","quoteStart":0,"quoteEnd":11}],
         "claims":[{"text":"The supplier was ACME Corporation","citationIds":["cite-1"],"uncertainty":"LOW"}]}
        """.trimIndent()
        val calls = mutableListOf<String?>()
        val outcome = pipeline().runWithRetry(listOf(retrieval)) { retryPrompt ->
            calls.add(retryPrompt)
            if (calls.size == 1) "{ truncated…" else valid
        }
        assertEquals(2, calls.size)
        assertTrue("repair retry must carry a prompt, got: $calls", calls[1] != null)
        assertTrue(outcome is GroundedChatPipeline.Result.Complete)
    }

    @Test
    fun `pre-call refusal returns Refuse result`() = runTest {
        val mockRefuser = mockk<PreCallRefuser>()
        coEvery { mockRefuser.decide(any(), any()) } returns
            PreCallRefuser.PreCallDecision.REFUSE(
                PreCallRefuser.PreCallDecision.RefusalReason.T1_EMPTY_RETRIEVAL,
                "No notes found"
            )
        val outcome = pipeline(mockRefuser).run(emptyList(), "{}", attempt = 1)
        assertTrue(outcome is GroundedChatPipeline.Result.Refuse)
        val refuse = outcome as GroundedChatPipeline.Result.Refuse
        assertEquals("No notes found", refuse.message)
        assertEquals(PreCallRefuser.PreCallDecision.RefusalReason.T1_EMPTY_RETRIEVAL, refuse.reason)
    }

    @Test
    fun `unsupported claim on attempt 1 yields Retry with specific prompt`() = runTest {
        coEvery { preCallRefuser.decide(any(), any()) } returns PreCallRefuser.PreCallDecision.PROCEED
        val json = """
        {"answer":"unverifiable",
         "citations":[{"id":"cite-1","sourceType":"NOTE","sourceId":"note.md","chunkId":"seg-1","quoteStart":0,"quoteEnd":5}],
         "claims":[{"text":"quantum teleportation is real","citationIds":["cite-1"],"uncertainty":"LOW"}]}
        """.trimIndent()
        val outcome = pipeline().run(listOf(retrieval), json, attempt = 1)
        assertTrue(outcome is GroundedChatPipeline.Result.Retry)
        val retry = outcome as GroundedChatPipeline.Result.Retry
        assertTrue(retry.retryPrompt.contains("entailment failed"))
    }
}
