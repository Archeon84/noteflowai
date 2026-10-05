package com.noteflowai.app.data.search

import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.model.SourceType
import java.time.LocalDate
import kotlin.text.MatchResult

/**
 * Regex-based natural language query parser.
 * Extracts intent, dates, entities, and source types from user queries.
 * No LLM calls -- pure heuristics, <10ms latency.
 */
class QueryParser {

    companion object {
        // Intent keyword sets
        private val DECISION_KEYWORDS = setOf(
            "decision", "decided", "choice", "chose", "choosing",
            "agreed", "agreement", "resolution", "conclude", "conclusion",
            "rationale", "why did i", "why did we", "reason for",
            "picked", "selected", "went with", "settled on"
        )

        // Concrete commitment signals only. The old set contained bare
        // auxiliaries ("must", "should", "need to", "going to", ...) that
        // hijacked general queries — any "I must ..." became a commitment
        // lookup that ignored the query text entirely.
        private val COMMITMENT_KEYWORDS = setOf(
            "commitment", "commit", "promised", "promise", "todo",
            "task", "action item", "follow up", "follow-up", "deadline",
            "due", "overdue", "owe", "said i would"
        )

        private val ENTITY_KEYWORDS = setOf(
            "who is", "who was", "who are", "tell me about",
            "what do i know about", "information about",
            "details about", "notes about"
        )

        private val PROJECT_KEYWORDS = setOf(
            "project", "project for", "project scope", "milestone",
            "sprint", "roadmap", "initiative", "program"
        )

        private val CONFLICT_KEYWORDS = setOf(
            "conflict", "contradict", "contradiction", "inconsistent",
            "disagree", "mixed signals", "conflicting", "inconsistency"
        )

        private val CHANGE_KEYWORDS = setOf(
            "change", "changed", "evolve", "evolved", "evolution",
            "shift", "shifted", "over time", "timeline", "history",
            "how did", "how has", "progression", "before and after"
        )

        private val CREATE_NOTE_KEYWORDS = setOf(
            "create a note", "write a note", "new note", "make a note",
            "save a note", "draft a note"
        )

        private val CREATE_TASK_KEYWORDS = setOf(
            "create a task", "add a task", "new task", "make a task"
        )

        private val CREATE_DECISION_KEYWORDS = setOf(
            "create a decision", "add a decision", "new decision",
            "log a decision", "record a decision"
        )

        private val SUMMARY_KEYWORDS = setOf(
            "summary", "summarize", "summarise", "overview",
            "recap", "brief", "digest", "tldr"
        )

        // Relative date patterns
        private val RELATIVE_DATE_PATTERNS: List<Pair<Regex, (MatchResult) -> Pair<LocalDate, LocalDate>>> = listOf(
            Regex("""(?:last|past)\s+(\d+)\s+days?""") to { m: MatchResult ->
                val n = m.groupValues[1].toLong()
                Pair(LocalDate.now().minusDays(n), LocalDate.now())
            },
            Regex("""(?:last|past)\s+(\d+)\s+weeks?""") to { m: MatchResult ->
                val n = m.groupValues[1].toLong()
                Pair(LocalDate.now().minusWeeks(n), LocalDate.now())
            },
            Regex("""(?:last|past)\s+(\d+)\s+months?""") to { m: MatchResult ->
                val n = m.groupValues[1].toLong()
                Pair(LocalDate.now().minusMonths(n), LocalDate.now())
            },
            Regex("""(?:last|past)\s+(\d+)\s+years?""") to { m: MatchResult ->
                val n = m.groupValues[1].toLong()
                Pair(LocalDate.now().minusYears(n), LocalDate.now())
            },
            Regex("""last\s+week""") to {
                Pair(LocalDate.now().minusWeeks(1), LocalDate.now())
            },
            Regex("""last\s+month""") to {
                Pair(LocalDate.now().minusMonths(1), LocalDate.now())
            },
            Regex("""last\s+year""") to {
                Pair(LocalDate.now().minusYears(1), LocalDate.now())
            },
            Regex("""this\s+week""") to {
                Pair(LocalDate.now().with(java.time.DayOfWeek.MONDAY), LocalDate.now())
            },
            Regex("""this\s+month""") to {
                Pair(LocalDate.now().withDayOfMonth(1), LocalDate.now())
            },
            Regex("""this\s+year""") to {
                Pair(LocalDate.now().withDayOfYear(1), LocalDate.now())
            },
            Regex("""today""") to {
                Pair(LocalDate.now(), LocalDate.now())
            },
            Regex("""yesterday""") to {
                Pair(LocalDate.now().minusDays(1), LocalDate.now().minusDays(1))
            },
            Regex("""(\d+)\s+days?\s+ago""") to { m: MatchResult ->
                val n = m.groupValues[1].toLong()
                Pair(LocalDate.now().minusDays(n), LocalDate.now().minusDays(n))
            },
            Regex("""(\d+)\s+weeks?\s+ago""") to { m: MatchResult ->
                val n = m.groupValues[1].toLong()
                Pair(LocalDate.now().minusWeeks(n), LocalDate.now().minusWeeks(n))
            },
            Regex("""(\d+)\s+months?\s+ago""") to { m: MatchResult ->
                val n = m.groupValues[1].toLong()
                Pair(LocalDate.now().minusMonths(n), LocalDate.now().minusMonths(n))
            }
        )

        // Explicit date pattern (YYYY-MM-DD)
        private val EXPLICIT_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")

        // Source type keywords. Plurals included: matching is word-boundary
        // based (see matchesKeyword), so "notes" needs its own entry.
        private val SOURCE_TYPE_MAP = mapOf(
            "recording" to SourceType.AUDIO,
            "recordings" to SourceType.AUDIO,
            "audio" to SourceType.AUDIO,
            "transcription" to SourceType.AUDIO,
            "transcriptions" to SourceType.AUDIO,
            "transcript" to SourceType.AUDIO,
            "transcripts" to SourceType.AUDIO,
            "pdf" to SourceType.PDF,
            "pdfs" to SourceType.PDF,
            "document" to SourceType.DOCUMENT,
            "documents" to SourceType.DOCUMENT,
            "doc" to SourceType.DOCUMENT,
            "docs" to SourceType.DOCUMENT,
            "docx" to SourceType.DOCUMENT,
            "scan" to SourceType.OCR,
            "scans" to SourceType.OCR,
            "ocr" to SourceType.OCR,
            "image" to SourceType.OCR,
            "images" to SourceType.OCR,
            "youtube" to SourceType.YOUTUBE,
            "video" to SourceType.YOUTUBE,
            "videos" to SourceType.YOUTUBE,
            "note" to SourceType.NOTE,
            "notes" to SourceType.NOTE
        )

        // Unconfirmed keyword
        private val UNCONFIRMED_KEYWORDS = setOf(
            "unconfirmed", "pending", "detected", "suggested",
            "draft", "proposed", "not confirmed"
        )

        private val COMPREHENSIVE_KEYWORDS = setOf(
            "list all", "what are all", "what is all", "give me all",
            "give me every", "show all", "every", "all of the",
            "full note", "entire note", "whole note", "everything in",
            "all dates", "all items", "all tasks", "all decisions",
            "all notes", "all steps", "complete note", "everything written"
        )
    }

