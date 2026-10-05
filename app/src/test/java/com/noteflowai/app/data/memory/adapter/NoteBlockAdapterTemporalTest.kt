package com.noteflowai.app.data.memory.adapter

import com.noteflowai.app.data.NoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteBlockAdapterTemporalTest {

    @Test
    fun `adapt injects creation and modification metadata into text chunks and segment properties`() {
        val note = NoteFile(
            fileName = "Project_Alpha.json",
            lastModified = "Aug 25, 2026 14:00",
            preview = "First paragraph content",
            content = "# Section 1\nFirst paragraph content\n\n# Section 2\nSecond paragraph content",
            category = "Work",
            tags = listOf("project", "alpha"),
            lastModifiedEpoch = 1787666400000L,
            createdAt = "Aug 20, 2026 10:00",
            createdAtEpoch = 1787234400000L
        )

        val segments = NoteBlockAdapter.adapt(note)
        assertEquals(2, segments.size)

        // Segment 1
        val seg1 = segments[0]
        assertEquals(1787234400000L, seg1.createdAt)
        assertEquals(1787666400000L, seg1.updatedAt)
        assertTrue(seg1.text.startsWith("[Note: \"Project Alpha\" | Created: Aug 20, 2026 10:00 | Modified: Aug 25, 2026 14:00]"))
        assertTrue(seg1.text.contains("First paragraph content"))
        assertTrue(seg1.metadataJson?.contains("Project Alpha") == true)
        assertTrue(seg1.metadataJson?.contains("Aug 20, 2026 10:00") == true)

        // Segment 2
        val seg2 = segments[1]
        assertEquals(1787234400000L, seg2.createdAt)
        assertEquals(1787666400000L, seg2.updatedAt)
        assertTrue(seg2.text.startsWith("[Note: \"Project Alpha\" | Created: Aug 20, 2026 10:00 | Modified: Aug 25, 2026 14:00]"))
        assertTrue(seg2.text.contains("Second paragraph content"))
    }

    @Test
    fun `adaptAsSingle injects metadata header for single chunk notes`() {
        val note = NoteFile(
            fileName = "Single_Note.json",
            lastModified = "Aug 28, 2026 09:00",
            preview = "Single paragraph",
            content = "Single paragraph",
            createdAt = "Aug 28, 2026 09:00",
            createdAtEpoch = 1787910000000L,
            lastModifiedEpoch = 1787910000000L
        )

        val segment = NoteBlockAdapter.adaptAsSingle(note)
        assertTrue(segment != null)
        assertEquals(1787910000000L, segment!!.createdAt)
        assertEquals(1787910000000L, segment.updatedAt)
        assertTrue(segment.text.startsWith("[Note: \"Single Note\" | Created: Aug 28, 2026 09:00 | Modified: Aug 28, 2026 09:00]"))
        assertTrue(segment.text.contains("Single paragraph"))
    }
}
