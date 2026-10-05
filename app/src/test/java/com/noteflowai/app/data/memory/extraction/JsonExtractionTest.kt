package com.noteflowai.app.data.memory.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the shared LLM-output handling (Phase 2 audit fixes).
 *
 * - JsonExtraction: the old greedy `\{.*\}` fallback grabbed trailing prose
 *   into the parse and zeroed extractions.
 * - LenientDates: valid LLM dates (offset-less datetimes, written forms,
 *   epochs) used to fall through to null and silently drop due/start dates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JsonExtractionTest {

    // ── Balanced-brace carving ──────────────────────────────────────

    @Test
    fun `trailing prose after json is excluded`() {
        val text = """{"a": 1} Here is some trailing prose with a } brace."""
        assertEquals("""{"a": 1}""", JsonExtraction.extractJsonObject(text))
    }

    @Test
    fun `leading prose before json is skipped`() {
        val text = """Sure! Here you go: {"a": {"b": [1, 2]}} Hope that helps."""
        assertEquals("""{"a": {"b": [1, 2]}}""", JsonExtraction.extractJsonObject(text))
    }

    @Test
    fun `braces inside string literals do not affect depth`() {
        val text = """{"statement": "use {braces} carefully"} trailing"""
        assertEquals(text.substring(0, text.indexOf(" trailing")), JsonExtraction.extractJsonObject(text))
    }

    @Test
    fun `markdown fence is unwrapped`() {
        val text = "```json\n{\"a\": 1}\n```"
        assertEquals("""{"a": 1}""", JsonExtraction.extractJsonObject(text))
    }

    @Test
    fun `no json returns null`() {
        assertNull(JsonExtraction.extractJsonObject("just some prose, no braces"))
    }

    @Test
    fun `unbalanced json returns null`() {
        assertNull(JsonExtraction.extractJsonObject("""{"a": 1"""))
    }

    // ── Lenient dates ───────────────────────────────────────────────

    @Test
    fun `instant parses`() {
        assertEquals(1_771_149_600_000L, LenientDates.parseToMillis("2026-02-15T10:00:00Z"))
    }

    @Test
    fun `offset-less datetime parses as UTC`() {
        assertEquals(1_771_149_600_000L, LenientDates.parseToMillis("2026-02-15T10:00:00"))
    }

    @Test
    fun `plain date parses as UTC midnight`() {
        assertEquals(1_771_113_600_000L, LenientDates.parseToMillis("2026-02-15"))
    }

    @Test
    fun `written dates parse`() {
        assertEquals(1_771_113_600_000L, LenientDates.parseToMillis("February 15, 2026"))
        assertEquals(1_771_113_600_000L, LenientDates.parseToMillis("15 February 2026"))
    }

    @Test
    fun `epoch millis and seconds parse`() {
        assertEquals(1_771_113_600_000L, LenientDates.parseToMillis("1771113600000"))
        assertEquals(1_771_113_600_000L, LenientDates.parseToMillis("1771113600"))
    }

    @Test
    fun `garbage returns null`() {
        assertNull(LenientDates.parseToMillis("soon-ish"))
        assertNull(LenientDates.parseToMillis(""))
        assertNull(LenientDates.parseToMillis(null))
    }

    // ── Validator integration ───────────────────────────────────────

    @Test
    fun `fabricated entity segment id is rejected`() {
        val result = ExtractionValidator.validate(
            ExtractionResponse(
                entities = listOf(
                    ExtractedEntity("PERSON", "Alice", emptyList(), 0.8f, "seg_nope")
                )
            ),
            setOf("seg_1")
        )
        assertTrue(result.entities.isEmpty())
        assertTrue(result.errors.any { it.contains("Fabricated entity source_segment_id") })
    }

    @Test
    fun `null entity segment id passes validation`() {
        val result = ExtractionValidator.validate(
            ExtractionResponse(
                entities = listOf(
                    ExtractedEntity("PERSON", "Alice", emptyList(), 0.8f, null)
                )
            ),
            setOf("seg_1")
        )
        // Null segments are a store-time skip, not a validation rejection.
        assertEquals(1, result.entities.size)
    }

    @Test
    fun `written due date passes validation`() {
        val result = ExtractionValidator.validate(
            ExtractionResponse(
                memory_objects = listOf(
                    ExtractedMemoryObject(
                        type = "COMMITMENT",
                        statement = "Ship it",
                        due_at = "March 15, 2026",
                        source_segment_id = "seg_1"
                    )
                )
            ),
            setOf("seg_1")
        )
        assertTrue(result.errors.isEmpty())
        assertEquals(1, result.memoryObjects.size)
    }
}
