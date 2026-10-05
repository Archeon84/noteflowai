package com.noteflowai.app.data.digest

import android.content.Context
import android.util.Log
import java.io.File

/**
 * File-based storage for daily digests in filesDir/digest/.
 * Keeps the last 7 days.
 */
object DigestStorage {

    private const val TAG = "DigestStorage"
    private const val DIR_NAME = "digest"
    private const val MAX_DAYS = 7

    private fun getDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun save(context: Context, digest: DailyDigest) {
        val dir = getDir(context)
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(digest.generatedAt))
        val file = File(dir, "digest_$dateStr.json")
        file.writeText(digest.toJson())
        Log.i(TAG, "Saved digest for $dateStr")

        // Cleanup old digests
        cleanupOldDigests(dir)
    }

    fun loadLatest(context: Context): DailyDigest? {
        val dir = getDir(context)
        val files = dir.listFiles()
            ?.filter { it.name.startsWith("digest_") && it.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?: return null

        return files.firstOrNull()?.let {
            try {
                DailyDigest.fromJson(it.readText())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse digest: ${e.message}")
                null
            }
        }
    }

    fun loadAll(context: Context): List<DailyDigest> {
        val dir = getDir(context)
        return dir.listFiles()
            ?.filter { it.name.startsWith("digest_") && it.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.mapNotNull { file ->
                try {
                    DailyDigest.fromJson(file.readText())
                } catch (e: Exception) {
                    null
                }
            }
            ?: emptyList()
    }

    private fun cleanupOldDigests(dir: File) {
        val files = dir.listFiles()
            ?.filter { it.name.startsWith("digest_") }
            ?.sortedByDescending { it.name }
            ?: return

        if (files.size > MAX_DAYS) {
            files.drop(MAX_DAYS).forEach { it.delete() }
        }
    }
}
