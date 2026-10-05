package com.noteflowai.app.data.search

/**
 * Builds search queries from multi-turn conversation context.
 *
 * Instead of only using the last user message as the search query,
 * this extracts topic keywords from the recent conversation to provide
 * richer context for note retrieval.
 *
 * Handles:
 * - Pronoun resolution: "What about it?" → uses subject from prior message
 * - Topic continuity: carries forward keywords from recent turns
 * - Query expansion: adds co-occurring terms from the user's notes
 */
class ConversationQueryBuilder(
    private val searchIndex: NoteSearchIndex
) {

    companion object {
        /** Number of recent messages to consider for context. */
        private const val CONTEXT_WINDOW = 5

        /** Max tokens in the final expanded query. */
        private const val MAX_QUERY_TOKENS = 20

        /** Pronouns and demonstratives that trigger subject resolution. */
        private val PRONOUNS = setOf(
            "it", "that", "this", "them", "they", "those", "these",
            "he", "she", "his", "her", "its", "their", "him"
        )

        /** Words indicating the user wants to continue or expand on something. */
        private val CONTINUATION_MARKERS = setOf(
            "more", "continue", "elaborate", "explain", "tell me",
            "what about", "how about", "and", "also", "then"
        )

        /** Temporal reference patterns. */
        private val TEMPORAL_PATTERNS = mapOf(
            "yesterday" to 1L,
            "last week" to 7L,
            "last month" to 30L,
            "earlier" to 3L,
            "before" to 3L,
            "previously" to 5L,
            "recently" to 2L
        )
    }

    /**
     * A chat message for query building purposes.
     */
    data class ChatTurn(
        val role: String,   // "user" or "assistant"
        val content: String
    )

    /**
     * Build an enriched search query from conversation context.
     *
     * @param messages Full conversation history (most recent last)
     * @return Expanded query string suitable for search
     */
    fun buildQuery(messages: List<ChatTurn>): String {
        if (messages.isEmpty()) return ""

        val recentMessages = messages.takeLast(CONTEXT_WINDOW)
        val lastUserMsg = recentMessages.lastOrNull { it.role == "user" }?.content ?: ""

        if (lastUserMsg.isBlank()) return ""

        // Check for pronouns in the last message — resolve from prior context
        val resolvedQuery = resolvePronouns(lastUserMsg, recentMessages)

        // Extract topic keywords from the conversation window
        val contextKeywords = extractContextKeywords(recentMessages)

        // Merge: primary = current question, secondary = context keywords.
        // A pure continuation ("tell me more") tokenizes to nothing — fall
        // back to the conversation topic instead of returning "" (which made
        // follow-ups silently retrieve nothing).
        val primaryTokens = searchIndex.tokenize(resolvedQuery)
        if (primaryTokens.isEmpty()) {
            return contextKeywords.take(MAX_QUERY_TOKENS).joinToString(" ")
        }
        val contextTokens = contextKeywords.filter { it !in primaryTokens }

        val combined = (primaryTokens + contextTokens).distinct().take(MAX_QUERY_TOKENS)
        return combined.joinToString(" ")
    }

    /**
     * Build a query specifically for the embedding model.
     * Embeddings work better with natural language, so we produce a sentence-like query.
     */
    fun buildEmbeddingQuery(messages: List<ChatTurn>): String {
        if (messages.isEmpty()) return ""

        val recentMessages = messages.takeLast(CONTEXT_WINDOW)
        val lastUserMsg = recentMessages.lastOrNull { it.role == "user" }?.content ?: ""

        if (lastUserMsg.isBlank()) return ""

        val resolvedQuery = resolvePronouns(lastUserMsg, recentMessages)
        val contextKeywords = extractContextKeywords(recentMessages)

        // For embeddings, produce a natural language query. A token-empty
        // continuation carries no signal — use the topic phrase alone.
        val contextPhrase = contextKeywords.take(5).joinToString(" ")
        if (searchIndex.tokenize(resolvedQuery).isEmpty()) {
            return contextPhrase
        }
        return if (contextPhrase.isNotBlank()) {
            "$resolvedQuery $contextPhrase"
        } else {
            resolvedQuery
        }
    }

    // ── Pronoun and reference resolution ──────────────────────

    /**
     * Heuristic pronoun and reference resolution.
     *
     * Handles:
     * - Standard pronouns (it, that, this, them, they, etc.)
     * - Continuation markers ("tell me more about...", "what about...")
     * - Temporal references ("yesterday's approach" → expands time context)
     * - Named entity tracking from prior assistant responses
     */
    private fun resolvePronouns(lastMessage: String, messages: List<ChatTurn>): String {
        val tokens = searchIndex.tokenize(lastMessage)
        val lowerMessage = lastMessage.lowercase(java.util.Locale.ROOT)
        val hasPronoun = tokens.any { it in PRONOUNS }
        val hasContinuation = CONTINUATION_MARKERS.any { lowerMessage.contains(it) }
        val hasTemporal = TEMPORAL_PATTERNS.keys.any { lowerMessage.contains(it) }

        // Find the previous assistant message for context extraction.
        // Index-based (not value equality): duplicate assistant texts are
        // equal as data classes, and the old predicate skipped ALL of them
        // instead of just the trailing message.
        val lastIndex = messages.lastIndex
        val previousAssistant = messages
            .filterIndexed { index, turn -> turn.role == "assistant" && index != lastIndex }
            .lastOrNull()

        val resolutions = mutableListOf<String>()

        // Resolve pronouns from prior assistant subject
        if (hasPronoun && previousAssistant != null) {
            val subject = extractSubject(previousAssistant.content)
            if (subject.isNotBlank()) {
                resolutions.add(subject)
            }
        }

        // For continuation markers, extract the main topic from recent conversation
        if (hasContinuation && previousAssistant != null) {
            val topic = extractTopicFromConversation(messages)
            if (topic.isNotBlank() && topic !in resolutions) {
                resolutions.add(topic)
            }
        }

        // For temporal references, add time-context keywords
        if (hasTemporal) {
            for ((pattern, days) in TEMPORAL_PATTERNS) {
                if (lastMessage.lowercase(java.util.Locale.ROOT).contains(pattern)) {
                    // Add "recent" as a search modifier for temporal context
                    resolutions.add("recent")
                    break
                }
            }
        }

        // Named entity tracking: look for capitalized terms in recent user messages
        val namedEntities = extractNamedEntities(messages)
        val messageEntities = namedEntities.filter { entity ->
            lastMessage.lowercase(java.util.Locale.ROOT).contains(entity.lowercase(java.util.Locale.ROOT)) ||
            CONTINUATION_MARKERS.any { lastMessage.lowercase(java.util.Locale.ROOT).contains(it) }
        }
        for (entity in messageEntities.take(2)) {
            if (entity !in resolutions) {
                resolutions.add(entity)
            }
        }

        return if (resolutions.isNotEmpty()) {
            "${resolutions.joinToString(" ")} $lastMessage"
        } else {
            lastMessage
        }
    }

    /**
     * Extract the main subject/topic from assistant text using TF-IDF-like scoring.
     * Scores tokens by frequency in the text minus frequency in common English (stopwords).
     */
    private fun extractSubject(text: String): String {
        val tokens = searchIndex.tokenize(text)
        if (tokens.isEmpty()) return ""

        // Score by frequency (more frequent = more likely to be the topic)
        val termFreq = mutableMapOf<String, Int>()
        for (token in tokens) {
            termFreq[token] = (termFreq[token] ?: 0) + 1
        }

        // Also prefer longer tokens (more specific = more likely to be a named entity)
        return termFreq.entries
            .sortedByDescending { it.value * (it.key.length.coerceAtMost(8)) }
            .take(4)
            .map { it.key }
            .joinToString(" ")
    }

    /**
     * Extract the dominant topic from the recent conversation.
     * Looks for the most frequently mentioned non-stopword across recent turns.
     */
    private fun extractTopicFromConversation(messages: List<ChatTurn>): String {
        val termCounts = mutableMapOf<String, Int>()
        val stopWords = setOf(
            "would", "could", "should", "might", "like", "just", "also",
            "about", "there", "their", "what", "when", "where", "which",
            "have", "been", "from", "with", "your", "some", "more", "than",
            "can", "the", "and", "for", "are", "but", "not", "you", "all",
            "has", "was", "one", "our", "out", "this", "that", "these"
        )

        // Count across all recent messages, weighting user messages higher
        for (msg in messages.takeLast(CONTEXT_WINDOW)) {
            val tokens = searchIndex.tokenize(msg.content)
            val weight = if (msg.role == "user") 2 else 1
            for (token in tokens) {
                if (token !in stopWords && token.length >= 3) {
                    termCounts[token] = (termCounts[token] ?: 0) + weight
                }
            }
        }

        return termCounts.entries
            .filter { it.value >= 3 }  // must appear multiple times
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
            .joinToString(" ")
    }

    /**
     * Extract named entities (capitalized multi-word terms) from conversation.
     */
    private fun extractNamedEntities(messages: List<ChatTurn>): List<String> {
        val entities = mutableListOf<String>()

        for (msg in messages.takeLast(CONTEXT_WINDOW)) {
            // Look for capitalized words that might be proper nouns / named entities
            val words = msg.content.split("\\s+".toRegex())
            var currentEntity = mutableListOf<String>()

            for (word in words) {
                val cleaned = word.replace(Regex("[^a-zA-Z0-9]"), "")
                if (cleaned.isNotEmpty() && cleaned[0].isUpperCase() && cleaned.length >= 2) {
                    currentEntity.add(cleaned)
                } else {
                    if (currentEntity.size >= 1) {
                        val entity = currentEntity.joinToString(" ")
                        if (entity.length >= 3 && entity !in entities) {
                            entities.add(entity)
                        }
                    }
                    currentEntity = mutableListOf()
                }
            }
            if (currentEntity.size >= 1) {
                val entity = currentEntity.joinToString(" ")
                if (entity.length >= 3 && entity !in entities) {
                    entities.add(entity)
                }
            }
        }

        return entities
    }

    // ── Context keyword extraction ────────────────────────────

    /**
     * Extract topic-relevant keywords from the conversation window,
     * weighted by recency and frequency.
     */
    private fun extractContextKeywords(messages: List<ChatTurn>): List<String> {
        val termCounts = mutableMapOf<String, Int>()
        val userMessages = messages.filter { it.role == "user" }

        for ((index, msg) in userMessages.withIndex()) {
            val tokens = searchIndex.tokenize(msg.content)
            // Recency weight: more recent messages get higher weight
            val weight = index + 1
            for (token in tokens) {
                termCounts[token] = (termCounts[token] ?: 0) + weight
            }
        }

        // Also extract from assistant responses (they often contain keywords too)
        val assistantMessages = messages.filter { it.role == "assistant" }
        for ((index, msg) in assistantMessages.withIndex()) {
            val tokens = searchIndex.tokenize(msg.content)
            val weight = index + 1  // lower weight than user messages
            for (token in tokens) {
                termCounts[token] = (termCounts[token] ?: 0) + weight
            }
        }

        // Remove very common terms that are likely noise in context
        val stopContext = setOf("would", "could", "should", "might", "like", "just",
            "also", "about", "there", "their", "what", "when", "where", "which",
            "have", "been", "from", "with", "your", "some", "more", "than", "into")

        return termCounts.entries
            .filter { it.key !in stopContext && it.value >= 2 }
            .sortedByDescending { it.value }
            .take(MAX_QUERY_TOKENS)
            .map { it.key }
    }
}
