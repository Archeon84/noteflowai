package com.noteflowai.app.data.chat

import com.noteflowai.app.data.search.RetrievalResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for GroundingPromptBuilder (Phase 6 rework).
 *
 * Reworked to use numbered-list citation injection with explicit [N] contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroundingPromptBuilderTest {

    private val builder = GroundingPromptBuilder()

    private fun retrieval(
        sourceSegmentId: String,
        sourceId: String = "note.md",
        text: String = "The supplier was ACME Corp. The price was $500.",
        score: Float = 0.9f,
        memoryObjectId: String? = null
    ) = RetrievalResult(
        sourceSegmentId = sourceSegmentId,
        sourceId = sourceId,
        text = text,
        score = score,
        rank = 0,
        memoryObjectId = memoryObjectId
    )

    // ── Numbered list injection ────────────────────────────────────────────

    @Test
    fun `numbered list with one result`() {
        val results = listOf(retrieval("seg-1"))
        val prompt = builder.buildGroundingPrompt(results)

        assertTrue(prompt.contains("1. [segmentId=seg-1] [note=note.md]"))
        assertTrue(prompt.contains("The supplier was ACME Corp."))
    }

    @Test
    fun `numbered list with multiple results`() {
        val results = listOf(
            retrieval("seg-1", "note1.md", "First segment text"),
            retrieval("seg-2", "note2.md", "Second segment text")
        )
        val prompt = builder.buildGroundingPrompt(results)

        assertTrue(prompt.contains("1. [segmentId=seg-1] [note=note1.md]"))
        assertTrue(prompt.contains("First segment text"))
        assertTrue(prompt.contains("2. [segmentId=seg-2] [note=note2.md]"))
        assertTrue(prompt.contains("Second segment text"))
    }

    @Test
    fun `exact N marker instruction present`() {
        val results = listOf(retrieval("seg-1"))
        val prompt = builder.buildGroundingPrompt(results)

        assertTrue(prompt.contains("Cite claims with [N] markers where N is the number of the evidence item"))
        assertTrue(prompt.contains("Use [1], [2] exactly"))
        assertTrue(prompt.contains("no other citation syntax"))
    }

    @Test
    fun `no comma joined ID list`() {
        val results = listOf(
            retrieval("seg-1"),
            retrieval("seg-2")
        )
        val prompt = builder.buildGroundingPrompt(results)

        // Should NOT contain comma-joined ID list like "seg-1, seg-2"
        assertTrue(!prompt.contains("seg-1, seg-2") || !prompt.contains("Valid source IDs:"))
    }

    @Test
    fun `empty retrieval results`() {
        val results = emptyList<RetrievalResult>()
        val prompt = builder.buildGroundingPrompt(results)

        assertTrue(prompt.contains("No evidence available"))
        assertTrue(prompt.contains("abstained"))
    }

    // ── Response schema with new fields ────────────────────────────────────

    @Test
    fun `response schema includes citationIds and memory_object_ids`() {
        val results = listOf(retrieval("seg-1"))
        val prompt = builder.buildGroundingPrompt(results)

        assertTrue(prompt.contains("citationIds"))
        assertTrue(prompt.contains("memory_object_ids"))
        assertTrue(prompt.contains("uncertainty"))
    }

    @Test
    fun `response schema includes abstained and needs_clarification`() {
        val results = listOf(retrieval("seg-1"))
        val prompt = builder.buildGroundingPrompt(results)

        assertTrue(prompt.contains("abstained"))
        assertTrue(prompt.contains("needs_clarification"))
    }

    // ── Knowledge Graph and Timeline Preamble ──────────────────────────────

    @Test
    fun `entity context is rendered in preamble and not in numbered evidence list`() {
        val entityResult = RetrievalResult(
            sourceSegmentId = "entity_context_123",
            sourceId = "knowledge_graph",
            text = "[Entity Context]\n• Alice (PERSON) aka Al",
            score = 1.0f,
            rank = 0,
            metadata = mapOf("type" to "entity_context")
        )
        val noteResult = retrieval("seg-1", "note1.md", "Alice is leading the team.")

        val prompt = builder.buildGroundingPrompt(listOf(entityResult, noteResult))

        assertTrue(prompt.contains("## Knowledge Graph"))
        assertTrue(prompt.contains("• Alice (PERSON) aka Al"))
        // Numbered list should only contain noteResult as item 1
        assertTrue(prompt.contains("1. [segmentId=seg-1] [note=note1.md]"))
        assertTrue(!prompt.contains("2."))
        assertTrue(!prompt.contains("1. [segmentId=entity_context_123]"))
        assertTrue(prompt.contains("The Knowledge Graph and Timeline sections above provide structured context"))
    }

    @Test
    fun `timeline context is rendered in preamble and not in numbered evidence list`() {
        val timelineResult = RetrievalResult(
            sourceSegmentId = "timeline_context_456",
            sourceId = "timeline",
            text = "[Timeline Context]\n• Q1 Planning (EXACT: 2026-03-01)",
            score = 0.95f,
            rank = 0,
            metadata = mapOf("type" to "timeline_context")
        )
        val noteResult = retrieval("seg-1", "note1.md", "Planning started in March.")

        val prompt = builder.buildGroundingPrompt(listOf(timelineResult, noteResult))

        assertTrue(prompt.contains("## Timeline"))
        assertTrue(prompt.contains("• Q1 Planning (EXACT: 2026-03-01)"))
        assertTrue(prompt.contains("1. [segmentId=seg-1] [note=note1.md]"))
        assertTrue(!prompt.contains("2."))
        assertTrue(prompt.contains("The Knowledge Graph and Timeline sections above provide structured context"))
    }
}