    /**
     * Parse a natural language query into a structured QueryPlan.
     */
    fun parse(query: String): QueryPlan {
        val lowerQuery = query.lowercase(java.util.Locale.ROOT).trim()

        // 1. Detect intent
        val intent = detectIntent(lowerQuery)

        // 2. Extract date range
        val (dateFrom, dateTo) = extractDateRange(lowerQuery)

        // 3. Extract entities (capitalized words/phrases)
        val entities = extractEntities(query)

        // 4. Extract source types
        val sourceTypes = extractSourceTypes(lowerQuery)

        // 5. Map intent to memory types
        val memoryTypes = intentToMemoryTypes(intent)

        // 6. Check for unconfirmed inclusion
        val includeUnconfirmed = lowerQuery.containsAny(UNCONFIRMED_KEYWORDS)

        // 7. Check for timeline/comparison/comprehensive requirements
        val requiresTimeline = intent == QueryIntent.CHANGE_ANALYSIS ||
            lowerQuery.containsAny(setOf("timeline", "over time", "history", "chronological"))
        val requiresComparison = lowerQuery.containsAny(
            setOf(" vs ", " versus ", "compare", "compared to", "difference between")
        )
        val requiresActionConfirmation = lowerQuery.containsAny(
            setOf("confirmed", "accepted", "approved", "active")
        )
        val isComprehensive = lowerQuery.containsAny(COMPREHENSIVE_KEYWORDS) ||
            Regex("""\b(list all|all of|all the|every single|entire note|full note)\b""").containsMatchIn(lowerQuery)

        return QueryPlan(
            intent = intent,
            queryText = query,
            dateFrom = dateFrom,
            dateTo = dateTo,
            entities = entities,
            sourceTypes = sourceTypes,
            memoryTypes = memoryTypes,
            includeUnconfirmed = includeUnconfirmed,
            requiresTimeline = requiresTimeline,
            requiresComparison = requiresComparison,
            requiresActionConfirmation = requiresActionConfirmation,
            isComprehensive = isComprehensive
        )
    }

