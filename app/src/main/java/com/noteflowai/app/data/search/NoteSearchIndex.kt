package com.noteflowai.app.data.search

import android.content.Context
import android.util.Log
import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.displayTitle
import kotlin.math.ln
import kotlin.math.max

/**
 * BM25 search index over the user's notes with field boosting.
 *
 * Replaces the previous TF-IDF cosine-similarity implementation with Okapi BM25,
 * which handles term frequency saturation better (a word appearing 10 times isn't
 * 10x more relevant than appearing once).
 *
 * Field boosts ensure title/tag matches rank higher than body-only matches:
 *  - fileName: 3x
 *  - tags: 2.5x
 *  - category: 2x
 *  - preview: 1.5x
 *  - content: 1x (baseline)
 *
 * Performance targets:
 *  - rebuildIndex(): ~50-100ms for 500 notes
 *  - search():       <200ms for 500 docs x 50 query tokens
 */
class NoteSearchIndex {

    companion object {
        private const val TAG = "NoteSearchIndex"
        private const val MAX_DOC_LENGTH = 500_000 // 500k chars (~125k words) ensures large notes are indexed without truncation
        private const val EXCERPT_WINDOW = 120     // chars around match for excerpt

        // BM25 constants
        private const val BM25_K1 = 1.2f
        private const val BM25_B = 0.75f

        // Field boost multipliers
        private const val BOOST_FILE_NAME = 3.0f
        private const val BOOST_TAGS = 2.5f
        private const val BOOST_CATEGORY = 2.0f
        private const val BOOST_PREVIEW = 1.5f
        private const val BOOST_CONTENT = 1.0f

        // Minimum relevance score threshold
        private const val DEFAULT_MIN_SCORE = 0.05f

        // Tokenizer regexes hoisted so they are compiled once, not per call.
        private val NON_ALNUM = Regex("[^a-z0-9\\s]")
        private val WHITESPACE = Regex("\\s+")
    }

    /** A single indexed document with field-specific token lists. */
    private data class DocEntry(
        val fileName: String,
        val title: String,
        val category: String,
        val tags: List<String>,
        val preview: String,
        val fullText: String,
        // Field-specific token lists for boosted scoring
        val fileNameTokens: List<String>,
        val tagTokens: List<String>,
        val categoryTokens: List<String>,
        val previewTokens: List<String>,
        val contentTokens: List<String>,
        // Term frequency maps per field
        val fileNameTF: Map<String, Float>,
        val tagTF: Map<String, Float>,
        val categoryTF: Map<String, Float>,
        val previewTF: Map<String, Float>,
        val contentTF: Map<String, Float>,
        // Union of all field vocabularies: the search prefilter checks token
        // overlap against this set before paying for BM25 math per field.
        val allTokens: Set<String>,
        // Combined document length (weighted by field importance)
        val combinedLength: Int
    )

    /** Search result with relevance score and context excerpt. */
    data class SearchResult(
        val fileName: String,
        val title: String,
        val score: Float,
        val excerpt: String,
        /** Which fields contributed to this match (for attribution). */
        val matchedFields: List<String> = emptyList(),
        /** Search provenance: "bm25", "embedding", or "hybrid". */
        val source: String = "bm25"
    )

    @Volatile
    private var docs: List<DocEntry> = emptyList()

    @Volatile
    private var idf: Map<String, Float> = emptyMap()

    @Volatile
    private var totalDocs: Int = 0

    @Volatile
    private var avgDocLength: Float = 0f

    /** Fingerprint of the last indexed notes list — skip rebuild when unchanged. */
    @Volatile
    private var lastFingerprint: String = ""

    /**
     * Change fingerprint over the notes list. Content hashes (not a prefix
     * sample) so body edits past any offset invalidate; combined with a
     * StringBuilder so the 40KB string rebuild per check doesn't go O(n^2).
     */
    fun computeFingerprint(notes: List<NoteFile>): String {
        var h = 17
        for (note in notes) {
            h = 31 * h + note.fileName.hashCode()
            h = 31 * h + note.lastModifiedEpoch.hashCode()
            h = 31 * h + note.content.hashCode()
            h = 31 * h + note.preview.hashCode()
            h = 31 * h + note.category.hashCode()
            h = 31 * h + note.tags.hashCode()
        }
        return "v3_" + h.toString(16)
    }

    // ── Index building ────────────────────────────────────────────────

