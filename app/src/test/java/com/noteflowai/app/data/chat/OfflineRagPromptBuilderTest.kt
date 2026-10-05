package com.noteflowai.app.data.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRagPromptBuilderTest {

    @Test
    fun `buildSystemPrompt includes temporal context, evidence excerpts with dates, and rules`() {
        val temporal = "[TEMPORAL CONTEXT] Current Date: 2026-08-30"
        val excerpts = listOf(
            OfflineRagPromptBuilder.Excerpt(
                index = 1,
                title = "Budget 2026",
                text = "Approved budget is $10,000 for Q3 marketing.",
                createdDate = "Aug 20, 2026",
                modifiedDate = "Aug 25, 2026"
            ),
            OfflineRagPromptBuilder.Excerpt(
                index = 2,
                title = "Team Meeting",
                text = "Discussed Q3 launch timeline.",
                createdDate = "Aug 26, 2026"
            )
        )

        val prompt = OfflineRagPromptBuilder.buildSystemPrompt(
            basePrompt = "You are a concise assistant.",
            temporalContext = temporal,
            excerpts = excerpts
        )

        assertTrue(prompt.contains(temporal))
        assertTrue(prompt.contains("You are a concise assistant."))
        assertTrue(prompt.contains("=== RETRIEVED NOTE EVIDENCE ==="))
        assertTrue(prompt.contains("[1] \"Budget 2026\" (Created: Aug 20, 2026, Modified: Aug 25, 2026):"))
        assertTrue(prompt.contains("Approved budget is $10,000"))
        assertTrue(prompt.contains("[2] \"Team Meeting\" (Created: Aug 26, 2026):"))
        assertTrue(prompt.contains("=== STRICT OFFLINE RAG RULES ==="))
        assertTrue(prompt.contains("1. FACTUAL GROUNDING:"))
        assertTrue(prompt.contains("2. TEMPORAL RESOLUTION:"))
        assertTrue(prompt.contains("3. CONFLICT HANDLING:"))
        assertTrue(prompt.contains("4. CITATION SYNTAX:"))
        assertTrue(prompt.contains("5. HONEST REFUSAL:"))
        assertTrue(prompt.contains("6. RICH MARKDOWN FORMATTING:"))
        assertTrue(prompt.contains("Markdown headings (## Section Title)"))
        assertTrue(prompt.contains("Use bullet points (- )"))
    }

    @Test
    fun `buildSystemPrompt with empty excerpts indicates no relevant notes`() {
        val prompt = OfflineRagPromptBuilder.buildSystemPrompt(
            basePrompt = "",
            temporalContext = "[TEMPORAL CONTEXT] Today is 2026-08-30",
            excerpts = emptyList()
        )

        assertTrue(prompt.contains("No relevant note excerpts found for this query."))
        assertTrue(prompt.contains("HONEST REFUSAL"))
    }

    @Test
    fun `buildSystemPrompt includes 1-shot citation exemplar for offline models`() {
        val prompt = OfflineRagPromptBuilder.buildSystemPrompt("Base prompt")
        assertTrue(prompt.contains("Example: \"The project kick-off is on Friday [1] and requires 3 engineers [2].\""))
    }

    @Test
    fun `getOfflineFormattingAndGroundingDirectives contains headings, bullets, and refusal`() {
        val directives = OfflineRagPromptBuilder.getOfflineFormattingAndGroundingDirectives()
        assertTrue(directives.contains("1. FACTUAL GROUNDING:"))
        assertTrue(directives.contains("2. HONEST REFUSAL:"))
        assertTrue(directives.contains("3. STRUCTURED HEADINGS:"))
        assertTrue(directives.contains("4. BULLET POINTS:"))
        assertTrue(directives.contains("5. HIGHLIGHT KEY TERMS:"))
        assertTrue(directives.contains("6. CONCISE & READABLE:"))
        assertTrue(directives.contains("7. CITATIONS:"))
    }

    @Test
    fun `getOfflineAssistantWebSearchDirectives contains web grounding and citations without note refusal`() {
        val directives = OfflineRagPromptBuilder.getOfflineAssistantWebSearchDirectives()
        assertTrue(directives.contains("1. WEB GROUNDING:"))
        assertTrue(directives.contains("2. CITATIONS:"))
        assertTrue(directives.contains("NO NOTE REFUSAL:"))
        assertFalse(directives.contains("Based on your notes, I don't have information"))
    }

    @Test
    fun `getOfflineGeneralKnowledgeDirectives instructs general knowledge without citations or note refusal`() {
        val directives = OfflineRagPromptBuilder.getOfflineGeneralKnowledgeDirectives()
        assertTrue(directives.contains("1. GENERAL KNOWLEDGE:"))
        assertTrue(directives.contains("2. NO CITATIONS:"))
        assertTrue(directives.contains("3. NO NOTE REFUSAL:"))
        assertFalse(directives.contains("Based on your notes, I don't have information"))
    }

    @Test
    fun `attachPostHocCitations appends citation to grounded sentence when missing`() {
        val sources = listOf(
            RagSource(
                noteFileName = "meeting.txt",
                noteTitle = "Project Kickoff",
                relevanceScore = 0.9f,
                excerpt = "The project kickoff is scheduled for Friday morning with three engineers assigned.",
                source = "test"
            )
        )
        val textWithoutCitations = "The project kickoff is scheduled for Friday morning."
        val result = OfflineRagPromptBuilder.attachPostHocCitations(textWithoutCitations, sources)

        assertTrue("Expected citation [1] to be attached, got: $result", result.contains("[1]"))
    }

    @Test
    fun `attachPostHocCitations does not modify text that already has bracket citations`() {
        val sources = listOf(
            RagSource(
                noteFileName = "meeting.txt",
                noteTitle = "Project Kickoff",
                relevanceScore = 0.9f,
                excerpt = "The project kickoff is scheduled for Friday morning.",
                source = "test"
            )
        )
        val textWithCitation = "The kickoff is on Friday [1]."
        val result = OfflineRagPromptBuilder.attachPostHocCitations(textWithCitation, sources)

        assertTrue(result == textWithCitation)
    }

    @Test
    fun `attachPostHocCitations attaches citations for web sources`() {
        val webSources = listOf(
            RagSource(
                noteFileName = "https://kotlinlang.org",
                noteTitle = "Kotlin Programming Language",
                relevanceScore = 0.95f,
                excerpt = "Kotlin is a modern concise and safe programming language.",
                source = "web"
            )
        )
        val textWithoutCitation = "Kotlin is a modern concise and safe programming language."
        val result = OfflineRagPromptBuilder.attachPostHocCitations(textWithoutCitation, webSources)
        assertTrue("Expected citation [1] to be attached to web source, got: $result", result.contains("[1]"))
    }

    @Test
    fun `ChatRecallMode parses correctly from string`() {
        org.junit.Assert.assertEquals(ChatRecallMode.NOTES_ONLY, ChatRecallMode.fromString("NOTES_ONLY"))
        org.junit.Assert.assertEquals(ChatRecallMode.REMOTE_API_ONLY, ChatRecallMode.fromString("REMOTE_API_ONLY"))
        org.junit.Assert.assertEquals(ChatRecallMode.REMOTE_API_ONLY, ChatRecallMode.fromString("remote_api_only"))
        org.junit.Assert.assertEquals(ChatRecallMode.NOTES_ONLY, ChatRecallMode.fromString(null))
        org.junit.Assert.assertEquals(ChatRecallMode.NOTES_ONLY, ChatRecallMode.fromString("invalid"))
    }

    @Test
    fun `clampPromptToSafeBudget leaves short prompts unchanged`() {
        val shortPrompt = "Short prompt that fits easily within budget."
        val clamped = OfflineRagPromptBuilder.clampPromptToSafeBudget(shortPrompt, maxChars = 1000)
        org.junit.Assert.assertEquals(shortPrompt, clamped)
    }

    @Test
    fun `clampPromptToSafeBudget truncates middle when exceeding budget`() {
        val head = "START_DIRECTIVE: System instruction here."
        val middle = "A".repeat(5000)
        val tail = "USER_QUERY: What are the main points in my note?"
        val longPrompt = "$head\n$middle\n$tail"

        val clamped = OfflineRagPromptBuilder.clampPromptToSafeBudget(longPrompt, maxChars = 500)
        assertTrue(clamped.length <= 550)
        assertTrue(clamped.startsWith("START_DIRECTIVE"))
        assertTrue(clamped.endsWith("in my note?"))
        assertTrue(clamped.contains("[...prior note context trimmed to fit on-device memory...]"))
    }
}

