package com.noteflowai.app.data.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkingServiceTest {

    private val chunkingService = ChunkingService()

    @Test
    fun `chunkNote splits long content into bounded chunks with overlap`() {
        val paragraphs = (1..20).map { "Paragraph $it: " + "Sentence discussing important details. ".repeat(10) }
        val noteContent = paragraphs.joinToString("\n\n")

        val chunks = chunkingService.chunkNote(
            noteId = "note_123.json",
            title = "Project Plan",
            content = noteContent,
            createdDate = "Aug 30, 2026",
            modifiedDate = "Aug 31, 2026",
            targetChunkTokens = 256,
            overlapRatio = 0.15
        )

        assertTrue("Chunks should not be empty", chunks.isNotEmpty())
        assertTrue("Should have multiple chunks", chunks.size > 1)
        for (chunk in chunks) {
            assertEquals("note_123.json", chunk.noteId)
            assertEquals("Project Plan", chunk.title)
            assertEquals("Aug 30, 2026", chunk.createdDate)
            assertEquals("Aug 31, 2026", chunk.modifiedDate)
            assertTrue(chunk.text.isNotBlank())
        }
    }

    @Test
    fun `chunkNote handles empty or blank content gracefully`() {
        val chunks = chunkingService.chunkNote(
            noteId = "empty.json",
            title = "Empty",
            content = "   "
        )
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `chunkNote keeps single short paragraph in one chunk`() {
        val shortContent = "Short meeting summary from earlier today."
        val chunks = chunkingService.chunkNote(
            noteId = "short.json",
            title = "Short",
            content = shortContent
        )
        assertEquals(1, chunks.size)
        assertEquals("Short", chunks[0].title)
        assertEquals(shortContent, chunks[0].text)
    }
}