    /**
     * Rebuild the full index from the current notes list.
     * Call this whenever notes are created, updated, or deleted.
     *
     * Phase 1 integrity: synchronized so concurrent triggers (cold-start
     * rebuild vs the debounced notes collector) cannot interleave field
     * writes; the fingerprint early-return makes the loser a no-op. Search
     * stays lock-free on the volatile references.
     */
    fun rebuildIndex(notes: List<NoteFile>) {
        synchronized(rebuildLock) {
            rebuildIndexLocked(notes)
        }
    }

    private val rebuildLock = Any()

    private fun rebuildIndexLocked(notes: List<NoteFile>) {
        // Skip rebuild if the notes list hasn't actually changed
        val fingerprint = computeFingerprint(notes)
        if (fingerprint == lastFingerprint) return
        lastFingerprint = fingerprint

        val start = System.currentTimeMillis()
        totalDocs = notes.size

        docs = notes.map { note ->
            val text = note.content.take(MAX_DOC_LENGTH)
            val combined = "${note.fileName} ${note.preview} ${note.tags.joinToString(" ")} ${note.category} $text"

            val fileNameTokens = tokenize(note.fileName)
            val tagTokens = note.tags.flatMap { tokenize(it) }
            val categoryTokens = tokenize(note.category)
            val previewTokens = tokenize(note.preview)
            val contentTokens = tokenize(text)

            // Compute TF per field
            val fileNameTF = computeTF(fileNameTokens)
            val tagTF = computeTF(tagTokens)
            val categoryTF = computeTF(categoryTokens)
            val previewTF = computeTF(previewTokens)
            val contentTF = computeTF(contentTokens)

            // Combined length weighted by field importance
            val combinedLength = (fileNameTokens.size * BOOST_FILE_NAME +
                    tagTokens.size * BOOST_TAGS +
                    categoryTokens.size * BOOST_CATEGORY +
                    previewTokens.size * BOOST_PREVIEW +
                    contentTokens.size * BOOST_CONTENT).toInt()

            DocEntry(
                fileName = note.fileName,
                title = note.displayTitle,
                category = note.category,
                tags = note.tags,
                preview = note.preview,
                fullText = text,
                fileNameTokens = fileNameTokens,
                tagTokens = tagTokens,
                categoryTokens = categoryTokens,
                previewTokens = previewTokens,
                contentTokens = contentTokens,
                fileNameTF = fileNameTF,
                tagTF = tagTF,
                categoryTF = categoryTF,
                previewTF = previewTF,
                contentTF = contentTF,
                allTokens = fileNameTF.keys + tagTF.keys + categoryTF.keys + previewTF.keys + contentTF.keys,
                combinedLength = combinedLength
            )
        }

        // Compute average document length (weighted)
        avgDocLength = if (docs.isNotEmpty()) {
            docs.map { it.combinedLength }.average().toFloat()
        } else 0f

        // Compute IDF across corpus using all token sets
        val docCountPerTerm = mutableMapOf<String, Int>()
        for (doc in docs) {
            val allTerms = mutableSetOf<String>()
            allTerms.addAll(doc.fileNameTF.keys)
            allTerms.addAll(doc.tagTF.keys)
            allTerms.addAll(doc.categoryTF.keys)
            allTerms.addAll(doc.previewTF.keys)
            allTerms.addAll(doc.contentTF.keys)
            for (term in allTerms) {
                docCountPerTerm[term] = (docCountPerTerm[term] ?: 0) + 1
            }
        }

        idf = docCountPerTerm.mapValues { (_, count) ->
            ln((totalDocs.toDouble() - count + 0.5) / (count + 0.5) + 1.0).toFloat()
        }

        val elapsed = System.currentTimeMillis() - start
        Log.i(TAG, "Index rebuilt: $totalDocs docs, ${idf.size} unique terms, ${elapsed}ms")
    }

    // ── Search ────────────────────────────────────────────────────────

