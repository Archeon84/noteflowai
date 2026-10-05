package com.noteflowai.app.data.memory.pipeline

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ln
import kotlin.math.max

/**
 * Lightweight BM25 full-text index over source segments (§7 INDEXING stage).
 *
 * The existing [com.noteflowai.app.data.search.NoteSearchIndex] is keyed by
 * NoteFile and carries fingerprint/persistence logic tied to notes, so this
 * index is intentionally separate and segment-scoped. It reuses the same
 * tokenizer/BM25 shape so retrieval can later return segment-level citations.
 *
 * Entries are persisted to `filesDir/search_index/segment_bm25.json`. Corpus
 * statistics (doc frequency, doc count, average length) are cached at mutation
 * time and read on search, so a query never pays a full-corpus pass.
 */
class SegmentIndexService(
    private val context: Context,
    private val sourceSegmentRepository: SourceSegmentRepository
) {

    companion object {
        private const val TAG = "SegmentIndexService"
        private const val DIR_NAME = "search_index"
        private const val FILE_NAME = "segment_bm25.json"
        private const val EXCERPT_WINDOW = 120
        private const val BM25_K1 = 1.2f
        private const val BM25_B = 0.75f
        private const val MAX_SEGMENT_CHARS = 4000

        // Tokenizer regexes hoisted so they are compiled once, not per call.
        private val NON_ALNUM = Regex("[^a-z0-9\\s]")
        private val WHITESPACE = Regex("\\s+")

        /**
         * Phase 1 integrity: a single shared instance keeps the in-memory
         * entry map consistent between the ingestion pipeline and retrieval —
         * separate instances would last-writer-wins clobber segment_bm25.json.
         */
        @Volatile
        private var INSTANCE: SegmentIndexService? = null

        fun getInstance(context: Context): SegmentIndexService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SegmentIndexService(
                    context.applicationContext,
                    SourceSegmentRepository(context)
                ).also { INSTANCE = it }
            }
        }
    }

    /**
     * Phase 1 integrity: serializes indexSource/removeSource (mutate stats +
     * persist) so concurrent sources cannot interleave map mutations and
     * persist a half-updated view. Search stays lock-free.
     */
    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    /** A segment hit from a BM25 search. */
    data class SegmentHit(
        val segmentId: String,
        val sourceId: String,
        val score: Float,
        val excerpt: String
    )

    private data class Entry(
        val segmentId: String,
        val sourceId: String,
        val text: String,
        val tokens: List<String>,
        /** Term-frequency map, precomputed at index time (not persisted). */
        val tf: Map<String, Int>? = null
    )

    private val gson = Gson()

    @Volatile
    private var entries: MutableMap<String, Entry> = mutableMapOf()

    @Volatile
    private var loaded = false

    // Corpus statistics, recomputed only on mutation (search reads these).
    @Volatile
    private var docFreq: Map<String, Int> = emptyMap()

    @Volatile
    private var totalDocs: Int = 0

    @Volatile
    private var avgLength: Float = 0f

    /**
     * Index all segments of [sourceId], replacing any prior entries for those
     * segment ids. Returns true if at least one segment was indexed (or the
     * source has no segments).
     */
    suspend fun indexSource(sourceId: String): Boolean = withContext(Dispatchers.IO) {
        ensureLoaded()
        writeMutex.withLock {
            try {
                val segments = sourceSegmentRepository.getBySourceId(sourceId)
                if (segments.isEmpty()) {
                    Log.w(TAG, "No segments to index for source: $sourceId")
                    return@withLock true
                }
                for (seg in segments) {
                    val tokens = tokenize(seg.text)
                    entries[seg.id] = Entry(
                        segmentId = seg.id,
                        sourceId = seg.sourceId,
                        text = seg.text.take(MAX_SEGMENT_CHARS),
                        tokens = tokens,
                        tf = computeTF(tokens)
                    )
                }
                refreshStats()
                persist()
                Log.i(TAG, "Indexed ${segments.size} segments for source: $sourceId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Segment indexing failed for source: $sourceId", e)
                false
            }
        }
    }

    /**
     * Remove entries for a deleted source.
     */
    suspend fun removeSource(sourceId: String) = withContext(Dispatchers.IO) {
        ensureLoaded()
        writeMutex.withLock {
            val removed = entries.keys.filter { entries[it]?.sourceId == sourceId }
            removed.forEach { entries.remove(it) }
            if (removed.isNotEmpty()) {
                refreshStats()
                persist()
            }
        }
    }

    /**
     * BM25 search over all indexed segments. Returns up to [maxResults] hits
     * sorted by descending score.
     */
    suspend fun search(query: String, maxResults: Int = 10): List<SegmentHit> = withContext(Dispatchers.IO) {
        ensureLoaded()
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty() || entries.isEmpty()) return@withContext emptyList()

        // Corpus statistics are cached at mutation time, so a search is a pure
        // per-document scoring pass with no corpus-wide recomputation.
        val docCount = totalDocs
        val avgLen = avgLength
        val df = docFreq

        data class Scored(val entry: Entry, val score: Float)

        val scored = entries.values.mapNotNull { entry ->
            val tf = entry.tf ?: computeTF(entry.tokens)
            var total = 0f
            for (token in queryTokens) {
                val docFrequency = df[token] ?: 0
                val idf = ln(((docCount - docFrequency + 0.5) / (docFrequency + 0.5)) + 1.0).toFloat()
                val rawTf = (tf[token] ?: 0).toFloat()
                if (rawTf == 0f) continue
                val numerator = rawTf * (BM25_K1 + 1)
                val denominator = rawTf + BM25_K1 * (1 - BM25_B + BM25_B * entry.tokens.size / max(avgLen, 1f))
                total += idf * numerator / denominator
            }
            if (total <= 0f) null else Scored(entry, total)
        }

        scored.sortedByDescending { it.score }
            .take(maxResults)
            .map { ScoredHit ->
                SegmentHit(
                    segmentId = ScoredHit.entry.segmentId,
                    sourceId = ScoredHit.entry.sourceId,
                    score = ScoredHit.score,
                    excerpt = extractExcerpt(ScoredHit.entry.text, queryTokens)
                )
            }
    }

    fun size(): Int = entries.size

    /**
     * Recompute corpus-wide statistics (doc frequency, doc count, average
     * length) from the current entries. Called only on mutation, so searches
     * never pay this cost.
     */
    private fun refreshStats() {
        val docList = entries.values.toList()
        totalDocs = docList.size
        avgLength = if (docList.isEmpty()) 0f else {
            docList.map { it.tokens.size }.average().toFloat().coerceAtLeast(1f)
        }
        val df = mutableMapOf<String, Int>()
        for (entry in docList) {
            entry.tokens.toSet().forEach { t -> df[t] = (df[t] ?: 0) + 1 }
        }
        docFreq = df
    }

    // ── Persistence ───────────────────────────────────────────────────

    private fun ensureLoaded() {
        if (loaded) return
        val file = indexFile()
        if (file.exists()) {
            try {
                val type = object : TypeToken<List<Entry>>() {}.type
                val list: List<Entry> = java.io.BufferedReader(
                    java.io.InputStreamReader(java.io.FileInputStream(file), Charsets.UTF_8),
                    32768
                ).use { reader ->
                    gson.fromJson(reader, type)
                }
                // Older persisted entries have no tf map; backfill it on load.
                entries = list.associateBy { it.segmentId }.toMutableMap().mapValues { (_, e) ->
                    if (e.tf == null) e.copy(tf = computeTF(e.tokens)) else e
                }.toMutableMap()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load segment index: ${e.message}")
            }
        }
        refreshStats()
        loaded = true
    }

    private fun persist() {
        val file = indexFile()
        val data = entries.values
        com.noteflowai.app.data.AtomicJsonFile.writeStream(file) { writer ->
            gson.toJson(data, writer)
        }
    }

    private fun indexFile(): File =
        File(context.filesDir, "$DIR_NAME/$FILE_NAME")

    // ── Tokenizer / excerpt (mirrors NoteSearchIndex) ─────────────────

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(NON_ALNUM, " ")
            .split(WHITESPACE)
            .filter { it.length >= 2 }
            .filter { it !in STOP_WORDS }
            .map { it.take(30) }
    }

    private fun computeTF(tokens: List<String>): Map<String, Int> {
        if (tokens.isEmpty()) return emptyMap()
        return tokens.groupingBy { it }.eachCount()
    }

    private fun extractExcerpt(fullText: String, queryTokens: List<String>): String {
        val lowerText = fullText.lowercase()
        var bestIndex = -1
        for (token in queryTokens) {
            val idx = lowerText.indexOf(token)
            if (idx >= 0 && (bestIndex < 0 || idx < bestIndex)) bestIndex = idx
        }
        if (bestIndex < 0) {
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

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "than", "so", "this",
        "that", "with", "for", "of", "on", "in", "to", "is", "are", "was", "were",
        "it", "its", "as", "at", "be", "by", "from", "we", "you", "your", "i", "he",
        "she", "they", "them", "their", "not", "no", "yes", "can", "will", "would"
    )
}
