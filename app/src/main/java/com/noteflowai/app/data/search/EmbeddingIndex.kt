package com.noteflowai.app.data.search

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.noteflowai.app.data.NoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Stores per-note embedding vectors and provides cosine-similarity search.
 *
 * Embeddings are persisted to [Context.filesDir]/search_index/embeddings.json
 * so cold starts only re-embed notes that changed since last persist.
 *
 * Falls back to empty results when no embeddings are available (offline + no on-device model).
 */
class EmbeddingIndex {

    companion object {
        private const val TAG = "EmbeddingIndex"
        private const val DIR_NAME = "search_index"
        private const val FILE_NAME = "embeddings.json"
        // Relevance floor for cosine search, shared by note-level retrieval,
        // segment retrieval, and AutoLinker semantic linking. Raised back from
        // 0.20 to 0.25 to cut false-semantic edges in the knowledge graph: low
        // cosine matches (near-orthogonal vectors) were producing noisy links
        // between unrelated notes. The higher floor is the value originally
        // calibrated for the on-device embedding model and keeps only clearly
        // related matches.
        const val MIN_COSINE_SIMILARITY = 0.25f

        // Bump this when the embedding model changes. Persisted vectors from a
        // different model live in a different vector space, so they are invalid
        // and must be re-embedded (loadFromDisk returns false -> full rebuild).
        const val MODEL_FINGERPRINT = "ibm-granite-embedding-311m-multilingual-r2"

        /** Fingerprint for vectors produced by a remote provider. Distinct per
         *  provider+model, so switching remote models invalidates persisted
         *  vectors (they live in a different vector space). */
        fun remoteFingerprint(provider: String, model: String?): String =
            "remote-${provider.lowercase(java.util.Locale.ROOT)}-${(model ?: "default").lowercase(java.util.Locale.ROOT)}"
    }

    /** Disk format header so a model change invalidates stale vectors. */
    private data class PersistedIndex(
        val fingerprint: String,
        val entries: Collection<EmbeddingEntry>
    )

