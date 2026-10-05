package com.noteflowai.app.data.capture

import android.content.Context
import com.noteflowai.app.data.memory.db.MemoryDatabaseModule
import com.noteflowai.app.data.memory.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Durable raw-capture store (Phase 1 of the Agentic Guide).
 *
 * Records each captured note/recording/document before any AI processing, so raw content survives
 * process death and is independent of the processing pipeline. Processing lifecycle is tracked by
 * the existing per-source [com.noteflowai.app.data.memory.model.ProcessingStatus]; this repository
 * keeps the coarse capture row (CAPTURED → PROCESSING → READY/FAILED) in sync.
 */
class RawCaptureRepository(context: Context) {

    private val dao: RawCaptureDao = MemoryDatabaseModule.provideRawCaptureDao(context)

    companion object {
        @Volatile
        private var INSTANCE: RawCaptureRepository? = null

        fun getInstance(context: Context): RawCaptureRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RawCaptureRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    suspend fun recordCapture(
        sourceId: String,
        sourceType: SourceType,
        title: String? = null,
        rawText: String? = null,
        audioUri: String? = null
    ) = withContext(Dispatchers.IO) {
        val existing = dao.getBySourceId(sourceId)
        // Unchanged re-save: touch nothing. The old blind upsert regressed
        // READY/FAILED rows back to CAPTURED on every save, re-queueing
        // finished captures for reprocessing.
        if (existing != null &&
            existing.title == title &&
            existing.rawText == rawText &&
            existing.audioUri == audioUri
        ) {
            return@withContext
        }
        // Never yank a row out from under an in-flight run; the scheduler
        // re-enqueues PROCESSING rows and picks up the updated content below.
        val status = if (existing?.status == CaptureStatus.PROCESSING) {
            CaptureStatus.PROCESSING
        } else {
            CaptureStatus.CAPTURED
        }
        dao.upsert(
            RawCapture(
                sourceId = sourceId,
                title = title,
                sourceType = sourceType,
                rawText = rawText,
                audioUri = audioUri,
                status = status
            )
        )
    }

    suspend fun markProcessing(sourceId: String) = withContext(Dispatchers.IO) {
        dao.updateStatus(sourceId, CaptureStatus.PROCESSING)
    }

    suspend fun markReady(sourceId: String) = withContext(Dispatchers.IO) {
        dao.updateStatus(sourceId, CaptureStatus.READY)
    }

    suspend fun markFailed(sourceId: String) = withContext(Dispatchers.IO) {
        dao.updateStatus(sourceId, CaptureStatus.FAILED)
    }

    suspend fun getBySourceId(sourceId: String): RawCapture? = withContext(Dispatchers.IO) {
        dao.getBySourceId(sourceId)
    }

    /** Captures still CAPTURED/PROCESSING — re-enqueued after app start. */
    suspend fun getIncomplete(): List<RawCapture> = withContext(Dispatchers.IO) {
        dao.getIncomplete()
    }

    /** Blocking variant for non-coroutine contexts (e.g. app-start recovery thread). */
    fun getIncompleteBlocking(): List<RawCapture> =
        kotlinx.coroutines.runBlocking(Dispatchers.IO) { dao.getIncomplete() }

    suspend fun deleteBySourceId(sourceId: String) = withContext(Dispatchers.IO) {
        dao.deleteBySourceId(sourceId)
    }
}