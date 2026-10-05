package com.noteflowai.app.data.capture

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.noteflowai.app.data.logging.SafeLogger
import com.noteflowai.app.data.memory.model.ProcessingState
import com.noteflowai.app.data.memory.model.ProcessingStatus
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.pipeline.SourceProcessingPipeline

/**
 * Durable, OS-scheduled driver for the memory-layer processing pipeline (Phase 1).
 *
 * Runs as a unique WorkManager worker per source (see [CaptureJobScheduler]) and delegates the
 * actual stage work to the existing [SourceProcessingPipeline], which already persists per-stage
 * status and retries internally. This worker adds:
 *
 *  - resume-after-process-death: WorkManager re-runs the unique work if the process died mid-run;
 *  - retry/backoff: a [ProcessingState.FAILED_RETRYABLE] outcome → [Result.retry];
 *  - terminal outcome: [ProcessingState.COMPLETED] → [Result.success] (capture marked READY),
 *    [ProcessingState.FAILED_PERMANENT] → [Result.failure] (capture marked FAILED, note intact);
 *  - no main-thread work: `doWork` runs on WorkManager's background executor.
 *
 * Note: no raw content is logged here; only identifiers and status codes flow through [SafeLogger].
 */
class CaptureProcessingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "CaptureProcessingWorker"

        const val KEY_SOURCE_ID = "sourceId"
        const val KEY_SOURCE_TYPE = "sourceType"

        /**
         * Map a persisted [ProcessingStatus] to a WorkManager [Result]. Pure function so it is
         * unit-testable without the full WorkManager runtime.
         */
        internal fun outcomeFor(status: ProcessingStatus?, isStopped: Boolean): Result = when {
            isStopped -> Result.retry()
            status?.status == ProcessingState.COMPLETED -> Result.success()
            status?.status == ProcessingState.FAILED_PERMANENT -> Result.failure()
            else -> Result.retry()
        }
    }

    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE_ID)
        val sourceTypeName = inputData.getString(KEY_SOURCE_TYPE)
        if (sourceId == null || sourceTypeName == null) {
            SafeLogger.w(TAG, "worker_missing_input", mapOf("errorCode" to "MISSING_INPUT"))
            return Result.failure()
        }
        val sourceType = runCatching { SourceType.valueOf(sourceTypeName) }
            .getOrElse {
                SafeLogger.w(TAG, "worker_bad_source_type", mapOf("sourceType" to sourceTypeName))
                return Result.failure()
            }

        // Honor cancellation before starting any work. Retry (not failure):
        // an interrupted job must resume later, matching outcomeFor's
        // isStopped -> retry contract. Failure would strand the capture as
        // terminal FAILED.
        if (isStopped) {
            SafeLogger.d(TAG, "worker_cancelled_before_start", mapOf("sourceId" to sourceId))
            return Result.retry()
        }

        SafeLogger.i(
            TAG,
            "worker_started",
            mapOf("sourceId" to sourceId, "sourceType" to sourceTypeName, "attempt" to runAttemptCount)
        )
        val repository = RawCaptureRepository.getInstance(applicationContext)
        return try {
            repository.markProcessing(sourceId)

            val pipeline = SourceProcessingPipeline.getInstance(applicationContext)
            pipeline.processSource(sourceId, sourceType)

            if (isStopped) {
                SafeLogger.d(TAG, "worker_stopped_mid_run", mapOf("sourceId" to sourceId))
                return Result.retry()
            }

            val status = pipeline.getStatus(sourceId)
            when (status?.status) {
                ProcessingState.COMPLETED -> {
                    repository.markReady(sourceId)
                    SafeLogger.i(
                        TAG,
                        "worker_succeeded",
                        mapOf("sourceId" to sourceId, "stage" to status.currentStage?.name)
                    )
                }

                ProcessingState.FAILED_PERMANENT -> {
                    repository.markFailed(sourceId)
                    SafeLogger.e(
                        TAG,
                        "worker_permanent_failure",
                        mapOf("sourceId" to sourceId, "stage" to status.currentStage?.name)
                    )
                }

                else -> SafeLogger.w(
                    TAG,
                    "worker_retryable",
                    mapOf("sourceId" to sourceId, "stage" to status?.currentStage?.name)
                )
            }
            outcomeFor(status, isStopped)
        } catch (e: Exception) {
            if (isStopped) {
                SafeLogger.d(TAG, "worker_stopped_with_error", mapOf("sourceId" to sourceId))
                return Result.retry()
            }
            SafeLogger.w(
                TAG,
                "worker_exception",
                mapOf("sourceId" to sourceId, "errorCode" to (e::class.java.simpleName))
            )
            Result.retry()
        }
    }
}
