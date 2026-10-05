package com.noteflowai.app.data.search

import com.noteflowai.app.data.chat.RagSource

/**
 * Phase 2 injection defense: neutralize prompt-control sequences in retrieved
 * text before it enters an LLM prompt. ChatML role markers (`<|im_start|>` /
 * `<|im_end|>`) are control plane — a note containing them could forge system
 * instructions — so the `<|` / `|>` bigrams are broken while keeping the text
 * human-readable. Applied at every retrieved-text → prompt boundary
 * (assembler, grounding builder, full-note / window injection, web results).
 */
internal fun sanitizeRetrievedText(text: String): String =
    text.replace("<|", "(|").replace("|>", "|)")

/**
 * Assembles retrieval results into a prompt fragment for the LLM.
 * Generates intent-aware context with smart snippets.
 */
class PromptAssembler {

    companion object {
        private const val MAX_CONTEXT_TOKENS_CHARS = 32000 // ~8000 tokens (expanded from 8000 chars)
        private const val EXCERPT_MAX_CHARS = 4000          // ~1000 tokens per excerpt (expanded from 1500 chars)
    }

    private data class AssembledCandidate(
        val result: RetrievalResult,
        val title: String,
        val snippetText: String,
        val reasons: List<String>
    )

    /**
     * Orders items according to the U-curve (Perimeter Ordering / "Lost in the Middle" mitigation):
     * [Rank 1, Rank 3, ..., Rank 4, Rank 2]
     * Places the highest scoring items at the beginning (primacy effect) and end (recency effect)
     * of the context window where transformer attention is strongest.
     */
    fun <T> orderUCurve(items: List<T>): List<T> {
        if (items.size <= 2) return items
        val ordered = ArrayList<T>(items.size)
        for (i in items.indices step 2) {
            ordered.add(items[i])
        }
        val oddIndices = (1 until items.size step 2).toList().asReversed()
        for (i in oddIndices) {
            ordered.add(items[i])
        }
        return ordered
    }

    /**
     * Assemble retrieval results into a prompt fragment and RagSource list.
     */
    fun assemble(
        results: List<RetrievalResult>,
        plan: QueryPlan,
        smartSnippetExtractor: SmartSnippetExtractor,
        tokenize: (String) -> List<String>,
        maxContextChars: Int = MAX_CONTEXT_TOKENS_CHARS,
        applyUCurveOrdering: Boolean = true
    ): Pair<String, List<RagSource>> {
        if (results.isEmpty()) return Pair("", emptyList())

        val sb = StringBuilder()
        val ragSources = mutableListOf<RagSource>()
        var tokenBudget = maxContextChars

        // Add intent-specific preamble
        sb.appendLine(getIntentPreamble(plan))
        tokenBudget -= sb.length

        // Hoisted: identical tokenization was recomputed per result.
        val queryTokens = tokenize(plan.queryText)

        // Select candidates that fit within token budget in relevance rank order
        val candidates = mutableListOf<AssembledCandidate>()
        val perExcerptBudget = if (results.isNotEmpty()) {
            (tokenBudget / results.size).coerceIn(600, EXCERPT_MAX_CHARS)
        } else {
            EXCERPT_MAX_CHARS
        }

        for (result in results) {
            if (result.text.isBlank()) continue
            val text = result.text

            // Extract smart snippet with expanded ceiling and dynamic centroid windowing
            // for unsegmented notes exceeding 8000 characters to prevent query term cutoff.
            val candidateText = if (text.length > 8000 && queryTokens.isNotEmpty()) {
                locateCentroidWindow(text, queryTokens, windowSize = 8000)
            } else {
                text.take(8000)
            }
            val snippet = if (queryTokens.isNotEmpty()) {
                smartSnippetExtractor.extract(candidateText, queryTokens, maxSnippetLength = perExcerptBudget)
            } else {
                SmartSnippetExtractor.SmartSnippet(
                    text = text.take(perExcerptBudget),
                    score = result.score,
                    sentenceIndex = 0,
                    isPartial = text.length > perExcerptBudget
                )
            }

            // Check token budget: if snippet exceeds current budget, truncate if we can fit at least 250 chars
            val snippetText = if (snippet.text.length + 100 > tokenBudget) {
                if (tokenBudget >= 250) {
                    snippet.text.take(tokenBudget - 100).trimEnd() + "..."
                } else {
                    null
                }
            } else {
                snippet.text
            }

            if (snippetText == null) break
            tokenBudget -= (snippetText.length + 100)

            val title = buildTitle(result)
            val reasons = buildRetrievalReasons(result, plan)
            candidates.add(AssembledCandidate(result, title, snippetText, reasons))
        }

        // Apply U-curve ordering to selected candidates to eliminate "Lost in the Middle" attention degradation
        val orderedCandidates = if (applyUCurveOrdering) {
            orderUCurve(candidates)
        } else {
            candidates
        }

        for ((index, item) in orderedCandidates.withIndex()) {
            sb.appendLine("[${index + 1}] \"${sanitizeRetrievedText(item.title)}\": ${sanitizeRetrievedText(item.snippetText)}")
            sb.appendLine()

            ragSources.add(
                RagSource(
                    noteFileName = item.result.sourceId,
                    noteTitle = item.title,
                    relevanceScore = item.result.score,
                    excerpt = item.snippetText.take(500),
                    source = item.result.metadata?.get("type") ?: "memory",
                    retrievalReasons = item.reasons,
                    concepts = emptyList()
                )
            )
        }

        // Add intent-specific instructions
        sb.appendLine(getIntentInstructions(plan))

        return Pair(sb.toString(), ragSources)
    }

