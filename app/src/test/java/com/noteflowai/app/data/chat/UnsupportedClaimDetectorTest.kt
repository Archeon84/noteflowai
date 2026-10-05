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
 * Unit tests for UnsupportedClaimDetector (Phase 6 rework).
 *
 * Reworked to drive Trigger 5 only:
 * - Detects whether any claim is unsupported after validation
 * - Builds the retry prompt content with specific failure reasons
 * - Does NOT decide terminal states (GroundingDispositionResolver owns that)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnsupportedClaimDetectorTest {

    private val embedder = mockk<OnDeviceEmbedder>()
    private val validator = ClaimValidator(embedder)

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

    // ── Unsupported claim detection (Trigger 5) ────────────────────────────

    @Test
    fun `detects unsupported claims when citation channel fails`() = runTest {
        coEvery { embedder.isReady() } returns false

        val recCitations = listOf(
            reconciledCitation(citation("cite-1"))
        )
        val resp = response(
            answer = "The supplier was UNKNOWN Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was UNKNOWN Corp.", citationIds = listOf("cite-1")))
        )
        val validated = validated(resp, recCitations)

        val hasUnsupported = UnsupportedClaimDetector.hasUnsupportedClaims(validated)

        assertTrue(hasUnsupported)
    }

    @Test
    fun `detects no unsupported claims when all valid`() = runTest {
        val recCitations = listOf(
            reconciledCitation(citation("cite-1"))
        )
        val resp = response(
            answer = "The supplier was ACME Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was ACME Corp.", citationIds = listOf("cite-1")))
        )
        val validated = validated(resp, recCitations)

        val hasUnsupported = UnsupportedClaimDetector.hasUnsupportedClaims(validated)

        assertFalse(hasUnsupported)
    }

    @Test
    fun `detects no unsupported when memory channel passes`() = runTest {
        val recCitations = listOf(
            reconciledCitation(citation("cite-1"))
        )
        val resp = response(
            answer = "The supplier was ACME Corp.",
            citations = emptyList(),
            claims = listOf(claim("The supplier was ACME Corp.", citationIds = emptyList(), memoryObjectIds = listOf("mem-1")))
        )
        val validated = validated(resp, recCitations)

        val hasUnsupported = UnsupportedClaimDetector.hasUnsupportedClaims(validated)

        assertFalse(hasUnsupported)
    }

    @Test
    fun `empty claims list has no unsupported`() = runTest {
        val recCitations = emptyList<ReconciledCitation>()
        val resp = response(answer = "simple answer")
        val validated = validated(resp, recCitations)

        val hasUnsupported = UnsupportedClaimDetector.hasUnsupportedClaims(validated)

        assertFalse(hasUnsupported)
    }

    // ── buildRetryPrompt specificity (user closing note 3) ─────────────────

    @Test
    fun `buildRetryPrompt includes specific failure reason for invalid citation`() = runTest {
        coEvery { embedder.isReady() } returns false

        val recCitations = listOf(
            reconciledCitation(citation("cite-1"))
        )
        val resp = response(
            answer = "The supplier was UNKNOWN Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was UNKNOWN Corp.", citationIds = listOf("cite-missing")))
        )
        val validated = validated(resp, recCitations)

        val prompt = UnsupportedClaimDetector.buildRetryPrompt(validated)

        assertTrue(prompt.contains("Citation 'cite-missing' not in response"))
    }

    @Test
    fun `buildRetryPrompt includes specific failure reason for non-authoritative citation`() = runTest {
        val recCitations = listOf(
            reconciledCitation(citation("cite-1"), authoritative = false)
        )
        val resp = response(
            answer = "The supplier was ACME Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was ACME Corp.", citationIds = listOf("cite-1")))
        )
        val validated = validated(resp, recCitations)

        val prompt = UnsupportedClaimDetector.buildRetryPrompt(validated)

        assertTrue(prompt.contains("Citation 'cite-1' not authoritative"))
    }

    @Test
    fun `buildRetryPrompt includes specific failure reason for entailment failure`() = runTest {
        coEvery { embedder.isReady() } returns false

        val segmentText = "The supplier was ACME Corp. The price was $500."
        val recCitations = listOf(
            reconciledCitation(citation("cite-1"), segmentText = segmentText)
        )
        val resp = response(
            answer = "The supplier was UNKNOWN Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was UNKNOWN Corp.", citationIds = listOf("cite-1")))
        )
        val validated = validated(resp, recCitations)

        val prompt = UnsupportedClaimDetector.buildRetryPrompt(validated)

        assertTrue(prompt.contains("No cited chunk supports the claim"))
    }

    @Test
    fun `buildRetryPrompt lists all unsupported claims with details`() = runTest {
        coEvery { embedder.isReady() } returns false

        val recCitations = listOf(
            reconciledCitation(citation("cite-1"))
        )
        val resp = response(
            answer = "The supplier was UNKNOWN Corp. Price was $999.",
            citations = listOf(citation("cite-1")),
            claims = listOf(
                claim("The supplier was UNKNOWN Corp.", citationIds = listOf("cite-missing")),
                claim("Price was $999.", citationIds = listOf("cite-1"))
            )
        )
        val validated = validated(resp, recCitations)

        val prompt = UnsupportedClaimDetector.buildRetryPrompt(validated)

        assertTrue(prompt.contains("Citation 'cite-missing' not in response"))
        assertTrue(prompt.contains("UNKNOWN Corp"))
    }

    @Test
    fun `buildRetryPrompt includes retry instructions per spec`() = runTest {
        coEvery { embedder.isReady() } returns false

        val recCitations = listOf(
            reconciledCitation(citation("cite-1"))
        )
        val resp = response(
            answer = "The supplier was UNKNOWN Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was UNKNOWN Corp.", citationIds = listOf("cite-missing")))
        )
        val validated = validated(resp, recCitations)

        val prompt = UnsupportedClaimDetector.buildRetryPrompt(validated)

        assertTrue(prompt.contains("Only make claims that are directly supported"))
        assertTrue(prompt.contains("cite a VALID source_segment_id"))
        assertTrue(prompt.contains("If you cannot support a claim with evidence, remove it"))
        assertTrue(prompt.contains("\"abstained\": true"))
    }
}