    private fun detectIntent(query: String): QueryIntent {
        // Check create intents first (more specific)
        if (query.containsAny(CREATE_DECISION_KEYWORDS)) return QueryIntent.CREATE_DECISION
        if (query.containsAny(CREATE_TASK_KEYWORDS)) return QueryIntent.CREATE_TASK
        if (query.containsAny(CREATE_NOTE_KEYWORDS)) return QueryIntent.CREATE_NOTE

        // Check specific intents
        if (query.containsAny(DECISION_KEYWORDS)) return QueryIntent.DECISION_LOOKUP
        if (query.containsAny(COMMITMENT_KEYWORDS)) return QueryIntent.COMMITMENT_LOOKUP
        if (query.containsAny(CONFLICT_KEYWORDS)) return QueryIntent.CONFLICT_ANALYSIS
        if (query.containsAny(CHANGE_KEYWORDS)) return QueryIntent.CHANGE_ANALYSIS
        if (query.containsAny(ENTITY_KEYWORDS)) return QueryIntent.ENTITY_LOOKUP
        if (query.containsAny(PROJECT_KEYWORDS)) return QueryIntent.PROJECT_LOOKUP
        if (query.containsAny(SUMMARY_KEYWORDS)) return QueryIntent.SOURCE_SUMMARY

        // Default to general RAG
        return QueryIntent.GENERAL_RAG
    }

    private fun extractDateRange(query: String): Pair<Long?, Long?> {
        // Start of day in the DEVICE zone: the old UTC-midnight conversion
        // drifted day boundaries by hours off-UTC against UTC-ms timestamps.
        fun LocalDate.toEpochMillis(): Long =
            this.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        // Try relative date patterns first
        for ((pattern, extractor) in RELATIVE_DATE_PATTERNS) {
            val match = pattern.find(query)
            if (match != null) {
                val result = extractor(match)
                val fromMs = result.first.toEpochMillis()
                val toMs = result.second.toEpochMillis() + 86400000L // End of day
                return Pair(fromMs, toMs)
            }
        }

        // Try explicit dates
        val dates = EXPLICIT_DATE.findAll(query).toList()
        if (dates.isNotEmpty()) {
            val parsed = dates.mapNotNull { m ->
                try {
                    LocalDate.of(
                        m.groupValues[1].toInt(),
                        m.groupValues[2].toInt(),
                        m.groupValues[3].toInt()
                    )
                } catch (_: Exception) { null }
            }
            if (parsed.isNotEmpty()) {
                val fromMs = parsed.minOf { it }.toEpochMillis()
                val toMs = parsed.maxOf { it }.toEpochMillis() + 86400000L
                return Pair(fromMs, toMs)
            }
        }

        return Pair(null, null)
    }

    private fun extractEntities(query: String): List<String> {
        val entities = mutableListOf<String>()

        // Unicode-aware classes: the old [A-Z][a-z] missed NASA's, iPhone,
        // Cyrillic/CJK, and lowercase names entirely.
        val possessivePattern = Regex("""([\p{Lu}][\p{Ll}]+(?:\s+[\p{Lu}][\p{Ll}]+)*)'s""")
        for (match in possessivePattern.findAll(query)) {
            entities.add(match.groupValues[1].trim())
        }

        // Extract quoted entities
        val quotedPattern = Regex("[\"“”]([^\"“”]+)[\"“”]")
        for (match in quotedPattern.findAll(query)) {
            entities.add(match.groupValues[1].trim())
        }

        // Extract capitalized multi-word phrases (max 4 words)
        val capitalPattern = Regex("""([\p{Lu}][\p{Ll}]+(?:\s+[\p{Lu}][\p{Ll}]+){0,3})""")
        for (match in capitalPattern.findAll(query)) {
            val phrase = match.groupValues[1].trim()
            // Skip common false positives
            if (phrase.length > 2 && !phrase.lowercase(java.util.Locale.ROOT).matches(
                    Regex("^(the|and|for|with|about|from|this|that|what|how|why|when|where)$")
                )) {
                if (phrase !in entities) {
                    entities.add(phrase)
                }
            }
        }

        return entities.take(5) // Limit to 5 entities
    }

    private fun extractSourceTypes(query: String): List<SourceType> {
        val types = mutableListOf<SourceType>()
        for ((keyword, sourceType) in SOURCE_TYPE_MAP) {
            if (query.matchesKeyword(keyword) && sourceType !in types) {
                types.add(sourceType)
            }
        }
        return types
    }

    private fun intentToMemoryTypes(intent: QueryIntent): List<MemoryType> {
        return when (intent) {
            QueryIntent.DECISION_LOOKUP -> listOf(MemoryType.DECISION)
            QueryIntent.COMMITMENT_LOOKUP -> listOf(MemoryType.COMMITMENT)
            QueryIntent.ENTITY_LOOKUP -> emptyList() // Entity lookup doesn't filter by memory type
            QueryIntent.PROJECT_LOOKUP -> emptyList()
            else -> emptyList()
        }
    }

    private fun String.containsAny(keywords: Set<String>): Boolean {
        return keywords.any { matchesKeyword(it) }
    }

    /**
     * Word-boundary keyword match with plural tolerance ("commitments" still
     * matches "commitment"). The old substring match fired inside unrelated
     * words ("doc" in "doctor", "must" in "mustard") and poisoned intent +
     * source-type routing.
     */
    private fun String.matchesKeyword(keyword: String): Boolean {
        val pattern = "\\b${Regex.escape(keyword)}s?\\b"
        return Regex(pattern).containsMatchIn(this)
    }
}
