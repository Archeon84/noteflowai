package com.noteflowai.app.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteWindowSplitterTest {

    @Test
    fun `blank and short texts`() {
        assertTrue(splitNoteWindows("").isEmpty())
        assertTrue(splitNoteWindows("   ").isEmpty())
        val single = splitNoteWindows("short note")
        assertEquals(1, single.size)
        assertEquals("short note", single.first().text)
    }

    @Test
    fun `long text splits into bounded windows with overlap`() {
        // ~6000 chars of paragraph text with default 2400-char windows.
        val para = "Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod. "
        val text = (para.repeat(20) + "\n\n").repeat(4).trim()
        val windows = splitNoteWindows(text)
        assertTrue("expected 3+ windows, got ${windows.size}", windows.size >= 3)
        windows.forEach { assertTrue("window over budget: ${it.text.length}", it.text.length <= 2400) }
        // Overlap: tail of window N recurs at the head of window N+1.
        val tail = windows[0].text.takeLast(320)
        assertTrue(windows[1].text.contains(tail.take(60)))
        // Order metadata consistent.
        windows.forEachIndexed { i, w ->
            assertEquals(i, w.index)
            assertEquals(windows.size, w.total)
        }
        // Full coverage: every long word of the source appears in some window.
        val vocab = text.split(Regex("\\s+")).filter { it.length > 8 }.toSet()
        val covered = windows.flatMap { it.text.split(Regex("\\s+")) }.toSet()
        assertTrue(covered.containsAll(vocab))
    }

    @Test
    fun `rankWindows orders by cosine and takes max over slices`() {
        val query = floatArrayOf(1f, 0f)
        val ranked = rankWindows(
            query,
            listOf(
                listOf(floatArrayOf(0f, 1f)), // orthogonal -> 0
                listOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 0f)) // max -> 1
            )
        )
        assertEquals(listOf(1, 0), ranked)
    }

    @Test
    fun `rankWindows handles empty vectors`() {
        assertEquals(listOf(0), rankWindows(floatArrayOf(1f), listOf(emptyList())))
        assertEquals(emptyList<Int>(), rankWindows(floatArrayOf(1f), emptyList()))
    }
}
