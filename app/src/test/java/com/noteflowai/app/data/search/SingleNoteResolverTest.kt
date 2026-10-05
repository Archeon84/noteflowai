package com.noteflowai.app.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingleNoteResolverTest {

    private val titles = mapOf(
        "fridge_magnet.md" to "Fridge Photo Magnet Idea",
        "fridge_real.md" to "Fridge Photo Magnet Idea 2026-09-08 19-29-21",
        "rag_system.md" to "RAG System",
        "adhd.md" to "ADHD Notes"
    )

    @Test
    fun `query naming the note title resolves it`() {
        assertEquals(
            "fridge_magnet.md",
            resolveSingleNoteTarget(
                "what does my fridge photo magnet idea say about pricing?",
                listOf("rag_system.md" to 0.9f, "fridge_magnet.md" to 0.5f),
                titles, 0.4f
            )
        )
    }

    @Test
    fun `timestamped display title matches without the timestamp`() {
        // Real display titles carry "YYYY-MM-DD HH-MM-SS" suffixes users never type.
        assertEquals(
            "fridge_real.md",
            resolveSingleNoteTarget(
                "what does my fridge photo magnet idea note say?",
                listOf("rag_system.md" to 0.9f, "fridge_real.md" to 0.5f),
                mapOf(
                    "fridge_real.md" to "Fridge Photo Magnet Idea 2026-09-08 19-29-21",
                    "rag_system.md" to "RAG System"
                ),
                0.4f
            )
        )
    }

    @Test
    fun `reordered mention matches via word set`() {
        assertEquals(
            "fridge_magnet.md",
            resolveSingleNoteTarget(
                "magnet idea for fridge photos?",
                listOf("rag_system.md" to 0.9f),
                mapOf("fridge_magnet.md" to "Fridge Photo Magnet Idea"),
                0.4f
            )
        )
    }

    @Test
    fun `converged strong single-note results resolve`() {
        assertEquals(
            "rag_system.md",
            resolveSingleNoteTarget(
                "what can the rag system do?",
                listOf("rag_system.md" to 0.8f, "rag_system.md" to 0.5f),
                titles, 0.4f
            )
        )
    }

    @Test
    fun `results spanning two notes do not resolve`() {
        assertNull(
            resolveSingleNoteTarget(
                "compare both topics broadly",
                listOf("rag_system.md" to 0.9f, "adhd.md" to 0.8f),
                titles, 0.4f
            )
        )
    }

    @Test
    fun `weak converged top below bar does not resolve`() {
        assertNull(
            resolveSingleNoteTarget(
                "something vague",
                listOf("adhd.md" to 0.2f),
                titles, 0.4f
            )
        )
    }

    @Test
    fun `empty results do not resolve`() {
        assertNull(resolveSingleNoteTarget("anything", emptyList(), titles, 0.4f))
    }

    @Test
    fun `short titles never match`() {
        val short = mapOf("ai.md" to "AI")
        assertNull(
            resolveSingleNoteTarget(
                "said the AI assistant",
                listOf("ai.md" to 0.9f, "rag_system.md" to 0.8f),
                short + titles, 0.4f
            )
        )
    }
}
