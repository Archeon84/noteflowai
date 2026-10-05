package com.noteflowai.app.data.search

/**
 * Character-level fuzzy matching using a simplified Jaro-Winkler-inspired score.
 *
 * Handles typos, transpositions, and partial matches that BM25 token scoring misses.
 * Used for note-list filtering where users may mistype note titles.
 */
object FuzzyMatcher {

    /**
     * Compute a fuzzy similarity score between a query and a target string.
     * Returns a score in [0, 1] where 1 is an exact match.
     *
     * Uses a combination of:
     * - Longest common subsequence ratio for character-order sensitivity
     * - Prefix bonus for partial matches starting from the beginning
     * - Token overlap for multi-word queries
     */
    private val WHITESPACE = Regex("\\s+")
    /** Skip LCS entirely past this size (per-keystroke scoring). */
    private const val MAX_LCS_CHARS = 120

    fun score(query: String, target: String): Float {
        if (query.isEmpty()) return 0f
        if (target.isEmpty()) return 0f
        // Early exit: wildly different lengths cannot score above threshold.
        if (maxOf(query.length, target.length) > 3 * minOf(query.length, target.length) + 8) return 0f

        val q = query.lowercase(java.util.Locale.ROOT)
        val t = target.lowercase(java.util.Locale.ROOT)

        // Exact match
        if (q == t) return 1.0f

        // Prefix match bonus (longer prefix = higher score)
        var prefixLen = 0
        while (prefixLen < q.length && prefixLen < t.length && q[prefixLen] == t[prefixLen]) {
            prefixLen++
        }
        val prefixScore = if (prefixLen > 0) {
            0.4f + 0.3f * (prefixLen.toFloat() / q.length)
        } else 0f

        // Longest common subsequence (LCS) ratio, skipped for long inputs.
        val lcsScore = if (maxOf(q.length, t.length) <= MAX_LCS_CHARS) {
            longestCommonSubsequence(q, t).toFloat() / maxOf(q.length, t.length)
        } else {
            0f
        }

        // Token overlap for multi-word queries
        val qTokens = q.split(WHITESPACE).filter { it.length >= 2 }
        val tTokens = t.split(WHITESPACE).filter { it.length >= 2 }
        val tokenScore = if (qTokens.isNotEmpty() && tTokens.isNotEmpty()) {
            val matches = qTokens.count { qt -> tTokens.any { tt -> tt.contains(qt) || qt.contains(tt) } }
            matches.toFloat() / qTokens.size
        } else 0f

        // Weighted combination
        return (prefixScore * 0.4f + lcsScore * 0.4f + tokenScore * 0.2f).coerceIn(0f, 1f)
    }

    /**
     * Check if a target approximately matches a query, above a threshold.
     */
    fun matches(query: String, target: String, threshold: Float = 0.35f): Boolean {
        return score(query, target) >= threshold
    }

    private fun longestCommonSubsequence(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        // Optimized: only keep two rows at a time
        var prev = IntArray(n + 1)
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            for (j in 1..n) {
                curr[j] = if (a[i - 1] == b[j - 1]) {
                    prev[j - 1] + 1
                } else {
                    maxOf(prev[j], curr[j - 1])
                }
            }
            val temp = prev
            prev = curr
            curr = temp
            curr.fill(0)
        }
        return prev[n]
    }
}
