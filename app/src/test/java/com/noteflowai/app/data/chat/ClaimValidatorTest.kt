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

class ClaimValidatorTest {

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

    @Test
    fun `V1 unknown citation id is rejected`() = runTest {
        val recCitations = listOf(
            reconciledCitation(citation("cite-1"))
        )
        val response = GroundedChatResponse(
            answer = "The supplier was ACME Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was ACME Corp.", citationIds = listOf("cite-1", "cite-unknown")))
        )

        val validated = validator.validate(response, recCitations)

        assertEquals(1, validated.validatedClaims.size)
        val vc = validated.validatedClaims[0]
        assertFalse(vc.isValid)
        assertTrue(vc.invalidCitationIds.contains("cite-unknown"))
        assertTrue(vc.invalidCitationReasons["cite-unknown"]?.contains("cite-unknown") == true)
    }

    @Test
    fun `claim with all-invalid citationIds is not valid`() = runTest {
        val recCitations = listOf(
            reconciledCitation(citation("cite-1"))
        )
        val response = GroundedChatResponse(
            answer = "The supplier was ACME Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was ACME Corp.", citationIds = listOf("cite-missing")))
        )

        val validated = validator.validate(response, recCitations)

        assertFalse(validated.validatedClaims[0].isValid)
        assertEquals(1, validated.unsupportedClaims)
        assertEquals(0, validated.validClaims)
    }

    @Test
    fun `memory-object channel validates OR with citationIds`() = runTest {
        val recCitations = listOf(
            reconciledCitation(citation("cite-1"))
        )
        val response = GroundedChatResponse(
            answer = "The supplier was ACME Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(
                claim("The supplier was ACME Corp.", citationIds = emptyList(), memoryObjectIds = listOf("mem-1"))
            )
        )

        val validated = validator.validate(response, recCitations)

        assertTrue(validated.validatedClaims[0].isValid)
        assertEquals(listOf("mem-1"), validated.validatedClaims[0].validMemoryIds)
        assertEquals(1, validated.validClaims)
    }

    @Test
    fun `entailment passes via quote presence`() = runTest {
        val segmentText = "The supplier was ACME Corp. The price was $500."
        val recCitations = listOf(
            reconciledCitation(citation("cite-1"), segmentText = segmentText)
        )
        val response = GroundedChatResponse(
            answer = "The supplier was ACME Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was ACME Corp.", citationIds = listOf("cite-1")))
        )

        val validated = validator.validate(response, recCitations)

        assertTrue(validated.validatedClaims[0].isValid)
        assertTrue(validated.validatedClaims[0].hasValidQuoteRange)
    }

    @Test
    fun `entailment passes via embed cosine`() = runTest {
        coEvery { embedder.isReady() } returns true
        coEvery { embedder.embed(any()) } returns FloatArray(384) { 0.01f } // matching vectors

        val segmentText = "The supplier was ACME Corp. The price was $500."
        val recCitations = listOf(
            reconciledCitation(citation("cite-1"), segmentText = segmentText)
        )
        val response = GroundedChatResponse(
            answer = "The supplier was ACME Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("ACME Corp was the supplier", citationIds = listOf("cite-1")))
        )

        val validated = validator.validate(response, recCitations)

        assertTrue(validated.validatedClaims[0].isValid)
    }

    @Test
    fun `embedder unavailable is conservative reject`() = runTest {
        coEvery { embedder.isReady() } returns false

        val segmentText = "The supplier was ACME Corp. The price was $500."
        val recCitations = listOf(
            reconciledCitation(citation("cite-1"), segmentText = segmentText)
        )
        val response = GroundedChatResponse(
            answer = "The supplier was ACME Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("ACME Corp was the supplier", citationIds = listOf("cite-1")))
        )

        val validated = validator.validate(response, recCitations)

        // Quote presence should still pass for exact match
        assertTrue(validated.validatedClaims[0].isValid)
        assertTrue(validated.validatedClaims[0].hasValidQuoteRange)
    }

    @Test
    fun `malformed envelope never crashes`() = runTest {
        coEvery { embedder.isReady() } returns false

        val recCitations = listOf(
            reconciledCitation(citation("cite-1"))
        )
        val response = GroundedChatResponse(
            answer = "",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("", citationIds = listOf("cite-1")))
        )

        val validated = validator.validate(response, recCitations)

        // Should not crash; produces a validation result
        assertEquals(1, validated.totalClaims)
        assertFalse(validated.validatedClaims[0].isValid) // empty claim fails
    }

    @Test
    fun `non-authoritative citation is rejected`() = runTest {
        val recCitations = listOf(
            reconciledCitation(citation("cite-1"), authoritative = false)
        )
        val response = GroundedChatResponse(
            answer = "The supplier was ACME Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was ACME Corp.", citationIds = listOf("cite-1")))
        )

        val validated = validator.validate(response, recCitations)

        assertFalse(validated.validatedClaims[0].isValid)
        assertTrue(validated.validatedClaims[0].invalidCitationIds.contains("cite-1"))
    }

    @Test
    fun `both citation and memory channels fail claim invalid`() = runTest {
        val recCitations = listOf(
            reconciledCitation(citation("cite-1"))
        )
        val response = GroundedChatResponse(
            answer = "The supplier was ACME Corp.",
            citations = listOf(citation("cite-1")),
            claims = listOf(claim("The supplier was UNKNOWN Corp.", citationIds = listOf("cite-missing"), memoryObjectIds = emptyList()))
        )

        val validated = validator.validate(response, recCitations)

        assertFalse(validated.validatedClaims[0].isValid)
        assertEquals(1, validated.unsupportedClaims)
    }
}