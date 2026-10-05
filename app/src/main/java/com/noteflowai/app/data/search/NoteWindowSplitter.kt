package com.noteflowai.app.data.search

import kotlin.math.sqrt

/**
 * Query-time windowing for long-note offline reading.
 *
 * Splits one note's text into fixed ~600-token windows (2400 chars at 4
 * chars/token) with ~80-token overlap (320 chars), packed on paragraph
 * boundaries with word-boundary fallback. Pure JVM — directly unit-testable.
 *
 * Windows are ranked by cosine against the query embedding by [rankWindows];
 * callers embed each window (head+tail recommended — the on-device embedder
 * truncates to 128 tokens) and inject the top windows in document order.
 */
data class NoteWindow(val text: String, val index: Int, val total: Int)

fun splitNoteWindows(
    text: String,
    windowChars: Int = 2400,
    overlapChars: Int = 320
): List<NoteWindow> {
    if (text.isBlank()) return emptyList()
    val trimmed = text.trim()
    if (trimmed.length <= windowChars) return listOf(NoteWindow(trimmed, 0, 1))

    // Pack paragraphs into windows; split oversized paragraphs on word boundaries.
    val paragraphs = trimmed.split(Regex("\n\n+")).map { it.trim() }.filter { it.isNotEmpty() }
    val pieces = mutableListOf<String>()
    for (para in paragraphs) {
        var rest = para
        while (rest.length > windowChars) {
            val cut = rest.lastIndexOf(' ', windowChars).takeIf { it > windowChars / 2 }
                ?: windowChars
            pieces.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces.add(rest)
    }

    val windows = mutableListOf<String>()
    var current = StringBuilder()
    for (piece in pieces) {
        if (current.isNotEmpty() && current.length + 2 + piece.length > windowChars) {
            windows.add(current.toString())
            // Overlap: tail of the emitted window, word-boundary adjusted.
            val tail = current.takeLast(overlapChars)
            val overlapStart = tail.indexOf(' ').takeIf { it >= 0 }?.plus(1) ?: 0
            current = StringBuilder(tail.substring(overlapStart))
            if (current.isNotEmpty()) current.append("\n\n")
        } else if (current.isNotEmpty()) {
            current.append("\n\n")
        }
        current.append(piece)
    }
    if (current.isNotBlank()) windows.add(current.toString().trim())

    return windows.mapIndexed { i, w -> NoteWindow(w, i, windows.size) }
}

/** Cosine similarity over raw float vectors (embeddings are L2-normalized). */
fun cosineSimilarityVec(a: FloatArray, b: FloatArray): Float {
    var dot = 0f
    var na = 0f
    var nb = 0f
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        dot += a[i] * b[i]
        na += a[i] * a[i]
        nb += b[i] * b[i]
    }
    if (na == 0f || nb == 0f) return 0f
    return (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1f, 1f)
}

/**
 * Rank window indices by best cosine against the query vector. Each window may
 * carry several embeddings (e.g. head + tail slices for truncating embedders);
 * the window score is the max over its embeddings. Returns indices sorted by
 * descending score.
 */
fun rankWindows(
    queryVector: FloatArray,
    windowVectors: List<List<FloatArray>>
): List<Int> {
    return windowVectors.indices
        .map { i ->
            val best = windowVectors[i].maxOfOrNull { cosineSimilarityVec(queryVector, it) } ?: 0f
            i to best
        }
        .sortedByDescending { it.second }
        .map { it.first }
}
