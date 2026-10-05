package com.noteflowai.app.data.memory.pipeline

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.noteflowai.app.data.ConnectivityChecker
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.search.EmbeddingIndex
import com.noteflowai.app.data.search.OnDeviceEmbedder
import com.noteflowai.app.data.search.RemoteEmbeddingClient
import com.noteflowai.app.data.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Generates embeddings for the segments of a source (§7 EMBEDDING stage).
 *
 * Prefers the on-device embedder when it is initialized (offline-first); falls
 * back to the configured remote embedding provider. Vectors are persisted to a
 * segment-scoped JSON file (mirroring [com.noteflowai.app.data.search.EmbeddingIndex]
 * but keyed by segment id rather than note fileName).
 *
 * The EMBEDDING stage is optional and failure-tolerant: a source whose segments
 * cannot be embedded still proceeds to INDEXING. This keeps the pipeline usable
 * offline when no embedding model is available.
 */
class SegmentEmbeddingService(
    private val context: Context,
    private val sourceSegmentRepository: SourceSegmentRepository,
    private val onDeviceEmbedder: OnDeviceEmbedder,
    private val remoteEmbeddingClient: RemoteEmbeddingClient,
    private val settingsManager: SettingsManager,
    connectivityChecker: ConnectivityChecker? = null
) {
    private val connectivityChecker = connectivityChecker ?: ConnectivityChecker(context)

    companion object {
        private const val TAG = "SegmentEmbeddingService"
        private const val DIR_NAME = "search_index"
        private const val FILE_NAME = "segment_embeddings.json"

        /** Singleton mirroring [SourceProcessingPipeline]; a single shared
         *  instance keeps the in-memory embedding map consistent between the
         *  ingestion pipeline and retrieval. */
        @Volatile
        private var INSTANCE: SegmentEmbeddingService? = null

        /** Convenience constructor mirroring the repository-singleton pattern. */
        fun getInstance(context: Context): SegmentEmbeddingService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SegmentEmbeddingService(
                    context.applicationContext,
                    SourceSegmentRepository(context),
                    OnDeviceEmbedder(),
                    RemoteEmbeddingClient(),
                    SettingsManager.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
    }

    private val gson = Gson()

    /** Disk format header so a model change invalidates stale vectors. */
    private data class PersistedSegmentEmbeddings(
        val fingerprint: String,
        val entries: Collection<SegmentEmbedding>
    )

    /** Serializable embedding entry keyed by segment id. */
    private data class SegmentEmbedding(
        val segmentId: String,
        val sourceId: String,
        val vector: FloatArray
    )

    /** A semantic hit over embedded segments. */
    data class SegmentEmbeddingHit(
        val segmentId: String,
        val sourceId: String,
        val score: Float
    )

    private val embeddings: ConcurrentHashMap<String, SegmentEmbedding> = ConcurrentHashMap()

    @Volatile
    private var dimensions: Int = 0

    @Volatile
    private var activeFingerprint: String? = null

    @Volatile
    private var loaded = false

    /**
     * Phase 1 integrity: serializes embedSource/pruneForSource (mutate map +
     * persist) so concurrent sources cannot interleave mutations and persist
     * a half-updated view. Search stays lock-free.
     */
    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Embed all segments of [sourceId]. Existing embeddings for those segments
     * are replaced. Returns true if every segment was embedded (or the source
     * has no segments), false if embedding is unavailable or partially failed.
     */
    suspend fun embedSource(sourceId: String): Boolean = withContext(Dispatchers.IO) {
        ensureLoaded()
        try {
            val segments = sourceSegmentRepository.getBySourceId(sourceId)
            if (segments.isEmpty()) {
                Log.w(TAG, "No segments to embed for source: $sourceId")
                return@withContext true
            }

            // Prefer the on-device model when ready (offline-first).
            if (onDeviceEmbedder.isReady() || onDeviceEmbedder.initialize(context)) {
                var embedded = 0
                // Collect locally: only the map merge + persist runs under the
                // write lock, not the slow per-segment inference.
                val fresh = mutableMapOf<String, SegmentEmbedding>()
                for (seg in segments) {
                    val vector = onDeviceEmbedder.embed(seg.text)
                    if (vector != null) {
                        fresh[seg.id] = SegmentEmbedding(seg.id, sourceId, vector)
                        embedded++
                    }
                }
                writeMutex.withLock {
                    val targetFp = EmbeddingIndex.MODEL_FINGERPRINT
                    val targetDim = fresh.values.firstOrNull()?.vector?.size ?: 0
                    if (activeFingerprint != null && activeFingerprint != targetFp) {
                        Log.i(TAG, "Embedding backend switched from $activeFingerprint to $targetFp; clearing stale segment embeddings")
                        embeddings.clear()
                    } else if (dimensions != 0 && targetDim != 0 && dimensions != targetDim) {
                        Log.i(TAG, "Embedding dimension changed from $dimensions to $targetDim; clearing stale segment embeddings")
                        embeddings.clear()
                    }
                    embeddings.putAll(fresh)
                    if (targetDim > 0) dimensions = targetDim
                    activeFingerprint = targetFp
                    persist(targetFp)
                }
                Log.i(TAG, "Embedded $embedded/${segments.size} segments for source: $sourceId")
                return@withContext embedded > 0
            }

            // Fall back to the remote provider. Fail fast while offline instead
            // of paying a full HTTP timeout per segment.
            if (!connectivityChecker.hasNetwork()) {
                Log.w(TAG, "Offline: skipping remote embedding for source: $sourceId")
                return@withContext false
            }
            val baseUrl = settingsManager.aiBaseUrl.first()
            val apiKey = settingsManager.aiApiKey.first()
            val model = settingsManager.embeddingModel.first().ifBlank { null }
            val provider = settingsManager.aiProvider.first()
            // Local-Only Mode: segment text must not leave the device. Loopback
            // endpoints (Ollama on-device) stay allowed; anything else is
            // skipped — ingestion continues without vectors (BM25 still works).
            if (settingsManager.isLocalOnlyMode.first() &&
                !com.noteflowai.app.data.NetworkModule.isLoopbackUrl(baseUrl)
            ) {
                Log.w(TAG, "Local-Only Mode: skipping remote embedding for source: $sourceId")
                return@withContext false
            }
            if (baseUrl.isBlank()) {
                Log.w(TAG, "No embedding backend available for source: $sourceId")
                return@withContext false
            }

            // One batch call, not N sequential HTTP round-trips.
            var embedded = 0
            val batch = remoteEmbeddingClient.embed(
                texts = segments.map { it.text },
                provider = provider,
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model
            )
            val vectors = batch?.vectors.orEmpty()
            val fresh = mutableMapOf<String, SegmentEmbedding>()
            for ((i, seg) in segments.withIndex()) {
                val vector = vectors.getOrNull(i) ?: continue
                fresh[seg.id] = SegmentEmbedding(seg.id, sourceId, vector)
                embedded++
            }
            writeMutex.withLock {
                val targetFp = EmbeddingIndex.remoteFingerprint(provider, model)
                val targetDim = fresh.values.firstOrNull()?.vector?.size ?: 0
                if (activeFingerprint != null && activeFingerprint != targetFp) {
                    Log.i(TAG, "Embedding backend switched from $activeFingerprint to $targetFp; clearing stale segment embeddings")
                    embeddings.clear()
                } else if (dimensions != 0 && targetDim != 0 && dimensions != targetDim) {
                    Log.i(TAG, "Embedding dimension changed from $dimensions to $targetDim; clearing stale segment embeddings")
                    embeddings.clear()
                }
                embeddings.putAll(fresh)
                if (targetDim > 0) dimensions = targetDim
                activeFingerprint = targetFp
                persist(targetFp)
            }
            Log.i(TAG, "Remote-embedded $embedded/${segments.size} segments for source: $sourceId")
            embedded > 0
        } catch (e: Exception) {
            Log.e(TAG, "Segment embedding failed for source: $sourceId", e)
            false
        }
    }

    /**
     * Embed a single segment on demand (used by retrieval paths that need a
     * query-time vector). Returns null if unavailable.
     */
    suspend fun embedSegment(segmentId: String, text: String): FloatArray? = withContext(Dispatchers.IO) {
        ensureLoaded()
        if (onDeviceEmbedder.isReady()) return@withContext onDeviceEmbedder.embed(text)

        val baseUrl = settingsManager.aiBaseUrl.first()
        val apiKey = settingsManager.aiApiKey.first()
        val model = settingsManager.embeddingModel.first().ifBlank { null }
        val provider = settingsManager.aiProvider.first()
        if (baseUrl.isBlank()) null else remoteEmbeddingClient.embed(text, provider, apiKey, baseUrl, model)
    }

    /**
     * Semantic search over embedded segments. Returns segments whose embedding
     * is within [EmbeddingIndex.MIN_COSINE_SIMILARITY] cosine of [queryVector], ranked by
     * descending score.
     */
    suspend fun search(queryVector: FloatArray, maxResults: Int = 10): List<SegmentEmbeddingHit> =
        withContext(Dispatchers.IO) {
            ensureLoaded()
            if (queryVector.isEmpty() || embeddings.isEmpty()) return@withContext emptyList()
            if (dimensions > 0 && queryVector.size != dimensions) {
                Log.w(TAG, "Query dimension ${queryVector.size} != segment index dimension $dimensions")
                return@withContext emptyList()
            }
            embeddings.values.mapNotNull { entry ->
                val score = cosineSimilarity(queryVector, entry.vector)
                if (score >= EmbeddingIndex.MIN_COSINE_SIMILARITY) {
                    SegmentEmbeddingHit(entry.segmentId, entry.sourceId, score)
                } else {
                    null
                }
            }.sortedByDescending { it.score }.take(maxResults)
        }

    /** Remove embeddings for segments of a source that no longer exist. */
    suspend fun pruneForSource(sourceId: String, validSegmentIds: Set<String>) = withContext(Dispatchers.IO) {
        ensureLoaded()
        writeMutex.withLock {
            val stale = embeddings.keys.filter { id ->
                embeddings[id]?.sourceId == sourceId && id !in validSegmentIds
            }
            stale.forEach { embeddings.remove(it) }
            if (stale.isNotEmpty()) persist()
        }
    }

    fun size(): Int = embeddings.size

    fun getDimensions(): Int = dimensions
    fun getActiveFingerprint(): String? = activeFingerprint

    suspend fun clear() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            embeddings.clear()
            dimensions = 0
            activeFingerprint = null
            persist()
        }
    }

    // ── Persistence (segment-scoped, mirroring EmbeddingIndex) ─────────

    private fun ensureLoaded() {
        if (loaded) return
        val file = embeddingFile()
        if (file.exists()) {
            try {
                val wrapped = java.io.BufferedReader(
                    java.io.InputStreamReader(java.io.FileInputStream(file), Charsets.UTF_8),
                    32768
                ).use { reader ->
                    gson.fromJson(reader, PersistedSegmentEmbeddings::class.java)
                }
                // Phase 2: accept remote fingerprints ("remote-<provider>-<model>")
                // exactly like EmbeddingIndex.isKnownFingerprint. The old
                // on-device-only check treated remote-persisted vectors as
                // stale on every cold start → perpetual re-embedding loop.
                val fp = wrapped?.fingerprint
                val valid = fp == EmbeddingIndex.MODEL_FINGERPRINT ||
                        (fp != null && fp.startsWith("remote-"))
                if (!valid) {
                    // Either a legacy bare list (pre-fingerprint) or vectors from an
                    // older embedding model — both live in a stale vector space.
                    Log.i(TAG, "Stale segment embeddings (model fingerprint mismatch) — will re-embed")
                    embeddings.clear()
                    dimensions = 0
                    activeFingerprint = null
                } else {
                    embeddings.clear()
                    wrapped.entries.forEach { embeddings[it.segmentId] = it }
                    dimensions = embeddings.values.firstOrNull()?.vector?.size ?: 0
                    activeFingerprint = fp
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load segment embeddings: ${e.message}")
            }
        }
        loaded = true
    }

    private fun persist(fingerprint: String = EmbeddingIndex.MODEL_FINGERPRINT) {
        val file = embeddingFile()
        // Snapshot under no lock here — callers hold writeMutex for
        // mutate+persist sequences. Stream directly to disk via writeStream to avoid OOM.
        val data = PersistedSegmentEmbeddings(fingerprint, embeddings.values)
        com.noteflowai.app.data.AtomicJsonFile.writeStream(file) { writer ->
            gson.toJson(data, writer)
        }
    }

    private fun embeddingFile(): File =
        File(context.filesDir, "$DIR_NAME/$FILE_NAME")

    /** Cosine similarity between two equal-length vectors; 0 on mismatch/zero-norm. */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator > 0f) dot / denominator else 0f
    }
}