    /** Serializable embedding entry. */
    data class EmbeddingEntry(
        val fileName: String,
        val lastModifiedEpoch: Long,
        val vector: FloatArray,
        val dimensions: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EmbeddingEntry) return false
            return fileName == other.fileName
        }
        override fun hashCode(): Int = fileName.hashCode()
    }

    /** Search result from embedding-based retrieval. */
    data class EmbeddingSearchResult(
        val fileName: String,
        val score: Float,       // cosine similarity [0, 1]
        val excerpt: String = ""
    )

    private val gson: Gson = GsonBuilder().create()

    /**
     * Phase 1 integrity: guards compound read-modify-writes (put/prune/clear
     * + persist) so concurrent rebuild passes cannot interleave map mutations
     * and persist a half-updated view. Readers (search/getEmbedding) stay
     * lock-free on the volatile map reference.
     */
    private val writeLock = Any()

    private val entries: ConcurrentHashMap<String, EmbeddingEntry> = ConcurrentHashMap()

    @Volatile
    private var dimensions: Int = 0

    /**
     * Fingerprint of the backend that produced the loaded vectors (on-device
     * model id or remote provider+model). Null when empty/unknown. One index
     * holds exactly one vector space — mixing dimensions bricks search.
     */
    @Volatile
    private var activeFingerprint: String? = null

    // ── Persistence ───────────────────────────────────────────

    /**
     * Load persisted embeddings from disk.
     *
     * Accepts any known fingerprint (on-device model or any remote
     * provider+model) and records it as [activeFingerprint]; the caller
     * compares that against the current backend and clears on a switch.
     * Unknown/legacy formats are treated as stale (cleared, returns false).
     */
    fun loadFromDisk(context: Context): Boolean {
        val file = getFile(context)
        if (!file.exists()) return false

        return try {
            val wrapped = java.io.BufferedReader(
                java.io.InputStreamReader(java.io.FileInputStream(file), Charsets.UTF_8),
                32768
            ).use { reader ->
                gson.fromJson(reader, PersistedIndex::class.java)
            }
            if (wrapped == null || !isKnownFingerprint(wrapped.fingerprint)) {
                Log.i(TAG, "Stale embeddings (unknown fingerprint) — will re-embed")
                clear()
                return false
            }
            synchronized(writeLock) {
                entries.clear()
                for (entry in wrapped.entries) {
                    entries[entry.fileName] = entry
                }
                dimensions = entries.values.firstOrNull()?.dimensions ?: 0
                activeFingerprint = wrapped.fingerprint
            }
            Log.i(TAG, "Loaded ${entries.size} embeddings from disk (${dimensions}d, ${wrapped.fingerprint})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load embeddings: ${e.message}")
            false
        }
    }

    private fun isKnownFingerprint(fingerprint: String): Boolean {
        if (fingerprint == MODEL_FINGERPRINT) return true
        // remote-<provider>-<model>, per remoteFingerprint().
        return fingerprint.startsWith("remote-")
    }

    /**
     * Persist current embeddings to disk, tagged with a fingerprint identifying
     * the embedding model/source that produced them so a future model change
     * invalidates them. Defaults to the on-device [MODEL_FINGERPRINT]; callers
     * that embed remotely must pass [remoteFingerprint].
     */
    fun persistToDisk(context: Context, fingerprint: String = MODEL_FINGERPRINT) {
        val file = getFile(context)
        val data = synchronized(writeLock) {
            PersistedIndex(fingerprint, entries.values)
        }
        if (com.noteflowai.app.data.AtomicJsonFile.writeStream(file) { writer ->
            gson.toJson(data, writer)
        }) {
            synchronized(writeLock) { activeFingerprint = fingerprint }
            Log.i(TAG, "Persisted ${entries.size} embeddings (${file.length()} bytes)")
        }
    }

    // ── Index management ──────────────────────────────────────

    /**
     * Get the set of note fileNames that need re-embedding.
     * A note needs re-embedding if it's new or its lastModifiedEpoch changed.
     */
    fun getStaleNotes(notes: List<NoteFile>): List<NoteFile> {
        return notes.filter { note ->
            val existing = entries[note.fileName]
            existing == null || existing.lastModifiedEpoch != note.lastModifiedEpoch
        }
    }

    /**
     * Store an embedding for a single note.
     *
     * Rejects vectors from a different vector space: the old max() merge let
     * a 384d on-device index absorb 1536d remote vectors (or vice versa),
     * after which search() returned empty for EVERYTHING. First family wins;
     * mismatches are logged and dropped.
     */
    fun putEmbedding(fileName: String, lastModifiedEpoch: Long, vector: FloatArray) {
        if (vector.isEmpty()) return
        synchronized(writeLock) {
            if (dimensions != 0 && vector.size != dimensions) {
                Log.w(TAG, "Rejecting ${vector.size}d vector for $fileName (index is ${dimensions}d) — mixed spaces brick search")
                return
            }
            entries[fileName] = EmbeddingEntry(
                fileName = fileName,
                lastModifiedEpoch = lastModifiedEpoch,
                vector = vector,
                dimensions = vector.size
            )
            dimensions = vector.size
        }
    }

    /** Fingerprint of the loaded vectors' backend, or null when empty. */
    fun getActiveFingerprint(): String? = activeFingerprint

    /** Drop all vectors (backend switch) and reset the space. */
    fun clear() {
        synchronized(writeLock) {
            entries.clear()
            dimensions = 0
            activeFingerprint = null
        }
    }

    /**
     * Remove embeddings for notes that no longer exist.
     */
    fun prune(existingFileNames: Set<String>) {
        synchronized(writeLock) {
            val removed = entries.keys.filter { it !in existingFileNames }
            removed.forEach { entries.remove(it) }
            if (removed.isNotEmpty()) {
                Log.i(TAG, "Pruned ${removed.size} stale embeddings")
            }
        }
    }

    // ── Search ────────────────────────────────────────────────

    /**
     * Search for notes most similar to the query embedding.
     *
     * @param queryVector The query embedding vector
     * @param maxResults Maximum results to return
     * @param minScore Minimum cosine similarity threshold
     * @return Results sorted by descending similarity
     */
    fun search(
        queryVector: FloatArray,
        maxResults: Int = 5,
        minScore: Float = MIN_COSINE_SIMILARITY
    ): List<EmbeddingSearchResult> {
        if (entries.isEmpty() || queryVector.isEmpty()) return emptyList()

        // Dimension mismatch check
        if (dimensions > 0 && queryVector.size != dimensions) {
            Log.w(TAG, "Query dimension ${queryVector.size} != index dimension $dimensions")
            return emptyList()
        }

        return entries.values
            .map { entry ->
                val score = cosineSimilarity(queryVector, entry.vector)
                EmbeddingSearchResult(
                    fileName = entry.fileName,
                    score = score
                )
            }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(maxResults)
    }

    /**
     * Get the embedding vector for a specific note (for graph operations).
     */
    fun getEmbedding(fileName: String): FloatArray? {
        return entries[fileName]?.vector
    }

    // ── Utility ───────────────────────────────────────────────

    fun size(): Int = entries.size
    fun isEmpty(): Boolean = entries.isEmpty()
    fun getDimensions(): Int = dimensions

    private fun getFile(context: Context): File {
        return File(context.filesDir, "$DIR_NAME/$FILE_NAME")
    }

    /**
     * Compute cosine similarity between two vectors.
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0f) dotProduct / denominator else 0f
    }
}
