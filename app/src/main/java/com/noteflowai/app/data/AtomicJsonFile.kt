package com.noteflowai.app.data

import android.util.Log
import java.io.File

/**
 * Phase 1 integrity: crash-safe JSON persistence for the RAG index files.
 *
 * A kill between truncate and flush used to leave truncated JSON on disk,
 * forcing a full re-embed/re-index on next launch. Writing to `<name>.tmp`
 * + atomic rename means readers always see the old file or the new file,
 * never a partial one. A failed rename leaves the previous file intact.
 *
 * Writes are serialized process-wide (index persists are rare and
 * debounced; contention is negligible). In-memory map races are handled
 * separately by each store's own lock — this only guarantees file-level
 * atomicity.
 */
object AtomicJsonFile {

    private const val TAG = "AtomicJsonFile"

    @Synchronized
    fun write(file: File, text: String): Boolean {
        return writeStream(file) { writer -> writer.write(text) }
    }

    /**
     * Stream directly to `<name>.tmp` + atomic rename.
     * Prevents giant heap String allocations when serializing large models/indexes with Gson.
     */
    @Synchronized
    fun writeStream(file: File, block: (java.io.Writer) -> Unit): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            java.io.BufferedWriter(
                java.io.OutputStreamWriter(java.io.FileOutputStream(tmp), Charsets.UTF_8),
                32768
            ).use { writer ->
                block(writer)
                writer.flush()
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                Log.w(TAG, "Atomic rename failed for ${file.name} — previous file kept")
                false
            } else {
                // A stale tmp from a crashed write is overwritten next time;
                // delete defensively in case rename copied on some filesystems.
                if (tmp.exists()) tmp.delete()
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Atomic writeStream failed for ${file.name}: ${t.message}")
            false
        }
    }
}
