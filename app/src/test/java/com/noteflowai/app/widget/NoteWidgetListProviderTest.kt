package com.noteflowai.app.widget

import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.displayTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteWidgetListProviderTest {

    @Test
    fun formatRelativeTime_justNow_withinOneMinute() {
        val now = System.currentTimeMillis()
        val result = NoteWidgetListProvider.formatRelativeTime(now - 20_000L, "2026-11-07")
        assertEquals("Just now", result)
    }

    @Test
    fun formatRelativeTime_minutesAgo() {
        val now = System.currentTimeMillis()
        val result = NoteWidgetListProvider.formatRelativeTime(now - 5 * 60_000L, "2026-11-07")
        assertEquals("5m ago", result)
    }

    @Test
    fun formatRelativeTime_hoursAgo() {
        val now = System.currentTimeMillis()
        val result = NoteWidgetListProvider.formatRelativeTime(now - 3 * 3600_000L, "2026-11-07")
        assertEquals("3h ago", result)
    }

    @Test
    fun formatRelativeTime_daysAgo() {
        val now = System.currentTimeMillis()
        val result = NoteWidgetListProvider.formatRelativeTime(now - 2 * 86400_000L, "2026-11-07")
        assertEquals("2d ago", result)
    }

    @Test
    fun formatRelativeTime_fallbackWhenEpochIsZero() {
        val fallback = "Oct 24, 2026"
        val result = NoteWidgetListProvider.formatRelativeTime(0L, fallback)
        assertEquals(fallback, result)
    }

    @Test
    fun noteDisplayTitle_cleansFileNameProperly() {
        val note = NoteFile(
            fileName = "Meeting_Notes_Quarter_4.json",
            lastModified = "2026-11-07",
            preview = "Quarterly results review"
        )
        assertEquals("Meeting Notes Quarter 4", note.displayTitle)
    }
}
