package com.noteflowai.app.data.search

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * Persists the BM25 search index to disk so cold starts skip full rebuilds.
 *
 * Stored in [Context.filesDir]/search_index/index.json alongside a small
 * metadata header (fingerprint, doc count) for fast staleness checks.
 */
object NoteIndexPersistence {

    private const val TAG = "NoteIndexPersistence"
    private const val DIR_NAME = "search_index"
    private const val FILE_NAME = "index.json"

    // Compact JSON: pretty-printing bloats the multi-MB index on every persist.
    private val gson: Gson = Gson()

    /** Serializable snapshot of the search index. */
    data class Snapshot(
        val fingerprint: String,
        val totalDocs: Int,
        val avgDocLength: Float,
        val idf: Map<String, Float>,
        val docs: List<SerializableDoc>
    )

    /** A single doc entry flattened for JSON serialization. */
    data class SerializableDoc(
        val fileName: String,
        val title: String,
        val category: String,
        val tags: List<String>,
        val preview: String,
        val fullText: String,
        val fileNameTokens: List<String>,
        val tagTokens: List<String>,
        val categoryTokens: List<String>,
        val previewTokens: List<String>,
        val contentTokens: List<String>,
        val fileNameTF: Map<String, Float>,
        val tagTF: Map<String, Float>,
        val categoryTF: Map<String, Float>,
        val previewTF: Map<String, Float>,
        val contentTF: Map<String, Float>,
        // Empty in pre-v9 cache files; the loader recomputes from TF maps.
        val allTokens: List<String> = emptyList(),
        val combinedLength: Int
    )

    /**
     * Attempt to load a previously-persisted index. Returns the snapshot
     * regardless of freshness — the caller compares [Snapshot.fingerprint]
     * against the freshly-computed one and rebuilds on mismatch. (The old
     * signature required the caller to pass the current fingerprint, which
     * is empty on cold start, so the cache never hit.)
     */
    fun load(context: Context): Snapshot? {
        val file = getIndexFile(context)
        if (!file.exists()) return null

        return try {
            val snapshot: Snapshot? = gson.fromJson(file.readText(), Snapshot::class.java)
            if (snapshot == null) {
                Log.w(TAG, "Corrupted index file — ignoring")
                null
            } else {
                Log.i(TAG, "Loaded index: ${snapshot.totalDocs} docs, ${snapshot.idf.size} terms")
                snapshot
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load index: ${e.message}")
            null
        }
    }

    /**
     * Persist the current index state to disk. Crash-safe: writes to a temp
     * file + atomic rename so a kill mid-write never truncates the index.
     */
    fun persist(context: Context, snapshot: Snapshot) {
        val file = getIndexFile(context)
        if (com.noteflowai.app.data.AtomicJsonFile.write(file, gson.toJson(snapshot))) {
            Log.i(TAG, "Persisted index: ${snapshot.totalDocs} docs, ${file.length()} bytes")
        }
    }

    /**
     * Delete the persisted index (e.g. on corruption or manual reset).
     */
    fun clear(context: Context) {
        val file = getIndexFile(context)
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "Cleared persisted index")
        }
    }

    private fun getIndexFile(context: Context): File {
        return File(context.filesDir, "$DIR_NAME/$FILE_NAME")
    }
}