    /**
     * Search the index with a query string. Returns up to [maxResults] results
     * sorted by descending BM25 score with field boosting.
     *
     * @param query The search query
     * @param maxResults Maximum results to return
     * @param minScore Minimum relevance score threshold (default 0.05)
     */
    fun search(query: String, maxResults: Int = 3, minScore: Float = DEFAULT_MIN_SCORE): List<SearchResult> {
        if (docs.isEmpty() || query.isBlank()) return emptyList()

        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        // Score each document using BM25 with field boosting
        data class ScoredDoc(val entry: DocEntry, val score: Float, val matchedFields: MutableSet<String>)

        // Prefilter: a doc can only score when it shares a corpus-known token
        // with the query. Cheap set overlap replaces 5 field-BM25 evaluations
        // per token on every non-matching doc.
        val liveTokens = queryTokens.filter { it in idf }.toSet()
        if (liveTokens.isEmpty()) return emptyList()
        val candidates = docs.filter { doc -> doc.allTokens.any { it in liveTokens } }

        val scored = candidates.map { doc ->
            var totalScore = 0f
            val matchedFields = mutableSetOf<String>()

            for (token in queryTokens) {
                val idfVal = idf[token] ?: continue

                // BM25 score for each field
                val fileNameScore = bm25Score(token, doc.fileNameTF, idfVal, doc.fileNameTokens.size)
                val tagScore = bm25Score(token, doc.tagTF, idfVal, doc.tagTokens.size)
                val categoryScore = bm25Score(token, doc.categoryTF, idfVal, doc.categoryTokens.size)
                val previewScore = bm25Score(token, doc.previewTF, idfVal, doc.previewTokens.size)
                val contentScore = bm25Score(token, doc.contentTF, idfVal, doc.contentTokens.size)

                // Apply field boosts and accumulate
                val boostedScore = fileNameScore * BOOST_FILE_NAME +
                        tagScore * BOOST_TAGS +
                        categoryScore * BOOST_CATEGORY +
                        previewScore * BOOST_PREVIEW +
                        contentScore * BOOST_CONTENT

                totalScore += boostedScore

                // Track which fields matched (for attribution)
                if (fileNameScore > 0) matchedFields.add("title")
                if (tagScore > 0) matchedFields.add("tags")
                if (categoryScore > 0) matchedFields.add("category")
                if (previewScore > 0) matchedFields.add("preview")
                if (contentScore > 0) matchedFields.add("content")
            }

            ScoredDoc(doc, totalScore, matchedFields)
        }

        return scored
            .filter { it.score > minScore }
            .sortedByDescending { it.score }
            .take(maxResults)
            .map { scored ->
                SearchResult(
                    fileName = scored.entry.fileName,
                    title = scored.entry.title,
                    score = scored.score,
                    excerpt = extractExcerpt(scored.entry.fullText, queryTokens),
                    matchedFields = scored.matchedFields.toList()
                )
            }
    }

    // ── BM25 scoring ─────────────────────────────────────────────────

    /**
     * Compute BM25 score for a single query term against a document's term frequency map.
     *
     * BM25 formula: IDF(q) * (f(q,d) * (k1 + 1)) / (f(q,d) + k1 * (1 - b + b * dl / avgdl))
     */
    private fun bm25Score(
        term: String,
        termFrequency: Map<String, Float>,
        idfVal: Float,
        fieldLength: Int
    ): Float {
        val rawTf = termFrequency[term] ?: return 0f
        val docLength = fieldLength.toFloat()
        val numerator = rawTf * (BM25_K1 + 1)
        val denominator = rawTf + BM25_K1 * (1 - BM25_B + BM25_B * docLength / max(avgDocLength, 1f))
        return idfVal * numerator / denominator
    }

    // ── Tokenization ──────────────────────────────────────────────────

    /**
     * Tokenize text into lowercase terms, removing stop words and short tokens.
     */
    fun tokenize(text: String): List<String> {
        return text.lowercase(java.util.Locale.ROOT)
            .replace(NON_ALNUM, " ")   // strip punctuation
            .split(WHITESPACE)          // split on whitespace
            .filter { it.length >= 2 }  // remove single chars
            .filter { !StopWords.contains(it) }  // remove stop words
            .map { it.take(30) }        // cap token length
    }

    // ── TF computation ────────────────────────────────────────────────

    private fun computeTF(tokens: List<String>): Map<String, Float> {
        if (tokens.isEmpty()) return emptyMap()
        val counts = mutableMapOf<String, Int>()
        for (t in tokens) {
            counts[t] = (counts[t] ?: 0) + 1
        }
        return counts.mapValues { (_, count) -> count.toFloat() }
    }

    // ── Excerpt extraction ────────────────────────────────────────────

