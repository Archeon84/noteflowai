package com.noteflowai.app.data

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resilient model-file downloader shared by NLLB, Whisper and Qwen3 downloads.
 *
 * Guarantees:
 *  - Follows HuggingFace CDN redirects (incl. the XET bridge URLs).
 *  - Verifies the number of bytes written against the server-reported Content-Length.
 *  - Retries on truncation / connection drop (the failure mode that produced
 *    silently-corrupt models in the past).
 *  - Uses an infinite read timeout so large (1GB+) transfers over a slow CDN are
 *    never killed by an idle socket timeout mid-stream.
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val BUFFER_SIZE = 65536
    private const val MAX_ATTEMPTS = 3

    /**
     * Downloads [url] to [destFile].
     * @param onProgress called with bytes downloaded so far (may briefly exceed
     *        [expectedSize] if the server under-reports; callers should clamp).
     * @throws Exception if the file cannot be fetched at the expected size after retries.
     */
    fun download(
        url: String,
        destFile: File,
        onProgress: (Long) -> Unit = {}
    ) {
        // Local-Only Mode blocks model downloads (throws before any traffic).
        com.noteflowai.app.data.NetworkModule.requireNetworkAllowed(url)
        val expected = getContentLength(url)
        var lastError: Exception? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            destFile.delete()
            try {
                streamDownload(url, destFile, expected, onProgress)
                if (expected <= 0 || destFile.length() == expected) {
                    Log.i(TAG, "Downloaded ${destFile.name}: ${destFile.length()} bytes (expected $expected)")
                    return
                }
                lastError = Exception(
                    "Size mismatch for ${destFile.name}: ${destFile.length()}/$expected bytes"
                )
                Log.w(TAG, "${lastError.message} (attempt $attempt/$MAX_ATTEMPTS)")
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Attempt $attempt/$MAX_ATTEMPTS failed for ${destFile.name}: ${e.message}")
            }
            destFile.delete()
        }
        throw lastError ?: Exception("Failed to download ${destFile.name}")
    }

    /** HEAD request to read the server-reported Content-Length (0 if unknown). */
    fun getContentLength(urlString: String): Long {
        return try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            val len = conn.contentLengthLong
            conn.disconnect()
            if (code == HttpURLConnection.HTTP_OK && len > 0) len else 0L
        } catch (e: Exception) {
            Log.w(TAG, "getContentLength failed for $urlString: ${e.message}")
            0L
        }
    }

    private fun streamDownload(
        urlString: String,
        destFile: File,
        expected: Long,
        onProgress: (Long) -> Unit
    ) {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        // No idle timeout: large models over a slow CDN can stall for minutes.
        conn.readTimeout = 0
        conn.instanceFollowRedirects = true
        conn.connect()

        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw Exception("HTTP $code downloading $urlString")
        }

        val input = conn.inputStream
        val output = FileOutputStream(destFile)
        val buffer = ByteArray(BUFFER_SIZE)
        var read: Int
        var total = 0L

        try {
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                total += read
                onProgress(total)
            }
            output.flush()
        } finally {
            try { output.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
            conn.disconnect()
        }

        if (expected > 0 && total != expected) {
            throw Exception("Incomplete download for ${destFile.name}: $total/$expected bytes")
        }
    }
}
