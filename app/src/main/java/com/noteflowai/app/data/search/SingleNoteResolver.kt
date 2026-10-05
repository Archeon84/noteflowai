package com.noteflowai.app.data.search

/**
 * Single-note targeting for full-note injection.
 *
 * Broad queries spread excerpts across many notes, but some questions are
 * answerable from ONE note — either the query names its title, or every
 * surviving result maps to one source with a clear lead. In that case the LLM
 * should see the whole note instead of a 250-word slice.
 *
 * Pure function (no Android dependencies) so it is directly unit-testable.
 *
 * @param query the user-facing search query
 * @param ranked (noteSourceId, score) pairs in rank order; scores only need to
 *   be rank-consistent within a call (fused [0,1] or raw BM25)
 * @param noteTitles fileName -> display title, for title-match detection
 * @param minTopScore minimum top score to accept convergence (scale-matched by caller)
 * @return the target note fileName, or null when the question spans notes
 */
fun resolveSingleNoteTarget(
    query: String,
    ranked: List<Pair<String, Float>>,
    noteTitles: Map<String, String>,
    minTopScore: Float
): String? {
    if (ranked.isEmpty()) return null
    // 1. The query names the note. Display titles carry timestamp suffixes
    // ("Fridge Photo Magnet Idea 2026-09-08 19-29-21") that users never type,
    // so match the CORE title plus a word-set fallback for reordered mentions.
    val qLower = query.lowercase()
    for ((fileName, title) in noteTitles) {
        if (title.length >= 4 && qLower.contains(title.lowercase())) {
            return fileName
        }
        val core = coreTitle(title)
        if (core.length >= 4 && qLower.contains(core.lowercase())) {
            return fileName
        }
        val coreWords = core.lowercase().split(Regex("\\s+")).filter { it.length >= 4 }
        if (coreWords.size >= 2 && coreWords.all { qLower.contains(it) }) {
            return fileName
        }
    }
    // 2. Retrieval convergence: every surviving result maps to one note whose
    // top score clears the bar.
    if (ranked.map { it.first }.distinct().size != 1) return null
    val top = ranked.maxByOrNull { it.second } ?: return null
    if (top.second < minTopScore) return null
    return top.first
}

/**
 * Strip trailing timestamp suffixes from display titles
 * ("Idea 2026-09-08 19-29-21" -> "Idea") so user mentions match.
 */
internal fun coreTitle(display: String): String {
    return display
        .replace(Regex("\\s+\\d{4}-\\d{2}-\\d{2}.*$"), "")
        .replace(Regex("\\s+\\d{1,2}[:-]\\d{2}.*$"), "")
        .trim()
}
