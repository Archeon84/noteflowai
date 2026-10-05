package com.noteflowai.app.data.memory.extraction

import android.util.Log
import com.noteflowai.app.data.memory.model.EntityType
import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.model.RelationType
import com.noteflowai.app.data.memory.model.TemporalPrecision

/**
 * Validates extraction results from the LLM.
 * Rejects invalid types, missing IDs, out-of-range confidence, and fabricated source IDs.
 */
object ExtractionValidator {

    private const val TAG = "ExtractionValidator"

    private val VALID_ENTITY_TYPES = EntityType.values().map { it.name }.toSet()
    private val VALID_MEMORY_TYPES = MemoryType.values().map { it.name }.toSet()
    private val VALID_PRECISIONS = TemporalPrecision.values().map { it.name }.toSet()
    private val VALID_RELATION_TYPES = RelationType.values().map { it.name }.toSet()

    /**
     * Validate an extraction response. Returns a filtered copy with invalid items removed,
     * plus a list of validation errors for logging.
     */
    fun validate(
        response: ExtractionResponse,
        validSegmentIds: Set<String>
    ): ValidationResult {
        val errors = mutableListOf<String>()
        val validEntities = mutableListOf<ExtractedEntity>()
        val validMemoryObjects = mutableListOf<ExtractedMemoryObject>()
        val validTimeline = mutableListOf<ExtractedTimelineEntry>()
        val validRelations = mutableListOf<ExtractedRelation>()

        // Validate entities
        for (entity in response.entities) {
            val entityErrors = validateEntity(entity, validSegmentIds)
            if (entityErrors.isEmpty()) {
                validEntities.add(entity)
            } else {
                errors.addAll(entityErrors)
            }
        }

        // Validate memory objects
        for (obj in response.memory_objects) {
            val objErrors = validateMemoryObject(obj, validSegmentIds)
            if (objErrors.isEmpty()) {
                validMemoryObjects.add(obj)
            } else {
                errors.addAll(objErrors)
            }
        }

        // Validate timeline entries
        for (entry in response.timeline) {
            val entryErrors = validateTimelineEntry(entry, validSegmentIds)
            if (entryErrors.isEmpty()) {
                validTimeline.add(normalizeTimelineEntry(entry))
            } else {
                errors.addAll(entryErrors)
            }
        }

        // Validate relations — both endpoints must be among extracted entity names
        val extractedEntityNames = validEntities.map { it.canonical_name.lowercase().trim() }.toSet()
        for (rel in response.relations) {
            val relErrors = validateRelation(rel, validSegmentIds, extractedEntityNames)
            if (relErrors.isEmpty()) {
                validRelations.add(rel)
            } else {
                errors.addAll(relErrors)
            }
        }

        // Check for duplicate entities (same canonical_name + type)
        val dedupedEntities = deduplicateEntities(validEntities)
        val dedupedObjects = deduplicateMemoryObjects(validMemoryObjects)
        val dedupedTimeline = deduplicateTimeline(validTimeline)
        val dedupedRelations = deduplicateRelations(validRelations)

        if (errors.isNotEmpty()) {
            Log.w(TAG, "Validation errors: ${errors.joinToString("; ")}")
        }

        return ValidationResult(
            entities = dedupedEntities,
            memoryObjects = dedupedObjects,
            timeline = dedupedTimeline,
            relations = dedupedRelations,
            errors = errors
        )
    }

    private fun validateEntity(entity: ExtractedEntity, validSegmentIds: Set<String>): List<String> {
        val errors = mutableListOf<String>()

        if (entity.type !in VALID_ENTITY_TYPES) {
            errors.add("Unknown entity type: ${entity.type}")
        }
        // Entities had no segment check while memory/timeline rejected
        // fabricated ids — a hallucinated entity segment id passed validation
        // and was silently re-attributed by the store fallback.
        if (entity.source_segment_id != null && entity.source_segment_id !in validSegmentIds) {
            errors.add("Fabricated entity source_segment_id: ${entity.source_segment_id}")
        }
        if (entity.canonical_name.isBlank()) {
            errors.add("Empty canonical_name")
        }
        if (entity.canonical_name.length > 500) {
            errors.add("canonical_name too long: ${entity.canonical_name.length} chars")
        }
        if (entity.confidence !in 0.0f..1.0f) {
            errors.add("Confidence out of range: ${entity.confidence}")
        }

        return errors
    }

    private fun validateMemoryObject(obj: ExtractedMemoryObject, validSegmentIds: Set<String>): List<String> {
        val errors = mutableListOf<String>()

        if (obj.type !in VALID_MEMORY_TYPES) {
            errors.add("Unknown memory type: ${obj.type}")
        }
        if (obj.statement.isBlank()) {
            errors.add("Empty statement")
        }
        if (obj.statement.length > 5000) {
            errors.add("Statement too long: ${obj.statement.length} chars")
        }
        if (obj.source_segment_id.isBlank()) {
            errors.add("Missing source_segment_id")
        } else if (obj.source_segment_id !in validSegmentIds) {
            errors.add("Fabricated source_segment_id: ${obj.source_segment_id}")
        }
        if (obj.confidence !in 0.0f..1.0f) {
            errors.add("Confidence out of range: ${obj.confidence}")
        }
        if (obj.due_at != null && !isValidDate(obj.due_at)) {
            errors.add("Invalid due_at date: ${obj.due_at}")
        }
        if (obj.review_at != null && !isValidDate(obj.review_at)) {
            errors.add("Invalid review_at date: ${obj.review_at}")
        }
        if (obj.date != null && !isValidDate(obj.date)) {
            errors.add("Invalid date: ${obj.date}")
        }

        return errors
    }

