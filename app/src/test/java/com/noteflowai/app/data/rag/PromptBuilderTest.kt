package com.noteflowai.app.data.rag

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private val promptBuilder = PromptBuilder()

    @Test
    fun `buildRagPrompt formats evidence and respects context budget`() {
        val chunk1 = Chunk(
            noteId = "note1.json",
            title = "Design Document",
            text = "Architecture design details and components.",
            startOffset = 0,
            endOffset = 45,
            createdDate = "Aug 20, 2026",
            modifiedDate = "Aug 25, 2026"
        )
        val chunk2 = Chunk(
            noteId = "note2.json",
            title = "Meeting Notes",
            text = "Discussed timeline and deliverables.",
            startOffset = 0,
            endOffset = 36,
            createdDate = "Aug 28, 2026",
            modifiedDate = "Aug 28, 2026"
        )

        val prompt = promptBuilder.buildRagPrompt(
            query = "What was discussed in the meeting?",
            chunks = listOf(chunk1, chunk2),
            maxContextTokens = 20000
        )

        assertTrue(prompt.system.contains("NoteFlow AI"))
        assertTrue(prompt.system.contains("FACTUAL GROUNDING"))
        assertTrue(prompt.contextText.contains("[1] Note: \"Design Document\" (Created: Aug 20, 2026, Modified: Aug 25, 2026)"))
        assertTrue(prompt.contextText.contains("[2] Note: \"Meeting Notes\" (Created: Aug 28, 2026, Modified: Aug 28, 2026)"))
        assertTrue(prompt.userQuery == "What was discussed in the meeting?")

        val chatMl = prompt.toChatMlPrompt()
        assertTrue(chatMl.startsWith("<|im_start|>system\n"))
        assertTrue(chatMl.contains("<|im_start|>user\nWhat was discussed in the meeting?\n<|im_end|>\n"))
        assertTrue(chatMl.endsWith("<|im_start|>assistant\n<think>\n</think>\n"))
    }
}
