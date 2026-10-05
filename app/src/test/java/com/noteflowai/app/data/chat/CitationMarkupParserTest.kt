package com.noteflowai.app.data.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class CitationMarkupParserTest {
    @Test
    fun `extracts citation markers with positions`() {
        val markers = CitationMarkupParser.scan("See [1] and [12] for details.")
        assertEquals(2, markers.size)
        assertEquals(1, markers[0].markerIndex)
        assertEquals(12, markers[1].markerIndex)
        assertEquals(4, markers[0].start)
        assertEquals(7, markers[0].end)
    }

    @Test
    fun `no markers yields empty`() {
        assertEquals(0, CitationMarkupParser.scan("plain text").size)
    }
}