    private fun validateTimelineEntry(entry: ExtractedTimelineEntry, validSegmentIds: Set<String>): List<String> {
        val errors = mutableListOf<String>()

        if (entry.title.isBlank()) {
            errors.add("Empty timeline title")
        }
        if (entry.title.length > 500) {
            errors.add("Timeline title too long: ${entry.title.length} chars")
        }
        if (entry.source_segment_id.isNullOrBlank()) {
            errors.add("Missing timeline source_segment_id")
        } else if (entry.source_segment_id !in validSegmentIds) {
            errors.add("Fabricated timeline source_segment_id: ${entry.source_segment_id}")
        }
        if (entry.confidence !in 0.0f..1.0f) {
            errors.add("Timeline confidence out of range: ${entry.confidence}")
        }
        entry.start_date?.let {
            if (!isValidDate(it)) errors.add("Invalid timeline start_date: $it")
        }
        entry.end_date?.let {
            if (!isValidDate(it)) errors.add("Invalid timeline end_date: $it")
        }

        return errors
    }

    /**
     * Coerce an extracted timeline entry into its canonical form: an unknown precision
     * string or a non-empty-but-blank title is normalized to UNKNOWN / a safe default so
     * a bad label never crashes persistence. The segment id is required by validation.
     */
    private fun normalizeTimelineEntry(entry: ExtractedTimelineEntry): ExtractedTimelineEntry {
        return entry.copy(
            precision = entry.precision.takeIf { it in VALID_PRECISIONS } ?: TemporalPrecision.UNKNOWN.name,
            title = entry.title.trim()
        )
    }

    private fun deduplicateTimeline(entries: List<ExtractedTimelineEntry>): List<ExtractedTimelineEntry> {
        return entries.groupBy { "${it.title.lowercase().trim()}|${it.start_date}|${it.end_date}|${it.source_segment_id}" }
            .map { (_, group) ->
                group.maxByOrNull { it.confidence } ?: group.first()
            }
    }

    private fun deduplicateRelations(relations: List<ExtractedRelation>): List<ExtractedRelation> {
        return relations.groupBy { "${it.from_entity.lowercase().trim()}|${it.relation}|${it.to_entity.lowercase().trim()}" }
            .map { (_, group) ->
                group.maxByOrNull { it.confidence } ?: group.first()
            }
    }

    private fun validateRelation(
        rel: ExtractedRelation,
        validSegmentIds: Set<String>,
        extractedEntityNames: Set<String>
    ): List<String> {
        val errors = mutableListOf<String>()
        if (rel.relation !in VALID_RELATION_TYPES) {
            errors.add("Unknown relation type: ${rel.relation}")
        }
        if (rel.from_entity.isBlank()) errors.add("Relation from_entity is blank")
        if (rel.to_entity.isBlank()) errors.add("Relation to_entity is blank")
        if (rel.from_entity.lowercase().trim() !in extractedEntityNames) {
            errors.add("Relation from_entity not among extracted entities: ${rel.from_entity}")
        }
        if (rel.to_entity.lowercase().trim() !in extractedEntityNames) {
            errors.add("Relation to_entity not among extracted entities: ${rel.to_entity}")
        }
        if (rel.source_segment_id != null && rel.source_segment_id !in validSegmentIds) {
            errors.add("Fabricated relation source_segment_id: ${rel.source_segment_id}")
        }
        if (rel.confidence !in 0.0f..1.0f) {
            errors.add("Relation confidence out of range: ${rel.confidence}")
        }
        return errors
    }

    private fun isValidDate(dateStr: String): Boolean = LenientDates.isValid(dateStr)

    private fun deduplicateEntities(entities: List<ExtractedEntity>): List<ExtractedEntity> {
        return entities.groupBy { "${it.type}|${it.canonical_name.lowercase().trim()}" }
            .map { (_, group) ->
                group.maxByOrNull { it.confidence } ?: group.first()
            }
    }

    private fun deduplicateMemoryObjects(objects: List<ExtractedMemoryObject>): List<ExtractedMemoryObject> {
        return objects.groupBy { "${it.type}|${it.statement.lowercase().trim()}|${it.source_segment_id}" }
            .map { (_, group) ->
                group.maxByOrNull { it.confidence } ?: group.first()
            }
    }
}

data class ValidationResult(
    val entities: List<ExtractedEntity>,
    val memoryObjects: List<ExtractedMemoryObject>,
    val timeline: List<ExtractedTimelineEntry> = emptyList(),
    val relations: List<ExtractedRelation> = emptyList(),
    val errors: List<String>
)
