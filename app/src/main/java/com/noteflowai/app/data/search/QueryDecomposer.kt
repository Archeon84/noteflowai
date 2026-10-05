package com.noteflowai.app.data.search

/**
 * Breaks complex questions into sub-queries for better retrieval.
 *
 * Handles:
 * - Comparison queries: "X vs Y" -> search for X, search for Y, compare
 * - List queries: "What are my notes about X, Y, and Z?" -> search each
 * - Temporal queries: "notes from last week about X" -> filter by time + search
 * - Multi-aspect queries: "X and how it relates to Y" -> search X, search Y, find intersection
 */
class QueryDecomposer {

    data class SubQuery(
        val text: String,
        val purpose: String,    // "primary", "comparison", "context", "temporal"
        val weight: Float       // relative importance [0,1]
    )

    data class DecomposedQuery(
        val original: String,
        val subQueries: List<SubQuery>,
        val isComplex: Boolean,
        val decompositionStrategy: String
    )

    /**
     * Analyze and decompose a query into sub-queries.
     */
    fun decompose(query: String): DecomposedQuery {
        val trimmed = query.trim()

        // Check for comparison patterns
        val comparisonResult = decomposeComparison(trimmed)
        if (comparisonResult != null) return comparisonResult

        // Check for list patterns
        val listResult = decomposeList(trimmed)
        if (listResult != null) return listResult

        // Check for temporal modifiers
        val temporalResult = decomposeTemporal(trimmed)
        if (temporalResult != null) return temporalResult

        // Check for multi-aspect queries (contains "and", "how", "relate")
        val multiAspectResult = decomposeMultiAspect(trimmed)
        if (multiAspectResult != null) return multiAspectResult

        // Simple query -- no decomposition needed
        return DecomposedQuery(
            original = trimmed,
            subQueries = listOf(SubQuery(trimmed, "primary", 1.0f)),
            isComplex = false,
            decompositionStrategy = "none"
        )
    }

    private fun decomposeComparison(query: String): DecomposedQuery? {
        val patterns = listOf(
            Regex("(.+?)\\s+vs\\.?\\s+(.+?)$", RegexOption.IGNORE_CASE),
            Regex("(.+?)\\s+versus\\s+(.+?)$", RegexOption.IGNORE_CASE),
            Regex("compare\\s+(.+?)\\s+(?:and|with|to)\\s+(.+?)$", RegexOption.IGNORE_CASE),
            Regex("(.+?)\\s+(?:compared? to|relative to)\\s+(.+?)$", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(query) ?: continue
            val (left, right) = match.destructured
            return DecomposedQuery(
                original = query,
                subQueries = listOf(
                    SubQuery(left.trim(), "comparison", 0.5f),
                    SubQuery(right.trim(), "comparison", 0.5f)
                ),
                isComplex = true,
                decompositionStrategy = "comparison"
            )
        }
        return null
    }

    private fun decomposeList(query: String): DecomposedQuery? {
        // "notes about X, Y, and Z" or "X, Y, Z" — any arity. The old
        // three-group regex silently dropped the 4th item ("X, Y, Z, W"
        // lost W).
        val core = Regex("(?:notes?\\s+(?:about|on|regarding)\\s+)?(.+)$", RegexOption.IGNORE_CASE)
            .find(query)?.groupValues?.get(1)?.trim() ?: return null
        if (!core.contains(",")) return null
        val items = core.split(",")
            .map { it.replace(Regex("^(and|or)\\s+", RegexOption.IGNORE_CASE), "").trim() }
            .filter { it.isNotBlank() }

        if (items.size < 2) return null

        return DecomposedQuery(
            original = query,
            subQueries = items.map { SubQuery(it, "primary", 1.0f / items.size) },
            isComplex = true,
            decompositionStrategy = "list"
        )
    }

    private fun decomposeTemporal(query: String): DecomposedQuery? {
        val temporalPattern = Regex("(?:notes?\\s+)?(?:from|before|after|during|in)\\s+(last|this|next|past)\\s+(week|month|quarter|year|day|days|weeks|months)\\s*(?:about|on|regarding)?\\s*(.*)", RegexOption.IGNORE_CASE)
        val match = temporalPattern.find(query) ?: return null

        val timeRef = "${match.groupValues[1]} ${match.groupValues[2]}"
        val topic = match.groupValues[3].trim()

        val subQueries = mutableListOf(SubQuery(timeRef, "temporal", 0.3f))
        if (topic.isNotBlank()) {
            subQueries.add(SubQuery(topic, "primary", 0.7f))
        }

        return DecomposedQuery(
            original = query,
            subQueries = subQueries,
            isComplex = true,
            decompositionStrategy = "temporal"
        )
    }

    private fun decomposeMultiAspect(query: String): DecomposedQuery? {
        val multiPattern = Regex("(.+?)\\s+(?:and|how|relating? to|connected? to|related? to)\\s+(.+?)$", RegexOption.IGNORE_CASE)
        val match = multiPattern.find(query) ?: return null

        val (left, right) = match.destructured
        if (left.length < 3 || right.length < 3) return null
        // Bare keyword lists ("decisions and commitments", "tasks and notes")
        // are not multi-aspect questions: splitting them drifts intent into
        // two 0.6/0.4 subqueries. Require substantive clauses on both sides.
        val wordCount = { s: String -> s.trim().split("\\s+".toRegex()).size }
        if (wordCount(left) < 2 || wordCount(right) < 2) return null

        return DecomposedQuery(
            original = query,
            subQueries = listOf(
                SubQuery(left.trim(), "primary", 0.6f),
                SubQuery(right.trim(), "context", 0.4f)
            ),
            isComplex = true,
            decompositionStrategy = "multi_aspect"
        )
    }
}