    private fun getIntentPreamble(plan: QueryPlan): String {
        return when (plan.intent) {
            QueryIntent.DECISION_LOOKUP -> {
                "You have access to the user's recorded decisions and related notes. " +
                "Use them as your PRIMARY source for answering decision-related questions.\n\n" +
                "DECISIONS AND RELEVANT CONTEXT:"
            }
            QueryIntent.COMMITMENT_LOOKUP -> {
                "You have access to the user's commitments, tasks, and action items. " +
                "Use them as your PRIMARY source for answering commitment-related questions.\n\n" +
                "COMMITMENTS AND RELEVANT CONTEXT:"
            }
            QueryIntent.ENTITY_LOOKUP -> {
                "You have access to information about people, projects, and entities mentioned in the user's notes. " +
                "Use them as your PRIMARY source.\n\n" +
                "ENTITY INFORMATION:"
            }
            QueryIntent.PROJECT_LOOKUP -> {
                "You have access to the user's project-related notes, decisions, and commitments. " +
                "Use them as your PRIMARY source.\n\n" +
                "PROJECT INFORMATION:"
            }
            QueryIntent.CHANGE_ANALYSIS -> {
                "You have access to the user's chronological history of decisions and notes. " +
                "Use them to trace how the user's thinking has evolved over time.\n\n" +
                "CHRONOLOGICAL EVIDENCE:"
            }
            QueryIntent.CONFLICT_ANALYSIS -> {
                "You have access to the user's notes that may contain conflicting viewpoints. " +
                "Identify and present contradictions conservatively.\n\n" +
                "POTENTIAL CONFLICTS:"
            }
            QueryIntent.SOURCE_SUMMARY -> {
                "You have access to the user's content from various sources. " +
                "Provide a comprehensive summary.\n\n" +
                "SOURCE CONTENT:"
            }
            else -> {
                "You have access to the user's personal notes. " +
                "Use them as your PRIMARY source for answering questions.\n\n" +
                "RELEVANT NOTES:"
            }
        }
    }

    private fun getIntentInstructions(plan: QueryPlan): String {
        return when (plan.intent) {
            QueryIntent.DECISION_LOOKUP -> {
                "\nWhen answering about decisions:\n" +
                "- Always cite the specific decision and its source\n" +
                "- Include the reason if provided\n" +
                "- Note the date if available\n" +
                "- If the decision has been reversed or superseded, mention that"
            }
            QueryIntent.COMMITMENT_LOOKUP -> {
                "\nWhen answering about commitments:\n" +
                "- Include the action, owner, and due date if available\n" +
                "- Highlight overdue items\n" +
                "- Note the current status (active, completed, cancelled)"
            }
            QueryIntent.CHANGE_ANALYSIS -> {
                "\nWhen analyzing changes over time:\n" +
                "- Present evidence in chronological order\n" +
                "- Highlight shifts in thinking or decisions\n" +
                "- Note when decisions were made, reversed, or superseded"
            }
            QueryIntent.CONFLICT_ANALYSIS -> {
                "\nWhen identifying conflicts:\n" +
                "- Present both sides of the conflict\n" +
                "- Cite the specific notes or decisions involved\n" +
                "- Be conservative - only flag clear contradictions"
            }
            else -> ""
        }
    }

