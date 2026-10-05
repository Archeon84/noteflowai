package com.noteflowai.app.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundingSupportTest {

    @Test
    fun `valid advisory offsets produce a quote range`() {
        val range = GroundingSupport.computeQuoteSpan("hello world this is a long-enough chunk", 6, 11)
        assertNotNull(range)
        assertEquals(6, range!!.start)
        assertEquals(11, range!!.end)
    }

    @Test
    fun `out-of-range or inverted advisory offsets are rejected`() {
        assertNull(GroundingSupport.computeQuoteSpan("abc", 0, 99))
        assertNull(GroundingSupport.computeQuoteSpan("abc", 9, 2))
        assertNull(GroundingSupport.computeQuoteSpan("abc", -1, 2))
        assertNull(GroundingSupport.computeQuoteSpan("abc", 2, 2))
        assertNull(GroundingSupport.computeQuoteSpan("abc", null, null))
    }

    @Test
    fun `contiguous quote of at least QUOTE_MIN_CHARS passes`() {
        assertTrue(GroundingSupport.checkQuotePresence(
            "The supplier was ACME Corporation, based in Berlin.",
            "The supplier was ACME Corporation"
        ))
    }

    @Test
    fun `short string passes via 70 percent content-token overlap`() {
        // claim has 4 content tokens; chunk contains all 4 -> ratio 1.0 >= 0.7
        assertTrue(GroundingSupport.checkQuotePresence(
            "ACME Corporation is the supplier and the price quote.",
            "ACME Corporation supplier quote"
        ))
    }

    @Test
    fun `claim sharing few tokens fails`() {
        assertFalse(GroundingSupport.checkQuotePresence(
            "The weather in Berlin is rainy today.",
            "The supplier was a completely unrelated company"
        ))
    }

    @Test
    fun `findQuoteWindow locates the first content token in the chunk`() {
        val range = GroundingSupport.findQuoteWindow(
            "The supplier was ACME Corporation, based in Berlin.",
            "ACME Corporation"
        )
        assertNotNull(range)
        assertTrue(range!!.start in 0..range!!.end)
        assertTrue(range.end <= "The supplier was ACME Corporation, based in Berlin.".length)
    }

    @Test
    fun `note block index parsed from segment id`() {
        assertEquals(2, GroundingSupport.noteBlockIndexFromSegmentId("note_meeting_block2_abc"))
        assertEquals(0, GroundingSupport.noteBlockIndexFromSegmentId("note_x_block0_uuid"))
        assertNull(GroundingSupport.noteBlockIndexFromSegmentId("seg_123"))
    }

    @Test
    fun `unicode code units counted per utf-16 (emoji safe length)`() {
        // "🎉abc" length is 5 code units (surrogate pair + 3 chars)
        val text = "🎉abc"
        assertEquals(5, text.length)
        val span = GroundingSupport.computeQuoteSpan(text, 2, 5)
        assertNotNull(span)
        assertEquals("abc", text.substring(span!!.start, span.end))
    }
}