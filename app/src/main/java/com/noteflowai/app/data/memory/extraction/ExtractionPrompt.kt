package com.noteflowai.app.data.memory.extraction

/**
 * Builds extraction prompts for the Personal Memory Layer.
 * The prompt asks the LLM to return strict JSON with entities and memory objects
 * extracted from source segment text.
 */
object ExtractionPrompt {

    /**
     * The strict JSON schema the model must return.
     */
    const val RESPONSE_SCHEMA = """
{
  "entities": [
    {
      "type": "PERSON|PROJECT|COMPANY|PLACE|TOPIC|PRODUCT|ORGANIZATION",
      "canonical_name": "string",
      "aliases": ["string"],
      "confidence": 0.0,
      "source_segment_id": "string (must match one of the provided segment IDs)"
    }
  ],
  "memory_objects": [
    {
      "type": "DECISION|COMMITMENT|QUESTION|IDEA|FACT|OPINION",
      "statement": "string",
      "reason": "string or null",
      "owner": "string or null",
      "due_at": "ISO-8601 or null",
      "review_at": "ISO-8601 or null",
      "date": "ISO-8601 or null (when the decision/event itself happened, if stated)",
      "confidence": 0.0,
      "source_segment_id": "string (must match one of the provided segment IDs)"
    }
  ],
  "timeline": [
    {
      "title": "string",
      "start_date": "ISO-8601 or null",
      "end_date": "ISO-8601 or null",
      "precision": "EXACT|DAY_RANGE|MONTH|YEAR|RELATIVE|UNKNOWN",
      "confidence": 0.0,
      "source_segment_id": "string (must match one of the provided segment IDs)"
    }
  ],
  "relations": [
    {
      "from_entity": "string (canonical name of source entity)",
      "relation": "RELATED_TO|SUPPORTS|CONTRADICTS|FOLLOWS_UP|BELONGS_TO|SUPERSEDES|MENTIONS",
      "to_entity": "string (canonical name of target entity)",
      "confidence": 0.0,
      "source_segment_id": "string (must match one of the provided segment IDs)"
    }
  ]
}
"""

    /**
     * Build the system prompt for extraction.
     */
    fun buildSystemPrompt(): String {
        return """You are a precise information extraction assistant. Your task is to extract structured entities and memory objects from the provided text.

RULES:
1. Return ONLY valid JSON matching the schema below. No markdown, no explanations.
2. Entity types: PERSON, PROJECT, COMPANY, PLACE, TOPIC, PRODUCT, ORGANIZATION.
3. Memory types: DECISION, COMMITMENT, QUESTION, IDEA, FACT, OPINION.
4. A DECISION is something the user or speaker decided (e.g., "We decided to use Room", "I chose the blue option").
5. A COMMITMENT is a promise or action item (e.g., "I'll send the report by Friday", "We need to fix the bug").
6. A QUESTION is an open question or uncertainty (e.g., "Should we use X or Y?", "What's the deadline?").
7. An IDEA is a proposal or suggestion (e.g., "What if we tried...", "We could also consider...").
8. A FACT is a stated fact (e.g., "The meeting is at 3pm", "Revenue increased 20%").
9. An OPINION is a subjective view (e.g., "I think X is better", "This approach seems risky").
10. Set confidence between 0.0 and 1.0. Use 0.5+ for things clearly stated, 0.3-0.5 for implied, below 0.3 for uncertain.
11. For source_segment_id, use EXACTLY the ID provided in the input. Do not invent IDs.
12. Only extract what is explicitly stated or clearly implied. Do not hallucinate.
13. Do not extract trivial or obvious information.
14. owner and due_at for COMMITMENT should only be set if explicitly stated.
15. reason for DECISION should only be set if explicitly stated.
16. A TIMELINE entry is a dated or time-bounded event worth remembering (e.g., "The meeting is on Friday", "We shipped v2 in March", "The deadline moved to next week"). Do NOT extract trivial dates (today's date, a random timestamp). Prefer specific dates; use precision EXACT for a single concrete date, DAY_RANGE when a start and end are both given, MONTH/YEAR for a month or year only, RELATIVE for expressions like "last week" or "soon", and UNKNOWN when no usable date material is present.
17. For timeline entries, use EXACTLY the segment ID provided in the input. Do not invent IDs.
18. If no entities, memory objects, or timeline entries are found, return empty arrays.
19. A RELATION is a typed link between two entities you extracted. Use BELONGS_TO when an entity is a member/part of another (e.g. person belongs to org, project belongs to org). Use RELATED_TO for general associations. Use SUPPORTS/CONTRADICTS for corroborating or opposing claims. Use FOLLOWS_UP when one decision/idea follows from another. Use SUPERSEDES when a new decision replaces an old one. Use MENTIONS when a person/org is simply referenced in context of another entity. Only extract relations that are clearly stated.
20. Both from_entity and to_entity must match canonical_name values of entities you extracted in the same response. Do not invent entity names for relations.

SCHEMA:
$RESPONSE_SCHEMA"""
    }

