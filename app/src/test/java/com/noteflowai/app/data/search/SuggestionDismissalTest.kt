package com.noteflowai.app.data.search

import org.junit.Assert.*
import org.junit.Test

class SuggestionDismissalTest {

    @Test
    fun `dismissed note is excluded from active suggestions`() {
        val manager = SuggestionDismissalManager()
        val note1 = "meeting_notes_2026.md"
        val note2 = "architecture_design.md"

        assertFalse(manager.isDismissed(note1))
        assertFalse(manager.isDismissed(note2))

        manager.dismiss(note1)
        assertTrue(manager.isDismissed(note1))
        assertFalse(manager.isDismissed(note2))

        val candidates = listOf(note1, note2)
        val filtered = manager.filterActive(candidates) { it }
        assertEquals(listOf(note2), filtered)
    }

    @Test
    fun `clearDismissals resets all dismissed suggestions`() {
        val manager = SuggestionDismissalManager()
        manager.dismiss("note1.md")
        manager.dismiss("note2.md")
        assertTrue(manager.isDismissed("note1.md"))

        manager.clear()
        assertFalse(manager.isDismissed("note1.md"))
        assertFalse(manager.isDismissed("note2.md"))
    }
}
