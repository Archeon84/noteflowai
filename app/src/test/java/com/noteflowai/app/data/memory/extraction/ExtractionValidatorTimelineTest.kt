package com.noteflowai.app.data.memory.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Timeline-entry validation for guide §Phase 4.
 *
 * The timeline array adds a third validated list to [ExtractionValidator]: valid entries pass
 * through with precision coerced, unknown precision strings fall back to [TemporalPrecision.UNKNOWN],
 * fabricated / missing segment ids drop the entry, and duplicate entries collapse to the highest
 * confidence one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtractionValidatorTimelineTest {

    private val validSegmentIds = setOf("seg_1", "seg_2")

    private fun response(timeline: List<ExtractedTimelineEntry>) =
        ExtractionResponse(timeline = timeline)

    private fun entry(
        title: String = "Decided to launch",
        startDate: String? = "2026-03-01",
        endDate: String? = null,
        precision: String = "EXACT",
        confidence: Float = 0.9f,
        segmentId: String? = "seg_1"
    ) = ExtractedTimelineEntry(
        title = title,
        start_date = startDate,
        end_date = endDate,
        precision = precision,
        confidence = confidence,
        source_segment_id = segmentId
    )

    @Test
    fun `valid timeline entry passes through with precision preserved`() {
        val result = ExtractionValidator.validate(
            response(listOf(entry())),
            validSegmentIds
        )
        assertEquals(1, result.timeline.size)
        assertEquals("EXACT", result.timeline[0].precision)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `unknown precision string falls back to UNKNOWN`() {
        val result = ExtractionValidator.validate(
            response(listOf(entry(precision = "NOT_A_PRECISION"))),
            validSegmentIds
        )
        assertEquals(1, result.timeline.size)
        assertEquals("UNKNOWN", result.timeline[0].precision)
    }

    @Test
    fun `entry with fabricated segment id is dropped`() {
        val result = ExtractionValidator.validate(
            response(listOf(entry(segmentId = "fabricated_seg"))),
            validSegmentIds
        )
        assertTrue(result.timeline.isEmpty())
        assertTrue(result.errors.any { it.contains("Fabricated timeline source_segment_id") })
    }

    @Test
    fun `entry with missing or blank title is dropped`() {
        val result = ExtractionValidator.validate(
            response(listOf(entry(title = "  "))),
            validSegmentIds
        )
        assertTrue(result.timeline.isEmpty())
        assertTrue(result.errors.any { it.contains("Empty timeline title") })
    }

    @Test
    fun `out of range confidence drops the entry`() {
        val result = ExtractionValidator.validate(
            response(listOf(entry(confidence = 1.5f))),
            validSegmentIds
        )
        assertTrue(result.timeline.isEmpty())
    }

    @Test
    fun `invalid date strings drop the entry`() {
        val result = ExtractionValidator.validate(
            response(listOf(entry(startDate = "not-a-date"))),
            validSegmentIds
        )
        assertTrue(result.timeline.isEmpty())
    }

    @Test
    fun `duplicate timeline entries collapse to highest confidence`() {
        val result = ExtractionValidator.validate(
            response(
                listOf(
                    entry(confidence = 0.5f),
                    entry(confidence = 0.95f)
                )
            ),
            validSegmentIds
        )
        assertEquals(1, result.timeline.size)
        assertEquals(0.95f, result.timeline[0].confidence, 0.0f)
    }

    @Test
    fun `mixed valid and invalid entries keep only valid ones`() {
        val result = ExtractionValidator.validate(
            response(
                listOf(
                    entry(),
                    entry(title = "", segmentId = "seg_2"),
                    entry(segmentId = "nope")
                )
            ),
            validSegmentIds
        )
        assertEquals(1, result.timeline.size)
        assertEquals("Decided to launch", result.timeline[0].title)
    }
}