    /**
     * Build the user message containing source segments to extract from.
     */
    fun buildUserMessage(segments: List<ExtractionInputSegment>): String {
        val sb = StringBuilder()
        sb.appendLine("Extract entities and memory objects from the following source segments:")
        sb.appendLine()

        for (segment in segments) {
            sb.appendLine("---")
            sb.appendLine("Segment ID: ${segment.segmentId}")
            sb.appendLine("Source: ${segment.sourceId} (${segment.sourceType})")
            if (segment.date != null) sb.appendLine("Date: ${segment.date}")
            if (segment.speaker != null) sb.appendLine("Speaker: ${segment.speaker}")
            sb.appendLine("Text:")
            sb.appendLine(segment.text)
            sb.appendLine()
        }

        sb.appendLine("Return the extraction as JSON matching the provided schema.")
        return sb.toString()
    }
}

/**
 * Input segment for extraction.
 */
data class ExtractionInputSegment(
    val segmentId: String,
    val sourceId: String,
    val sourceType: String,
    val text: String,
    val date: String? = null,
    val speaker: String? = null
)

/**
 * The expected JSON response structure from the LLM.
 */
data class ExtractionResponse(
    val entities: List<ExtractedEntity> = emptyList(),
    val memory_objects: List<ExtractedMemoryObject> = emptyList(),
    val timeline: List<ExtractedTimelineEntry> = emptyList(),
    val relations: List<ExtractedRelation> = emptyList()
)

/**
 * A dated / time-bounded event the LLM surfaced for the timeline (guide §Phase 4).
 *
 * [start_date]/[end_date] are ISO-8601 date strings (date or date-time). Both null for
 * RELATIVE / UNKNOWN entries. [precision] is the string from the schema and is coerced
 * to [TemporalPrecision] at validation time (unknown strings fall back to UNKNOWN).
 */
data class ExtractedTimelineEntry(
    val title: String,
    val start_date: String? = null,
    val end_date: String? = null,
    val precision: String = "UNKNOWN",
    val confidence: Float = 0.5f,
    val source_segment_id: String? = null
)

data class ExtractedEntity(
    val type: String,
    val canonical_name: String,
    val aliases: List<String> = emptyList(),
    val confidence: Float = 0.5f,
    val source_segment_id: String? = null
)

data class ExtractedMemoryObject(
    val type: String,
    val statement: String,
    val reason: String? = null,
    val owner: String? = null,
    val due_at: String? = null,
    val review_at: String? = null,
    val date: String? = null,
    val confidence: Float = 0.5f,
    val source_segment_id: String
)

data class ExtractedRelation(
    val from_entity: String,
    val relation: String,
    val to_entity: String,
    val confidence: Float = 0.5f,
    val source_segment_id: String? = null
)
