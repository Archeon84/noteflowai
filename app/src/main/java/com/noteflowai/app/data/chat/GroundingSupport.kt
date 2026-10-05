package com.noteflowai.app.data.chat

/**
 * Phase 6: Stateless grounding helpers. All offsets are UTF-16 code units into
 * the segment text (match Kotlin String.substring semantics; emoji/CJK-safe per
 * code unit).
 */
object GroundingSupport {

    /** Minimum embedding cosine for semantic entailment (fail-closed below). */
    const val EMBED_COSINE_FLOOR = 0.58f

    /** Minimum embedding cosine considered borderline, eligible for token overlap fallback. */
    const val BORDERLINE_COSINE_FLOOR = 0.50f

    /** Minimum contiguous-quote length (code units) to count as quote presence. */
    const val QUOTE_MIN_CHARS = 12

    /** Minimum fraction of claim content tokens present in the chunk to pass via overlap. */
    const val QUOTE_TOKEN_RATIO = 0.7f

    /** Minimum fraction of claim content tokens present in the chunk for borderline / offline fallback. */
    const val TOKEN_OVERLAP_FALLBACK_RATIO = 0.50f

    private val NON_ALNUM = Regex("[^a-z0-9\\s]")
    private val WHITESPACE = Regex("\\s+")

    data class QuoteRange(val start: Int, val end: Int)

    /**
     * Canonical quote span. Prefers valid advisory offsets; otherwise returns null
     * (callers may fall back to [findQuoteWindow] for a token-window quote).
     */
    fun computeQuoteSpan(text: String, advisoryStart: Int?, advisoryEnd: Int?): QuoteRange? {
        if (advisoryStart != null && advisoryEnd != null &&
            advisoryStart >= 0 && advisoryEnd <= text.length && advisoryStart < advisoryEnd
        ) {
            return QuoteRange(advisoryStart, advisoryEnd)
        }
        return null
    }

    /**
     * Quote-presence entailment channel: a contiguous mention of the claim text
     * (>= QUOTE_MIN_CHARS) OR >= QUOTE_TOKEN_RATIO of the claim's content tokens
     * present in the chunk.
     */
    fun checkQuotePresence(text: String, claimText: String): Boolean {
        val trimmedClaim = claimText.trim()
        if (trimmedClaim.length >= QUOTE_MIN_CHARS && text.contains(trimmedClaim)) return true

        val claimTokens = contentTokens(trimmedClaim)
        val chunkTokens = contentTokens(text)
        if (claimTokens.isEmpty() || chunkTokens.isEmpty()) return false

        val overlap = claimTokens.intersect(chunkTokens).size
        return overlap.toFloat() / claimTokens.size >= QUOTE_TOKEN_RATIO
    }

    /**
     * Fallback entailment check using content token containment.
     * Passes if at least [minRatio] of the claim's content tokens are found in the chunk text.
     * Used when cosine similarity falls in the borderline band [BORDERLINE_COSINE_FLOOR, EMBED_COSINE_FLOOR)
     * or when the embedder is offline / uninitialized.
     */
    fun checkTokenOverlapFallback(
        text: String,
        claimText: String,
        minRatio: Float = TOKEN_OVERLAP_FALLBACK_RATIO
    ): Boolean {
        val claimTokens = contentTokens(claimText)
        val chunkTokens = contentTokens(text)
        if (claimTokens.isEmpty() || chunkTokens.isEmpty()) return false
        val overlap = claimTokens.intersect(chunkTokens).size
        return overlap.toFloat() / claimTokens.size >= minRatio
    }

    /** First contiguous window in [text] covering the claim's content tokens, else null. */
    fun findQuoteWindow(text: String, claimText: String): QuoteRange? {
        val claimTokens = contentTokens(claimText)
        val firstToken = claimTokens.firstOrNull() ?: return null
        val lower = text.lowercase()
        val index = lower.indexOf(firstToken)
        if (index < 0) return null
        return QuoteRange(index, (index + firstToken.length).coerceAtMost(text.length))
    }

    /** Content tokenizer: lowercase, strip non-alnum, keep words longer than 3 chars. */
    fun contentTokens(text: String): Set<String> =
        text.lowercase()
            .replace(NON_ALNUM, "")
            .split(WHITESPACE)
            .filter { it.length > 3 }
            .toSet()

    /**
     * Map a NOTE segment id back to its paragraph index: `note_<file>_block<index>_<uuid>`.
     * Returns null for non-note segment ids.
     */
    fun noteBlockIndexFromSegmentId(segmentId: String): Int? {
        val match = Regex("block(\\d+)").find(segmentId) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}