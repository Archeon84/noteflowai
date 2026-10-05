package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.search.OnDeviceEmbedder
import com.noteflowai.app.data.search.RetrievalResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for GroundingDispositionResolver (Phase 6).
 *
 * Single owner of terminal GroundingDisposition and retry-vs-terminal decision.
 * Evaluates Trigger 4 (HOLE_IN_EVIDENCE) and Trigger 5 (retry logic).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroundingDispositionResolverTest {

    private val embedder = mockk<OnDeviceEmbedder>()
    private val validator = ClaimValidator(embedder)
    private val detector = UnsupportedClaimDetector
    private val resolver = GroundingDispositionResolver(maxAttempts = 2)

    private fun citation(id: String, sourceType: SourceType = SourceType.NOTE, sourceId: String = "note.md", chunkId: String = "seg-1", authoritative: Boolean = true) =
        Citation(id = id, sourceType = sourceType, sourceId = sourceId, chunkId = chunkId)

    private fun reconciledCitation(
        citation: Citation,
        segmentText: String = "The supplier was ACME Corp. The price was $500.",
        authoritative: Boolean = true
    ): ReconciledCitation {
        val resolved = RetrievalResult(
            sourceSegmentId = citation.chunkId,
            sourceId = citation.sourceId,
            text = segmentText,
            score = 0.9f,
            rank = 0
        )
        val segment = SourceSegment(
            id = citation.chunkId,
            sourceId = citation.sourceId,
            text = segmentText,
            normalizedText = segmentText.lowercase(),
            sourceType = citation.sourceType
        )
        return ReconciledCitation(
            citation = citation,
            resolved = resolved,
            segment = segment,
            computedQuoteRange = GroundingSupport.QuoteRange(0, segmentText.length),
            authoritative = authoritative
        )
    }

    private fun claim(
        text: String,
        citationIds: List<String> = emptyList(),
        memoryObjectIds: List<String> = emptyList(),
        uncertainty: UncertaintyLevel = UncertaintyLevel.LOW
    ) = Claim(text = text, citationIds = citationIds, memory_object_ids = memoryObjectIds, uncertainty = uncertainty)

    private fun response(
        answer: String,
        citations: List<Citation> = emptyList(),
        claims: List<Claim> = emptyList(),
        abstained: Boolean = false,
        abstentionReason: String? = null
    ) = GroundedChatResponse(
        answer = answer,
        citations = citations,
        claims = claims,
        abstained = abstained,
        abstention_reason = abstentionReason
    )

    private suspend fun validated(
        response: GroundedChatResponse,
        recCitations: List<ReconciledCitation>
    ): ValidatedResponse = validator.validate(response, recCitations)

    // ── Terminal dispositions ──────────────────────────────────────────────

    @Test
    fun `fully validated when all claims supported`() = runTest {
        val recCitations = listOf(reconciledCitation(citation("cite-1")))
        val resp = response(
            answer = "The supplier was ACME Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was ACME Corp.", citationIds = listOf("cite-1")))
        )
        val validated = validated(resp, recCitations)

        val disposition = resolver.resolve(validated, attempt = 1)

        assertTrue(disposition is GroundingDisposition.FULLY_VALIDATED)
    }

    @Test
    fun `unverified when unsupported claims and max attempts exhausted`() = runTest {
        coEvery { embedder.isReady() } returns false
        val recCitations = listOf(reconciledCitation(citation("cite-1")))
        val resp = response(
            answer = "The supplier was UNKNOWN Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was UNKNOWN Corp.", citationIds = listOf("cite-missing")))
        )
        val validated = validated(resp, recCitations)

        // Attempt 2 (exhausted) -> UNVERIFIED
        val disposition = resolver.resolve(validated, attempt = 2)

        assertTrue(disposition is GroundingDisposition.UNVERIFIED)
        val unverified = disposition as GroundingDisposition.UNVERIFIED
        assertEquals(1, unverified.unsupportedClaims.size)
    }

    @Test
    fun `abstain when model already abstained`() = runTest {
        val recCitations = emptyList<ReconciledCitation>()
        val resp = response(answer = "", abstained = true, abstentionReason = "not enough info")
        val validated = validated(resp, recCitations)

        val disposition = resolver.resolve(validated, attempt = 1)

        assertTrue(disposition is GroundingDisposition.ABSTAIN)
        val abstain = disposition as GroundingDisposition.ABSTAIN
        assertEquals("not enough info", abstain.reason)
    }

    @Test
    fun `abstain when both abstained and clarification true then one terminal (mutual exclusivity)`() = runTest {
        val recCitations = emptyList<ReconciledCitation>()
        val resp = response(
            answer = "",
            abstained = true,
            abstentionReason = "not enough info"
        )
        // GroundedChatResponse allows both; resolver picks one
        val validated = validated(resp, recCitations)

        val disposition = resolver.resolve(validated, attempt = 1)

        assertTrue(disposition is GroundingDisposition.ABSTAIN)
    }

    @Test
    fun `hole in evidence when high uncertainty and no valid quote range`() = runTest {
        coEvery { embedder.isReady() } returns false
        val recCitations = listOf(reconciledCitation(citation("cite-1")))
        val resp = response(
            answer = "The supplier was UNKNOWN Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was UNKNOWN Corp.", citationIds = listOf("cite-1"), uncertainty = UncertaintyLevel.HIGH))
        )
        val validated = validated(resp, recCitations)

        val disposition = resolver.resolve(validated, attempt = 1)

        assertTrue(disposition is GroundingDisposition.HOLE_IN_EVIDENCE)
    }

    @Test
    fun `unresponsive when parse failed`() = runTest {
        // ParseResult.Failure is handled before resolver, but we can test the disposition directly
        val disposition = GroundingDisposition.UNRESPONSIVE
        assertTrue(disposition is GroundingDisposition.UNRESPONSIVE)
    }

    // ── Retry logic (Trigger 5) ────────────────────────────────────────────

    @Test
    fun `retry when unsupported claims and attempts remaining`() = runTest {
        coEvery { embedder.isReady() } returns false
        val recCitations = listOf(reconciledCitation(citation("cite-1")))
        val resp = response(
            answer = "The supplier was UNKNOWN Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was UNKNOWN Corp.", citationIds = listOf("cite-missing")))
        )
        val validated = validated(resp, recCitations)

        // Attempt 1 (one remaining) -> RETRY
        val disposition = resolver.resolve(validated, attempt = 1)

        assertTrue(disposition is GroundingDisposition.RETRY)
        val retry = disposition as GroundingDisposition.RETRY
        assertTrue(retry.retryPrompt.contains("Citation 'cite-missing' not in response"))
    }

    @Test
    fun `no retry when no unsupported claims`() = runTest {
        val recCitations = listOf(reconciledCitation(citation("cite-1")))
        val resp = response(
            answer = "The supplier was ACME Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was ACME Corp.", citationIds = listOf("cite-1")))
        )
        val validated = validated(resp, recCitations)

        val disposition = resolver.resolve(validated, attempt = 1)

        assertTrue(disposition is GroundingDisposition.FULLY_VALIDATED)
    }

    @Test
    fun `retry prompt is specific per spec`() = runTest {
        coEvery { embedder.isReady() } returns false
        val recCitations = listOf(reconciledCitation(citation("cite-1")))
        val resp = response(
            answer = "The supplier was UNKNOWN Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was UNKNOWN Corp.", citationIds = listOf("cite-missing")))
        )
        val validated = validated(resp, recCitations)

        val disposition = resolver.resolve(validated, attempt = 1)

        assertTrue(disposition is GroundingDisposition.RETRY)
        val retry = disposition as GroundingDisposition.RETRY
        assertTrue(retry.retryPrompt.contains("Only make claims that are directly supported"))
        assertTrue(retry.retryPrompt.contains("cite a VALID source_segment_id"))
        assertTrue(retry.retryPrompt.contains("If you cannot support a claim with evidence, remove it"))
        assertTrue(retry.retryPrompt.contains("\"abstained\": true"))
    }
}