package com.noteflowai.app.data.memory.rebuild

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Schedules the durable [MemoryRebuildWorker] (Phase 4 of the Agentic Guide).
 *
 * A memory rebuild is a single unique job (`rebuild:memory`) so re-enqueuing from the Memory Hub
 * replaces any existing request rather than stacking parallel rebuilds. The worker itself is
 * resumable: it checks `isStopped` between notes and returns [androidx.work.ListenableWorker.Result.retry]
 * when interrupted, so WorkManager resumes it (even across process death) exactly like
 * [com.noteflowai.app.data.capture.CaptureProcessingWorker].
 */
object MemoryRebuildScheduler {

    private const val TAG = "MemoryRebuildScheduler"

    /** Unique work name for the whole-memory rebuild — one rebuild at a time. */
    fun uniqueName(): String = "rebuild:memory"

    /** Enqueue the durable rebuild. Existing in-progress work is REPLACEd, not stacked. */
    fun enqueue(context: Context) {
        val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<MemoryRebuildWorker>()
            .setInputData(workDataOf(MemoryRebuildWorker.KEY_REBUILD_START_TIME to System.currentTimeMillis()))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName(), ExistingWorkPolicy.REPLACE, request)
    }

    /** Cancel the running/quelled rebuild (e.g. from the Memory Hub). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName())
    }
}