    /**
     * Extract a text excerpt around the first occurrence of query terms in [fullText].
     */
    private fun extractExcerpt(fullText: String, queryTokens: List<String>): String {
        val lowerText = fullText.lowercase(java.util.Locale.ROOT)
        var bestIndex = -1

        // Find the earliest match of any query token
        for (token in queryTokens) {
            val idx = lowerText.indexOf(token)
            if (idx >= 0 && (bestIndex < 0 || idx < bestIndex)) {
                bestIndex = idx
            }
        }

        if (bestIndex < 0) {
            // No exact match found — return beginning of text
            return fullText.take(EXCERPT_WINDOW * 2).trim() +
                if (fullText.length > EXCERPT_WINDOW * 2) "..." else ""
        }

        val start = (bestIndex - EXCERPT_WINDOW).coerceAtLeast(0)
        val end = (bestIndex + EXCERPT_WINDOW * 2).coerceAtMost(fullText.length)
        val excerpt = fullText.substring(start, end).trim()

        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < fullText.length) "..." else ""
        return "$prefix$excerpt$suffix"
    }

    // ── Disk persistence ───────────────────────────────────────

    /**
     * Try to load a previously-persisted index from disk.
     *
     * Takes the current notes so freshness is decided against a real
     * fingerprint: the old no-arg form compared against the cold-start ""
     * and never hit. @return true if a fresh cached index was adopted.
     */
    fun loadFromDisk(context: Context, notes: List<NoteFile>): Boolean {
        val snapshot = NoteIndexPersistence.load(context) ?: return false
        val fingerprint = computeFingerprint(notes)
        if (snapshot.fingerprint != fingerprint) {
            Log.i(TAG, "Stale index (fingerprint mismatch) — will rebuild")
            return false
        }

        totalDocs = snapshot.totalDocs
        avgDocLength = snapshot.avgDocLength
        idf = snapshot.idf
        lastFingerprint = snapshot.fingerprint

        docs = snapshot.docs.map { s ->
            // Old cache files predate allTokens: recompute from the TF maps.
            val allTokens = s.allTokens.toSet().ifEmpty {
                s.fileNameTF.keys + s.tagTF.keys + s.categoryTF.keys + s.previewTF.keys + s.contentTF.keys
            }
            DocEntry(
                fileName = s.fileName,
                title = s.title,
                category = s.category,
                tags = s.tags,
                preview = s.preview,
                fullText = s.fullText,
                fileNameTokens = s.fileNameTokens,
                tagTokens = s.tagTokens,
                categoryTokens = s.categoryTokens,
                previewTokens = s.previewTokens,
                contentTokens = s.contentTokens,
                fileNameTF = s.fileNameTF,
                tagTF = s.tagTF,
                categoryTF = s.categoryTF,
                previewTF = s.previewTF,
                contentTF = s.contentTF,
                allTokens = allTokens,
                combinedLength = s.combinedLength
            )
        }

        Log.i(TAG, "Loaded index from disk: $totalDocs docs, ${idf.size} terms")
        return true
    }

    /**
     * Persist the current index to disk so the next cold start can skip rebuild.
     * Should be called after rebuildIndex() completes.
     */
    fun persistIndex(context: Context) {
        if (docs.isEmpty()) return

        val snapshot = NoteIndexPersistence.Snapshot(
            fingerprint = lastFingerprint,
            totalDocs = totalDocs,
            avgDocLength = avgDocLength,
            idf = idf,
            docs = docs.map { d ->
                NoteIndexPersistence.SerializableDoc(
                    fileName = d.fileName,
                    title = d.title,
                    category = d.category,
                    tags = d.tags,
                    preview = d.preview,
                    fullText = d.fullText,
                    fileNameTokens = d.fileNameTokens,
                    tagTokens = d.tagTokens,
                    categoryTokens = d.categoryTokens,
                    previewTokens = d.previewTokens,
                    contentTokens = d.contentTokens,
                    fileNameTF = d.fileNameTF,
                    tagTF = d.tagTF,
                    categoryTF = d.categoryTF,
                    previewTF = d.previewTF,
                    contentTF = d.contentTF,
                    allTokens = d.allTokens.toList(),
                    combinedLength = d.combinedLength
                )
            }
        )

        NoteIndexPersistence.persist(context, snapshot)
    }

    // ── Utility ───────────────────────────────────────────────────────

    fun indexSize(): Int = docs.size
    fun isEmpty(): Boolean = docs.isEmpty()

    /** Get the current fingerprint (for persistence checks). */
    fun getCurrentFingerprint(): String = lastFingerprint

    /** Get all indexed document entry count. */
    fun getDocumentCount(): Int = totalDocs
}
