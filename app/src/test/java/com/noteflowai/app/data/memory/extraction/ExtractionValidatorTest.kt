package com.noteflowai.app.data.memory.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ExtractionValidator (personal memory layer, Phase 3).
 *
 * Validation rules per Master Plan §9:
 * - Reject unknown types.
 * - Reject missing source_segment_id.
 * - Reject source IDs not present in the input.
 * - Clamp confidence to 0.0-1.0.
 * - Reject statements not supported by the source segment.
 *
 * Robolectric stubs android.util.Log so error-path tests can run on the host JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExtractionValidatorTest {

    private val validSegmentIds = setOf("seg_1", "seg_2")

    private fun response(
        entities: List<ExtractedEntity> = emptyList(),
        memoryObjects: List<ExtractedMemoryObject> = emptyList()
    ) = ExtractionResponse(entities = entities, memory_objects = memoryObjects)

    private fun entity(
        type: String = "PERSON",
        name: String = "Alice",
        confidence: Float = 0.8f,
        segmentId: String? = "seg_1"
    ) = ExtractedEntity(
        type = type,
        canonical_name = name,
        aliases = emptyList(),
        confidence = confidence,
        source_segment_id = segmentId
    )

    private fun memoryObject(
        type: String = "DECISION",
        statement: String = "We decided to use Room",
        confidence: Float = 0.8f,
        segmentId: String = "seg_1",
        dueAt: String? = null,
        reviewAt: String? = null
    ) = ExtractedMemoryObject(
        type = type,
        statement = statement,
        reason = null,
        owner = null,
        due_at = dueAt,
        review_at = reviewAt,
        confidence = confidence,
        source_segment_id = segmentId
    )

    // ── Happy path ──────────────────────────────────────────────────────

    @Test
    fun `valid response passes through with no errors`() {
        val result = ExtractionValidator.validate(
            response(
                entities = listOf(entity()),
                memoryObjects = listOf(memoryObject())
            ),
            validSegmentIds
        )
        assertEquals(1, result.entities.size)
        assertEquals(1, result.memoryObjects.size)
        assertTrue(result.errors.isEmpty())
    }

    // ── Entity validation ───────────────────────────────────────────────

    @Test
    fun `unknown entity type is rejected`() {
        val result = ExtractionValidator.validate(
            response(entities = listOf(entity(type = "GODZILLA"))),
            validSegmentIds
        )
        assertEquals(0, result.entities.size)
        assertTrue(result.errors.any { it.contains("Unknown entity type") })
    }

    @Test
    fun `blank canonical name is rejected`() {
        val result = ExtractionValidator.validate(
            response(entities = listOf(entity(name = "  "))),
            validSegmentIds
        )
        assertEquals(0, result.entities.size)
        assertTrue(result.errors.any { it.contains("Empty canonical_name") })
    }

    @Test
    fun `overlong canonical name is rejected`() {
        val result = ExtractionValidator.validate(
            response(entities = listOf(entity(name = "A".repeat(501)))),
            validSegmentIds
        )
        assertEquals(0, result.entities.size)
        assertTrue(result.errors.any { it.contains("too long") })
    }

    @Test
    fun `entity confidence outside 0-1 is rejected`() {
        val result = ExtractionValidator.validate(
            response(entities = listOf(entity(confidence = 1.5f))),
            validSegmentIds
        )
        assertEquals(0, result.entities.size)
        assertTrue(result.errors.any { it.contains("Confidence out of range") })
    }

    // ── Memory object validation ────────────────────────────────────────

    @Test
    fun `unknown memory type is rejected`() {
        val result = ExtractionValidator.validate(
            response(memoryObjects = listOf(memoryObject(type = "VIBE"))),
            validSegmentIds
        )
        assertEquals(0, result.memoryObjects.size)
        assertTrue(result.errors.any { it.contains("Unknown memory type") })
    }

    @Test
    fun `blank statement is rejected`() {
        val result = ExtractionValidator.validate(
            response(memoryObjects = listOf(memoryObject(statement = ""))),
            validSegmentIds
        )
        assertEquals(0, result.memoryObjects.size)
        assertTrue(result.errors.any { it.contains("Empty statement") })
    }

    @Test
    fun `overlong statement is rejected`() {
        val result = ExtractionValidator.validate(
            response(memoryObjects = listOf(memoryObject(statement = "X".repeat(5001)))),
            validSegmentIds
        )
        assertEquals(0, result.memoryObjects.size)
        assertTrue(result.errors.any { it.contains("Statement too long") })
    }

    // ── Source segment ID integrity ─────────────────────────────────────

    @Test
    fun `missing source segment id is rejected`() {
        val result = ExtractionValidator.validate(
            response(memoryObjects = listOf(memoryObject(segmentId = ""))),
            validSegmentIds
        )
        assertEquals(0, result.memoryObjects.size)
        assertTrue(result.errors.any { it.contains("Missing source_segment_id") })
    }

    @Test
    fun `fabricated source segment id is rejected`() {
        val result = ExtractionValidator.validate(
            response(memoryObjects = listOf(memoryObject(segmentId = "seg_fake"))),
            validSegmentIds
        )
        assertEquals(0, result.memoryObjects.size)
        assertTrue(result.errors.any { it.contains("Fabricated source_segment_id") })
    }

    // ── Date validation ─────────────────────────────────────────────────

    @Test
    fun `invalid due date is rejected`() {
        val result = ExtractionValidator.validate(
            response(memoryObjects = listOf(memoryObject(dueAt = "not-a-date"))),
            validSegmentIds
        )
        assertEquals(0, result.memoryObjects.size)
        assertTrue(result.errors.any { it.contains("Invalid due_at") })
    }

    @Test
    fun `ISO date-only due date is accepted`() {
        val result = ExtractionValidator.validate(
            response(memoryObjects = listOf(memoryObject(dueAt = "2026-09-01"))),
            validSegmentIds
        )
        assertEquals(1, result.memoryObjects.size)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `ISO instant due date is accepted`() {
        val result = ExtractionValidator.validate(
            response(memoryObjects = listOf(memoryObject(dueAt = "2026-09-01T12:00:00Z"))),
            validSegmentIds
        )
        assertEquals(1, result.memoryObjects.size)
        assertTrue(result.errors.isEmpty())
    }

    // ── Deduplication ───────────────────────────────────────────────────

    @Test
    fun `duplicate entities keep the highest confidence`() {
        val result = ExtractionValidator.validate(
            response(entities = listOf(entity(name = "Alice", confidence = 0.5f), entity(name = "alice", confidence = 0.9f))),
            validSegmentIds
        )
        assertEquals(1, result.entities.size)
        assertEquals(0.9f, result.entities[0].confidence)
    }

    @Test
    fun `duplicate memory objects are deduplicated by type statement segment`() {
        val result = ExtractionValidator.validate(
            response(memoryObjects = listOf(
                memoryObject(statement = "Decide X", confidence = 0.4f),
                memoryObject(statement = "decide X", confidence = 0.7f)
            )),
            validSegmentIds
        )
        assertEquals(1, result.memoryObjects.size)
        assertEquals(0.7f, result.memoryObjects[0].confidence)
    }
}
