package com.noteflowai.app.data.capture

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.noteflowai.app.data.logging.SafeLogger
import com.noteflowai.app.data.memory.model.SourceType
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Schedules durable [CaptureProcessingWorker] jobs (Phase 1 of the Agentic Guide).
 *
 * Each source gets exactly one unique work name (`processing:<sourceId>`), so re-enqueuing the same
 * source replaces the existing queued request — it can never run twice concurrently, satisfying the
 * guide's "unique work per note" and "avoid duplicate processing" requirements.
 */
object CaptureJobScheduler {

    private const val TAG = "CaptureJobScheduler"

    /** Unique work name derived from a source id, so the same source maps to the same work. */
    fun uniqueNameFor(sourceId: String): String = "processing:$sourceId"

    /**
     * Enqueue a single durable processing job for [sourceId].
     *
     * Uses [ExistingWorkPolicy.REPLACE] so a re-capture or re-enqueue simply re-queues the single
     * unique work; the pipeline's in-flight set and persisted stage state prevent double work.
     */
    suspend fun enqueue(context: Context, sourceId: String, sourceType: SourceType) {
        val request = buildRequest(sourceId, sourceType)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueNameFor(sourceId), ExistingWorkPolicy.REPLACE, request)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Re-enqueue every CAPTURED/PROCESSING capture after app start so interrupted work resumes
     * without relying on the in-process pipeline's recovery alone.
     *
     * Uses KEEP (not REPLACE): recovery must not yank in-flight runs or
     * stampede every capture at once — REPLACE per capture defeated the
     * pipeline's own retry backoff. Runs in a structured scope instead of a
     * raw Thread.
     */
    fun recoverPending(context: Context) {
        scope.launch {
            try {
                val incomplete = RawCaptureRepository.getInstance(context).getIncomplete()
                if (incomplete.isEmpty()) return@launch
                SafeLogger.i(TAG, "recovering_pending_captures", mapOf("itemCount" to incomplete.size))
                incomplete.forEach { capture ->
                    val request = buildRequest(capture.sourceId, capture.sourceType)
                    WorkManager.getInstance(context)
                        .enqueueUniqueWork(uniqueNameFor(capture.sourceId), ExistingWorkPolicy.KEEP, request)
                }
            } catch (t: Throwable) {
                // Throwable, not Exception: startup recovery must never take
                // down the app process — not even on linkage/initialization
                // Errors (e.g. store unavailable at boot). Logged loudly.
                SafeLogger.w(
                    TAG,
                    "recover_pending_failed",
                    mapOf("errorCode" to (t::class.java.simpleName))
                )
            }
        }
    }

    /** Cancel the unique work for [sourceId] (e.g. when the source is deleted). */
    fun cancel(context: Context, sourceId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueNameFor(sourceId))
    }

    private fun buildRequest(sourceId: String, sourceType: SourceType): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<CaptureProcessingWorker>()
            .setInputData(
                workDataOf(
                    CaptureProcessingWorker.KEY_SOURCE_ID to sourceId,
                    CaptureProcessingWorker.KEY_SOURCE_TYPE to sourceType.name
                )
            )
            // Exponential backoff for transient failures (default 10s, doubles each retry).
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
}