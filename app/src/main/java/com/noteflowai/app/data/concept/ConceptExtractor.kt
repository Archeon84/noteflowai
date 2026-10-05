package com.noteflowai.app.data.concept

import android.content.Context
import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.search.NoteSearchIndex

/**
 * Extracts concepts, entities, and themes from note text.
 *
 * Uses lightweight NLP heuristics (no external API):
 * - Named entity recognition via capitalization patterns
 * - Recurring term extraction across the user's corpus
 * - Verb phrase detection for action concepts
 * - Temporal expression parsing
 * - Technical identifier detection (code spans, file names, qualified names)
 *
 * Each concept gets a canonical form (lowercased, deduplicated) and a
 * frequency count across the corpus for importance weighting.
 */
class ConceptExtractor(private val searchIndex: NoteSearchIndex) {

    companion object {
        /** Minimum times a term must appear across corpus to be a "concept". */
        private const val MIN_CONCEPT_FREQUENCY = 2

        /** Max concepts per note to avoid noise. */
        private const val MAX_CONCEPTS_PER_NOTE = 15

        /** Min token length to consider as a concept. */
        private const val MIN_TOKEN_LENGTH = 3

        private val STOP_PHRASES = setOf(
            "this", "that", "these", "those", "it", "its", "he", "she", "we",
            "they", "you", "i", "what", "when", "where", "which", "who", "how",
            "ok", "okay", "yes", "no", "am", "pm"
        )

        private val STOP_STARTS = listOf(
            "the ", "and ", "but ", "for ", "not ", "you ", "all ", "can ",
            "had ", "her ", "was ", "one ", "our ", "out ", "this ", "that ",
            "with ", "from ", "have ", "has ", "will ", "would ", "there ",
            "their ", "then ", "than ", "when ", "what ", "also ", "just "
        )

        private val ACRONYM_STOPLIST = setOf("OK", "TV", "AM", "PM", "II", "III", "IV", "VI")

        /** Content window per note; the overflow is logged, not silent. */
        private const val MAX_CONTENT_CHARS = 4000

        private const val TAG = "ConceptExtractor"

        @Volatile
        private var INSTANCE: ConceptExtractor? = null

        fun getInstance(context: Context): ConceptExtractor {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConceptExtractor(
                    NoteSearchIndex()
                ).also { INSTANCE = it }
            }
        }
    }

    /**
     * A single extracted concept from a note.
     */
    data class ExtractedConcept(
        val canonicalForm: String,      // lowercase, deduplicated
        val displayForm: String,        // original casing for display
        val type: ConceptType,
        val sourceNote: String,         // fileName
        val context: String,            // surrounding text snippet
        val importance: Float           // 0.0-1.0 based on corpus frequency
    )

    enum class ConceptType {
        ENTITY,         // Named entity (person, place, product)
        THEME,          // Recurring topic/theme
        ACTION,         // Verb phrase (doing something)
        TEMPORAL,       // Time reference
        TECHNICAL       // Domain-specific term
    }

    /**
     * Extract concepts from a single note.
     */
    fun extractFromNote(note: NoteFile): List<ExtractedConcept> {
        return extractFromContent(note.content, note.fileName)
    }

    /**
     * Extract concepts from raw content without requiring a NoteFile.
     * Used by NoteSuggestionEngine to avoid creating temporary NoteFile objects.
     */
    fun extractFromContent(content: String, sourceNote: String = "temp.md"): List<ExtractedConcept> {
        // Truncation is documented, not silent: extraction quality degrades
        // past the window, but callers can see how much was dropped.
        val truncated = content.length > MAX_CONTENT_CHARS
        val text = content.take(MAX_CONTENT_CHARS)
        if (truncated) {
            android.util.Log.d(TAG, "extract truncated to $MAX_CONTENT_CHARS chars for $sourceNote")
        }
        val tokens = searchIndex.tokenize(text)
        val concepts = mutableListOf<ExtractedConcept>()

        // 1. Named entities (capitalized multi-word phrases)
        concepts.addAll(extractEntities(text, sourceNote))

        // 2. Recurring themes (tokens that appear frequently in this note)
        concepts.addAll(extractThemes(tokens, sourceNote, text))

        // 3. Action concepts (verb + noun patterns)
        concepts.addAll(extractActions(text, sourceNote))

        // 4. Temporal concepts
        concepts.addAll(extractTemporal(text, sourceNote))

        // 5. Technical concepts (code identifiers, file names, code spans)
        concepts.addAll(extractTechnical(text, sourceNote))

        // Dedup on type + canonical form: an ENTITY and a TECHNICAL concept
        // sharing a surface form are different signals and must not collapse
        // into whichever came first.
        return concepts
            .distinctBy { "${it.type}|${it.canonicalForm}" }
            .take(MAX_CONCEPTS_PER_NOTE)
    }

    /**
     * Extract concepts from the entire corpus.
     * Returns a map of concept -> list of notes it appears in.
     */
    fun extractCorpusConcepts(notes: List<NoteFile>): Map<String, List<ExtractedConcept>> {
        val allConcepts = mutableMapOf<String, MutableList<ExtractedConcept>>()

        for (note in notes) {
            val concepts = extractFromNote(note)
            for (concept in concepts) {
                allConcepts.getOrPut(concept.canonicalForm) { mutableListOf() }.add(concept)
            }
        }

        // Compute importance based on corpus frequency
        val totalNotes = notes.size.toFloat()
        return allConcepts.mapValues { (_, conceptList) ->
            val frequency = conceptList.size
            val importance = (frequency / totalNotes).coerceIn(0.1f, 1.0f)
            conceptList.map { it.copy(importance = importance) }
        }
    }

    // ── Named Entity Extraction ────────────────────────────────

    private fun extractEntities(text: String, fileName: String): List<ExtractedConcept> {
        val entities = mutableListOf<ExtractedConcept>()

        // Pattern: 2-4 consecutive capitalized words (excluding sentence starts)
        val entityPattern = Regex("(?<!\\.\\s)(?:\\b([A-Z][a-z]+(?:\\s[A-Z][a-z]+){0,3})\\b)")

        // Also capture ALL-CAPS terms (acronyms, product names)
        val acronymPattern = Regex("\\b([A-Z]{2,6})\\b")

        for (match in entityPattern.findAll(text)) {
            val entity = match.value.trim()
            if (entity.length >= MIN_TOKEN_LENGTH && !isStopPhrase(entity)) {
                val snippet = extractContext(text, match.range.first, 60)
                entities.add(ExtractedConcept(
                    canonicalForm = entity.lowercase(),
                    displayForm = entity,
                    type = ConceptType.ENTITY,
                    sourceNote = fileName,
                    context = snippet,
                    importance = 0.5f
                ))
            }
        }

        for (match in acronymPattern.findAll(text)) {
            val entity = match.value
            // Acronyms skip the length/stop checks above, so bare "OK", "TV",
            // "AM"/"PM" and Roman-numeral section headers ("IV") flooded the
            // graph. Same bar as capitalized entities, plus an acronym
            // stoplist for the 2-letter interjections the length check keeps.
            if (entity.length < MIN_TOKEN_LENGTH || isStopPhrase(entity) ||
                entity.uppercase() in ACRONYM_STOPLIST
            ) {
                continue
            }
            val snippet = extractContext(text, match.range.first, 60)
            entities.add(ExtractedConcept(
                canonicalForm = entity.lowercase(),
                displayForm = entity,
                type = ConceptType.ENTITY,
                sourceNote = fileName,
                context = snippet,
                importance = 0.6f
            ))
        }

        return entities
    }

    // ── Theme Extraction ───────────────────────────────────────

    private fun extractThemes(tokens: List<String>, fileName: String, text: String): List<ExtractedConcept> {
        val counts = mutableMapOf<String, Int>()
        for (token in tokens) {
            if (token.length >= MIN_TOKEN_LENGTH) {
                counts[token] = (counts[token] ?: 0) + 1
            }
        }

        return counts.entries
            .filter { it.value >= 2 }  // appears at least twice in this note
            .sortedByDescending { it.value }
            .take(5)
            .map { (term, count) ->
                val idx = text.lowercase().indexOf(term)
                val snippet = if (idx >= 0) extractContext(text, idx, 60) else ""
                ExtractedConcept(
                    canonicalForm = term,
                    displayForm = term,
                    type = ConceptType.THEME,
                    sourceNote = fileName,
                    context = snippet,
                    importance = (count.toFloat() / tokens.size).coerceIn(0.1f, 1.0f)
                )
            }
    }

    // ── Action Extraction ──────────────────────────────────────

    private fun extractActions(text: String, fileName: String): List<ExtractedConcept> {
        val actions = mutableListOf<ExtractedConcept>()
        val actionPatterns = listOf(
            Regex("\\b(\\w+ing\\s+\\w+(?:\\s+\\w+)?)", RegexOption.IGNORE_CASE),  // "launching the product"
            Regex("\\b(to\\s+\\w+\\s+\\w+)", RegexOption.IGNORE_CASE),              // "to fix the bug"
            Regex("\\b(should|need to|must|going to|plan to|want to)\\s+(\\w+(?:\\s+\\w+){0,2})", RegexOption.IGNORE_CASE)
        )

        for (pattern in actionPatterns) {
            for (match in pattern.findAll(text)) {
                val action = match.value.trim()
                if (action.length in 5..50) {
                    val snippet = extractContext(text, match.range.first, 60)
                    actions.add(ExtractedConcept(
                        canonicalForm = action.lowercase(),
                        displayForm = action,
                        type = ConceptType.ACTION,
                        sourceNote = fileName,
                        context = snippet,
                        importance = 0.4f
                    ))
                }
            }
        }

        return actions.distinctBy { it.canonicalForm }.take(3)
    }

    // ── Temporal Extraction ────────────────────────────────────

    private fun extractTemporal(text: String, fileName: String): List<ExtractedConcept> {
        val temporals = mutableListOf<ExtractedConcept>()
        val temporalPatterns = listOf(
            Regex("\\b(Q[1-4]\\s*\\d{4})\\b", RegexOption.IGNORE_CASE),           // "Q1 2026"
            Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b"),                                    // "2026-03-15"
            Regex("\\b(January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s*\\d{0,4}", RegexOption.IGNORE_CASE),
            Regex("\\b(last|next|this)\\s+(week|month|quarter|year|meeting|monday|tuesday|wednesday|thursday|friday)", RegexOption.IGNORE_CASE),
            Regex("\\b(\\d{1,2})\\s*(days?|weeks?|months?)\\s*(ago|from now|before|after)", RegexOption.IGNORE_CASE)
        )

        for (pattern in temporalPatterns) {
            for (match in pattern.findAll(text)) {
                val temporal = match.value.trim()
                val snippet = extractContext(text, match.range.first, 60)
                temporals.add(ExtractedConcept(
                    canonicalForm = temporal.lowercase(),
                    displayForm = temporal,
                    type = ConceptType.TEMPORAL,
                    sourceNote = fileName,
                    context = snippet,
                    importance = 0.3f
                ))
            }
        }

        return temporals.distinctBy { it.canonicalForm }.take(3)
    }

    // ── Technical Extraction ───────────────────────────────────

    private fun extractTechnical(text: String, fileName: String): List<ExtractedConcept> {
        val technicals = mutableListOf<ExtractedConcept>()
        val technicalPatterns = listOf(
            Regex("`([^`\\n]{2,40})`"),                                              // `code span`
            Regex("\\b[\\w-]+\\.(kt|java|py|js|ts|tsx|md|json|xml|yml|yaml|gradle|sql|csv|txt)\\b"), // MainViewModel.kt
            Regex("\\b[a-z]+(?:[A-Z][a-z0-9]+)+\\b"),                                // camelCase identifiers
            Regex("\\b[a-z]+(?:_[a-z0-9]+)+\\b"),                                    // snake_case identifiers
            Regex("\\b\\w+(?:\\.\\w+){2,}\\b")                                       // qualified names
        )

        for (pattern in technicalPatterns) {
            for (match in pattern.findAll(text)) {
                // Only the first two patterns define capture group 1; reading
                // groups[1] on the others throws IndexOutOfBoundsException
                // ("No group 1") and crashed note-open on any matching token
                // (e.g. a multi-dot hostname in a pasted URL).
                val raw = (match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                    ?: match.value).trim().trim('`')
                if (raw.length in MIN_TOKEN_LENGTH..50 && !isStopPhrase(raw)) {
                    val snippet = extractContext(text, match.range.first, 60)
                    technicals.add(ExtractedConcept(
                        canonicalForm = raw.lowercase(),
                        displayForm = raw,
                        type = ConceptType.TECHNICAL,
                        sourceNote = fileName,
                        context = snippet,
                        importance = 0.45f
                    ))
                }
            }
        }

        return technicals.distinctBy { it.canonicalForm }.take(3)
    }

    // ── Helpers ────────────────────────────────────────────────

    private fun extractContext(text: String, index: Int, window: Int): String {
        val start = (index - window).coerceAtLeast(0)
        val end = (index + window).coerceAtMost(text.length)
        val snippet = text.substring(start, end).trim()
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < text.length) "..." else ""
        return "$prefix$snippet$suffix"
    }

    private fun isStopPhrase(phrase: String): Boolean {
        // Case-insensitive: sentence-start capitalization ("This migration",
        // "And then") must not smuggle stopwords past the check, and whole
        // single-word pronouns/determiners ("This", "That", "It") are never
        // entities.
        val lower = phrase.lowercase().trim()
        if (lower in STOP_PHRASES) return true
        return STOP_STARTS.any { lower.startsWith(it) }
    }
}