    private fun buildTitle(result: RetrievalResult): String {
        val type = result.metadata?.get("type") ?: "note"
        val created = result.metadata?.get("createdDate")
        val modified = result.metadata?.get("modifiedDate")
        val temporalSuffix = when {
            !created.isNullOrBlank() && !modified.isNullOrBlank() -> " (Created: $created, Modified: $modified)"
            !modified.isNullOrBlank() -> " (Modified: $modified)"
            !created.isNullOrBlank() -> " (Created: $created)"
            else -> ""
        }
        return when (type) {
            "decision" -> "Decision: ${result.text.take(50)}$temporalSuffix"
            "commitment" -> "Commitment: ${result.text.take(50)}$temporalSuffix"
            "entity_mention" -> "Entity: ${result.metadata?.get("entityName") ?: "Unknown"}$temporalSuffix"
            "timeline_decision" -> "Decision (${result.metadata?.get("decidedAt") ?: ""}): ${result.text.take(50)}"
            "note" -> (result.metadata?.get("title") ?: result.metadata?.get("fileName") ?: "Note") + temporalSuffix
            else -> result.sourceId + temporalSuffix
        }
    }

    private fun buildRetrievalReasons(
        result: RetrievalResult,
        plan: QueryPlan
    ): List<String> {
        val reasons = mutableListOf<String>()

        when (plan.intent) {
            QueryIntent.DECISION_LOOKUP -> {
                if (result.metadata?.get("type") == "decision") {
                    reasons.add("direct_decision_match")
                }
            }
            QueryIntent.COMMITMENT_LOOKUP -> {
                if (result.metadata?.get("type") == "commitment") {
                    reasons.add("direct_commitment_match")
                }
                if (result.metadata?.get("isOverdue") == "true") {
                    reasons.add("overdue_item")
                }
            }
            QueryIntent.ENTITY_LOOKUP -> {
                if (result.metadata?.get("type") == "entity_mention") {
                    reasons.add("entity_mention")
                }
            }
            else -> {
                reasons.add("content_match")
            }
        }

        // Scores are fused into [0,1] upstream, so the old >1.0 gate never
        // fired. 0.8+ marks genuinely strong evidence for telemetry.
        if (result.score >= 0.8f) {
            reasons.add("high_relevance")
        }

        return reasons
    }

    /**
     * Locates a dynamic text window of size [windowSize] centered around the densest
     * cluster (median index) of query term matches across large unsegmented note bodies.
     * Prevents query term truncation when query matches occur deeper than prefix offsets.
     */
    private fun locateCentroidWindow(
        text: String,
        queryTokens: List<String>,
        windowSize: Int = 8000
    ): String {
        if (text.length <= windowSize) return text
        val lowerText = text.lowercase()
        // Find indices of all query token occurrences (filtering out noise/stopwords < 3 chars)
        val matchIndices = queryTokens
            .filter { it.length > 2 }
            .flatMap { token ->
                val indices = mutableListOf<Int>()
                var startIndex = 0
                val lowerToken = token.lowercase()
                while (startIndex < lowerText.length) {
                    val idx = lowerText.indexOf(lowerToken, startIndex)
                    if (idx == -1) break
                    indices.add(idx)
                    startIndex = idx + lowerToken.length
                }
                indices
            }
            .sorted()

        if (matchIndices.isEmpty()) {
            return text.take(windowSize)
        }

        // Use the median match index as the centroid to anchor the most representative match cluster
        val centroid = matchIndices[matchIndices.size / 2]
        val halfWindow = windowSize / 2
        val start = (centroid - halfWindow).coerceAtLeast(0)
        val end = (start + windowSize).coerceAtMost(text.length)
        val adjustedStart = if (end == text.length) (text.length - windowSize).coerceAtLeast(0) else start
        return text.substring(adjustedStart, end)
    }
}
