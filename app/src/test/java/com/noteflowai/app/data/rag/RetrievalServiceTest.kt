package com.noteflowai.app.data.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalServiceTest {

    @Test
    fun `retrieve returns most relevant chunks boosting title matches`() {
        val chunk1 = Chunk(
            noteId = "note1.json",
            title = "Machine Learning Deployment",
            text = "Discussing on-device ML deployment parameters and quantization.",
            startOffset = 0,
            endOffset = 60
        )
        val chunk2 = Chunk(
            noteId = "note2.json",
            title = "Grocery List",
            text = "Milk, bread, eggs, cheese, apples.",
            startOffset = 0,
            endOffset = 35
        )
        val chunk3 = Chunk(
            noteId = "note3.json",
            title = "Project Roadmap",
            text = "Roadmap for Q3: finalize machine learning integration and release notes.",
            startOffset = 0,
            endOffset = 70
        )

        val retriever = RetrievalService(listOf(chunk1, chunk2, chunk3))
        val results = retriever.retrieve("machine learning deployment", k = 2)

        assertEquals(2, results.size)
        assertEquals("Machine Learning Deployment", results[0].title)
        assertEquals("Project Roadmap", results[1].title)
    }

    @Test
    fun `retrieve returns empty when query has no matching terms`() {
        val chunk1 = Chunk("n1.json", "Doc 1", "Alpha beta gamma", 0, 15)
        val chunk2 = Chunk("n2.json", "Doc 2", "Delta epsilon zeta", 0, 18)

        val retriever = RetrievalService(listOf(chunk1, chunk2))
        val results = retriever.retrieve("xyz 123", k = 1)

        // No-match fallback used to return irrelevant chunks, defeating
        // downstream refusal logic.
        assertTrue(results.isEmpty())
        assertTrue(retriever.retrieve("a", k = 1).isEmpty())
    }
}
