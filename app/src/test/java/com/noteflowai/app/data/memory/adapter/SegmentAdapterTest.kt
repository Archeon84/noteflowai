package com.noteflowai.app.data.memory.adapter

import com.noteflowai.app.data.memory.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adapter regression tests (Phase 2 audit fixes).
 *
 * - PDF pages: split() dropped delimiters, so N markers produced N+1 pieces
 *   and every page after the first got the wrong pageNumber.
 * - YouTube fractions: "[1:23.45]" added raw +45ms instead of +450ms, and
 *   endMs stayed null so ranges never formed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SegmentAdapterTest {

    @Test
    fun `pdf pages align with their markers`() {
        val text = """
            Intro text before any marker.
            --- Page 2 ---
            Second page body.
            --- Page 5 ---
            Fifth page body.
        """.trimIndent()

        val segments = DocumentChunkAdapter.adapt("doc1", text, "pdf")

        assertEquals(2, segments.size)
        assertTrue(segments.all { it.sourceType == SourceType.PDF })
        assertEquals(2, segments[0].pageNumber)
        assertEquals(5, segments[1].pageNumber)
        assertTrue(segments[0].text.contains("Intro text before any marker"))
        assertTrue(segments[0].text.contains("Second page body"))
        assertTrue(segments[1].text.contains("Fifth page body"))
    }

    @Test
    fun `pdf without markers falls back to paragraphs`() {
        val segments = DocumentChunkAdapter.adapt(
            "doc2",
            "Para one.\n\nPara two.",
            "pdf"
        )
        assertTrue(segments.isNotEmpty())
        assertTrue(segments.all { it.sourceType == SourceType.PDF })
    }

    @Test
    fun `youtube fractional seconds scale to millis and ranges chain`() {
        val text = "[1:23.45] First utterance\n[1:30] Second utterance"

        val segments = YouTubeSegmentAdapter.adapt("vid1", text, "https://youtu.be/x")

        assertEquals(2, segments.size)
        // 1*60_000 + 23*1000 + 450, not +45.
        assertEquals(83_450L, segments[0].startMs)
        assertEquals(90_000L, segments[1].startMs)
        // endMs chains from the next start; last segment stays open.
        assertEquals(90_000L, segments[0].endMs)
        assertEquals(null, segments[1].endMs)
    }

    @Test
    fun `youtube plain text yields one open segment`() {
        val segments = YouTubeSegmentAdapter.adapt("vid2", "Just some captions", null)
        assertEquals(1, segments.size)
        assertEquals(null, segments[0].startMs)
    }
}
