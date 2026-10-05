package com.noteflowai.app.data.memory.adapter

import com.noteflowai.app.data.NoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteBlockAdapterSemanticChunkingTest {

    @Test
    fun `adapt merges 40 list items into continuous segments without fragmenting into 40 blocks`() {
        val datesList = (1..40).joinToString("\n\n") { "2026-08-$it: Activity $it recorded in system" }
        val note = NoteFile(
            fileName = "Dates_Log.json",
            lastModified = "Aug 30, 2026 10:00",
            preview = "2026-08-1: Activity 1",
            content = datesList,
            createdAt = "Aug 30, 2026 10:00",
            createdAtEpoch = 1788000000000L,
            lastModifiedEpoch = 1788000000000L
        )

        val segments = NoteBlockAdapter.adapt(note)
        // 40 short items (~45 chars each = ~1800 chars) should be merged into 1 or 2 chunks (under 2000 chars each)
        assertTrue("Segments count should be <= 2, was ${segments.size}", segments.size <= 2)
        assertTrue(segments[0].text.contains("2026-08-1: Activity 1"))
        assertTrue(segments[0].text.contains("2026-08-20: Activity 20"))
    }

    @Test
    fun `adapt splits on markdown section headers`() {
        val noteContent = """
            # Section 1: Introduction
            This is the intro text.

            # Section 2: Requirements
            Here are the requirements.

            # Section 3: Timeline
            Here is the timeline.
        """.trimIndent()

        val note = NoteFile(
            fileName = "Document.json",
            lastModified = "Aug 30, 2026",
            preview = "Section 1",
            content = noteContent
        )

        val segments = NoteBlockAdapter.adapt(note)
        assertEquals(3, segments.size)
        assertTrue(segments[0].text.contains("Section 1: Introduction"))
        assertTrue(segments[1].text.contains("Section 2: Requirements"))
        assertTrue(segments[2].text.contains("Section 3: Timeline"))
    }
}
