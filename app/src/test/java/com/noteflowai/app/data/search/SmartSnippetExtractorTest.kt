package com.noteflowai.app.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSnippetExtractorTest {

    private val extractor = SmartSnippetExtractor()

    @Test
    fun `extract expands contiguous markdown list when match is inside a list`() {
        val markdownNote = """
            # Project Milestones
            Here is the overview of the project.

            ## Key Dates:
            - 2026-09-01: Architecture review
            - 2026-09-05: Database migration
            - 2026-09-10: Security audit
            - 2026-09-15: Beta testing
            - 2026-09-20: Production release

            Additional details follow below.
            We will discuss this in the next meeting.
        """.trimIndent()

        val tokens = listOf("migration", "dates")
        val snippet = extractor.extract(markdownNote, tokens)

        assertTrue(snippet.text.contains("## Key Dates:"))
        assertTrue(snippet.text.contains("- 2026-09-01: Architecture review"))
        assertTrue(snippet.text.contains("- 2026-09-05: Database migration"))
        assertTrue(snippet.text.contains("- 2026-09-10: Security audit"))
        assertTrue(snippet.text.contains("- 2026-09-15: Beta testing"))
        assertTrue(snippet.text.contains("- 2026-09-20: Production release"))
    }

    @Test
    fun `extract expands numbered list when match is inside a numbered list`() {
        val note = """
            Items to buy:
            1. Apples
            2. Bananas
            3. Milk
            4. Bread
            5. Eggs

            End of list.
        """.trimIndent()

        val tokens = listOf("milk")
        val snippet = extractor.extract(note, tokens)

        assertTrue(snippet.text.contains("1. Apples"))
        assertTrue(snippet.text.contains("2. Bananas"))
        assertTrue(snippet.text.contains("3. Milk"))
        assertTrue(snippet.text.contains("4. Bread"))
        assertTrue(snippet.text.contains("5. Eggs"))
    }

    @Test
    fun `extract uses larger ceiling for list or comprehensive queries`() {
        val longContent = (1..30).joinToString("\n") { "- Item $it: detail and description for task $it" }
        val note = "Action Items:\n$longContent"

        val queryTokens = listOf("list", "all", "items")
        val snippet = extractor.extract(note, queryTokens)

        assertTrue(snippet.text.length > 500)
        assertTrue(snippet.text.contains("Item 1:"))
        assertTrue(snippet.text.contains("Item 10:"))
    }

    @Test
    fun `extract handles blank text and empty tokens gracefully`() {
        val snippet1 = extractor.extract("", listOf("query"))
        assertEquals("", snippet1.text)
        assertFalse(snippet1.isPartial)

        val snippet2 = extractor.extract("Some note content", emptyList())
        assertEquals("Some note content", snippet2.text)
    }
}
