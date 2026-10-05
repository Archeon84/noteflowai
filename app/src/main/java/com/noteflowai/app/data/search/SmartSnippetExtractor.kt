package com.noteflowai.app.data.search

/**
 * Extracts the most relevant sentence/paragraph from a note.
 *
 * Instead of fixed-window excerpts, finds the passage that best
 * matches the query using sentence-level scoring.
 */
open class SmartSnippetExtractor {

    data class SmartSnippet(
        val text: String,
        val score: Float,          // relevance of this snippet
        val sentenceIndex: Int,    // position in the note
        val isPartial: Boolean     // true if truncated
    )

    companion object {
        const val DEFAULT_MAX_SNIPPET_LENGTH = 1000
        const val LIST_MAX_SNIPPET_LENGTH = 1500
        private const val CONTEXT_SENTENCES = 1  // sentences before/after the best match

        private val LIST_ITEM_REGEX = Regex("""^(\s*[-*•]|\s*\d+[.)])\s+.*""")
        private val LIST_OR_COMPREHENSIVE_QUERY = Regex(
            """\b(list|all|every|dates|items|tasks|steps|points|summarize|full|schedule|agenda|events)\b""",
            RegexOption.IGNORE_CASE
        )

        // Tokenizer regexes hoisted so they are compiled once, not per call.
        private val SENTENCE_SPLIT = Regex("[.!?]+\\s+")
        private val NON_ALNUM = Regex("[^a-z0-9\\s]")
        private val WHITESPACE = Regex("\\s+")

        /** Common synonym groups for better snippet matching. */
        private val SYNONYM_GROUPS = listOf(
            setOf("ai", "artificial", "intelligence", "ml", "machine", "learning"),
            setOf("app", "application", "software", "program"),
            setOf("api", "interface", "endpoint", "rest"),
            setOf("data", "information", "dataset", "records"),
            setOf("code", "programming", "development", "coding", "script"),
            setOf("design", "ui", "ux", "interface", "layout"),
            setOf("database", "db", "storage", "repository"),
            setOf("test", "testing", "qa", "quality"),
            setOf("deploy", "deployment", "release", "ship"),
            setOf("bug", "error", "issue", "defect", "fix"),
            setOf("feature", "functionality", "capability"),
            setOf("note", "document", "file", "page"),
            setOf("search", "find", "query", "lookup"),
            setOf("chat", "message", "conversation", "talk"),
            setOf("image", "photo", "picture", "visual"),
            setOf("audio", "sound", "music", "recording"),
            setOf("video", "film", "movie", "clip"),
            setOf("pdf", "document", "file"),
            setOf("android", "mobile", "phone", "device"),
            setOf("kotlin", "java", "jvm"),
            setOf("python", "script", "automation"),
            setOf("web", "internet", "online", "browser"),
            setOf("security", "auth", "authentication", "permission"),
            setOf("performance", "speed", "optimize", "fast"),
            setOf("settings", "config", "configuration", "preferences"),
            setOf("model", "llm", "gpt", "transformer", "neural")
        )

        /** Build a lookup map: token -> set of synonyms including itself. */
        private val synonymIndex: Map<String, Set<String>> by lazy {
            val map = mutableMapOf<String, MutableSet<String>>()
            for (group in SYNONYM_GROUPS) {
                for (token in group) {
                    map.getOrPut(token) { mutableSetOf() }.addAll(group)
                }
            }
            map
        }

        /** Expand a token to include its synonyms. */
        fun expandSynonyms(token: String): Set<String> {
            return synonymIndex[token.lowercase(java.util.Locale.ROOT)] ?: setOf(token.lowercase(java.util.Locale.ROOT))
        }
    }

