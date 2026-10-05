package com.noteflowai.app.data.search

/**
 * Reformulates search queries to improve retrieval when initial results are poor.
 *
 * Uses synonym expansion and query relaxation to cast a wider net when
 * the original query returns low-scoring or few results.
 */
class QueryReformulator {

    data class ReformulationResult(
        val originalQuery: String,
        val reformulatedQuery: String,
        val strategy: String
    )

    companion object {
        /** If the top result score is below this, attempt reformulation. */
        const val POOR_RESULT_THRESHOLD = 0.3f

        /** Minimum results to consider "enough" — below this triggers reformulation. */
        const val MIN_RESULTS_THRESHOLD = 2
    }

    /**
     * Attempt to reformulate a query that produced poor results.
     *
     * @param query the original search query
     * @param topScore score of the best result from the original search
     * @param resultCount number of results returned
     * @return reformulated query, or null if the original results are acceptable
     */
    fun reformulateIfNeeded(
        query: String,
        topScore: Float,
        resultCount: Int
    ): ReformulationResult? {
        if (topScore >= POOR_RESULT_THRESHOLD && resultCount >= MIN_RESULTS_THRESHOLD) {
            return null  // results are good enough
        }

        // Strategy 1: Expand with synonyms
        val expanded = expandWithSynonyms(query)
        if (expanded != query) {
            return ReformulationResult(
                originalQuery = query,
                reformulatedQuery = expanded,
                strategy = "synonym_expansion"
            )
        }

        // Strategy 2: Relax to individual tokens (if multi-word query)
        val tokens = query.split("\\s+".toRegex()).filter { it.length >= 2 }
        if (tokens.size > 1) {
            // Try the most specific (longest) token alone
            val longestToken = tokens.maxByOrNull { it.length } ?: return null
            return ReformulationResult(
                originalQuery = query,
                reformulatedQuery = longestToken,
                strategy = "token_relaxation"
            )
        }

        // Strategy 3: Strip common prefixes/suffixes for prefix matching
        if (tokens.size == 1) {
            val token = tokens.first()
            // Try trimming to a shorter prefix if the token is long
            if (token.length > 4) {
                return ReformulationResult(
                    originalQuery = query,
                    reformulatedQuery = token.take(token.length - 2),
                    strategy = "prefix_trimming"
                )
            }
        }

        return null
    }

    /**
     * Expand a query by adding synonyms for each token.
     */
    private fun expandWithSynonyms(query: String): String {
        val tokens = query.split("\\s+".toRegex()).filter { it.length >= 2 }
        val expandedTokens = mutableListOf<String>()

        for (token in tokens) {
            expandedTokens.add(token)
            val synonyms = SmartSnippetExtractor.expandSynonyms(token)
            // Add the most distinct synonym (not already in the query)
            for (syn in synonyms) {
                if (syn != token.lowercase(java.util.Locale.ROOT) && syn !in expandedTokens) {
                    expandedTokens.add(syn)
                    break  // only add one synonym per token to keep query manageable
                }
            }
        }

        return expandedTokens.distinct().joinToString(" ")
    }
}