    /**
     * Extract the most relevant snippet from note content.
     *
     * @param text Full note content
     * @param queryTokens Tokenized search query
     * @param maxSnippetLength Maximum length for the snippet (defaults to 1000, 1500 for list queries)
     * @return Best matching snippet
     */
    open fun extract(
        text: String,
        queryTokens: List<String>,
        maxSnippetLength: Int = DEFAULT_MAX_SNIPPET_LENGTH
    ): SmartSnippet {
        val isListQuery = queryTokens.any { LIST_OR_COMPREHENSIVE_QUERY.containsMatchIn(it) }
        val effectiveMaxLen = if (isListQuery && maxSnippetLength < LIST_MAX_SNIPPET_LENGTH) {
            LIST_MAX_SNIPPET_LENGTH
        } else {
            maxSnippetLength
        }

        if (text.isBlank() || queryTokens.isEmpty()) {
            return SmartSnippet(
                text = text.take(effectiveMaxLen).trim(),
                score = 0f,
                sentenceIndex = 0,
                isPartial = text.length > effectiveMaxLen
            )
        }

        // Synonym sets expanded once per extract — the old code re-expanded
        // per line AND per sentence inside the scoring loop.
        val expandedSynonyms = queryTokens.associateWith { expandSynonyms(it) }

        // 1. Line-level scan to detect and extract full contiguous list blocks if match is in a list
        val lines = text.lines()
        val scoredLines = lines.mapIndexed { index, line ->
            val tokens = tokenizeSimple(line)
            val score = computeRelevance(tokens, queryTokens, expandedSynonyms)
            Triple(index, line, score)
        }
        val bestLine = scoredLines.maxByOrNull { it.third }

        if (bestLine != null && bestLine.third > 0f && isListItem(bestLine.second)) {
            val startLine = findListStart(lines, bestLine.first)
            val endLine = findListEnd(lines, bestLine.first)
            val listBlock = lines.subList(startLine, endLine + 1).joinToString("\n").trim()

            val truncated = if (listBlock.length > effectiveMaxLen) {
                listBlock.take(effectiveMaxLen).trim() + "..."
            } else {
                listBlock
            }

            return SmartSnippet(
                text = truncated,
                score = bestLine.third,
                sentenceIndex = bestLine.first,
                isPartial = listBlock.length > effectiveMaxLen
            )
        }

        // 2. Sentence-level extraction with context sentences
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) {
            val truncated = if (text.length > effectiveMaxLen) {
                text.take(effectiveMaxLen).trim() + "..."
            } else {
                text.trim()
            }
            return SmartSnippet(text = truncated, score = 0f, sentenceIndex = 0, isPartial = text.length > effectiveMaxLen)
        }

        val scored = sentences.mapIndexed { index, sentence ->
            val sentenceTokens = tokenizeSimple(sentence)
            val score = computeRelevance(sentenceTokens, queryTokens, expandedSynonyms)
            Triple(index, sentence, score)
        }

        val best = scored.maxByOrNull { it.third } ?: scored.first()

        val contextCount = if (isListQuery) 3 else CONTEXT_SENTENCES
        val startIdx = (best.first - contextCount).coerceAtLeast(0)
        val endIdx = (best.first + contextCount).coerceAtMost(sentences.lastIndex)

        val snippet = (startIdx..endIdx).joinToString(" ") { sentences[it].trim() }

        val truncated = if (snippet.length > effectiveMaxLen) {
            snippet.take(effectiveMaxLen).trim() + "..."
        } else {
            snippet
        }

        return SmartSnippet(
            text = truncated,
            score = best.third,
            sentenceIndex = best.first,
            isPartial = snippet.length > effectiveMaxLen
        )
    }

    private fun isListItem(line: String): Boolean {
        val trimmed = line.trim()
        return LIST_ITEM_REGEX.matches(trimmed)
    }

    private fun findListStart(lines: List<String>, matchIndex: Int): Int {
        var idx = matchIndex
        while (idx > 0 && isListItem(lines[idx - 1])) {
            idx--
        }
        // Include preceding intro line if it is a section header or label (e.g. "Items:", "## Dates")
        if (idx > 0) {
            val prev = lines[idx - 1].trim()
            if (prev.isNotBlank() && (prev.endsWith(":") || prev.startsWith("#"))) {
                idx--
            }
        }
        return idx
    }

    private fun findListEnd(lines: List<String>, matchIndex: Int): Int {
        var idx = matchIndex
        while (idx < lines.lastIndex && isListItem(lines[idx + 1])) {
            idx++
        }
        return idx
    }

    private fun computeRelevance(
        sentenceTokens: List<String>,
        queryTokens: List<String>,
        expandedSynonyms: Map<String, Set<String>>
    ): Float {
        if (sentenceTokens.isEmpty() || queryTokens.isEmpty()) return 0f

        var matchScore = 0f
        for (qt in queryTokens) {
            // Token-boundary match: the old bidirectional substring let "ai"
            // match "air"/"said"/"pair" and "art" match "part", inflating
            // every snippet score. Exact plus plural-s only.
            val directMatch = sentenceTokens.any { tokenMatches(it, qt) }
            if (directMatch) {
                matchScore += 1f
                continue
            }
            // Synonym-expanded match
            val synonyms = expandedSynonyms[qt].orEmpty()
            if (sentenceTokens.any { st -> synonyms.any { syn -> tokenMatches(st, syn) } }) {
                matchScore += 0.7f  // partial credit for synonym match
            }
        }
        return (matchScore / queryTokens.size).coerceAtMost(1f)
    }

    private fun splitSentences(text: String): List<String> {
        return text.split(SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.length > 10 }  // skip very short fragments
    }

    /** Exact match or plural-s variant — never substring. */
    private fun tokenMatches(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length > b.length) return a == b + "s"
        if (b.length > a.length) return b == a + "s"
        return false
    }

    private fun tokenizeSimple(text: String): List<String> {
        return text.lowercase(java.util.Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .split(WHITESPACE)
            .filter { it.length >= 2 }
    }
}
