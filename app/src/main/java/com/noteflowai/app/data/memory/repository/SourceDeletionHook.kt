package com.noteflowai.app.data.memory.repository

import android.content.Context
import android.util.Log
import com.noteflowai.app.data.memory.db.MemoryDatabaseModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles cascade deletion of derived data when a source is deleted.
 *
 * Call [onSourceDeleted] from NoteRepository.deleteNote, RecordingRepository.deleteRecording,
 * and any other deletion path to ensure SourceSegments and derived data
 * (memories, decisions, commitments, entities mentions, relations, citations,
 * timeline entries, review items, conflicts, processing status) are cleaned up.
 *
 * Deletion runs asynchronously to avoid blocking the caller. The actual delete
 * delegates to [MemoryRepository.deleteDerivedDataForSource], which runs all
 * table deletes atomically in a single Room transaction — children before
 * segments, so segment-subquery deletes cannot silently no-op.
 */
object SourceDeletionHook {

    private const val TAG = "SourceDeletionHook"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called when a source (note, recording, document, etc.) is deleted.
     * Removes all associated derived data.
     *
     * This method is non-blocking — it launches a coroutine to perform cleanup.
     */
    fun onSourceDeleted(context: Context, sourceId: String) {
        scope.launch {
            try {
                val repository = MemoryRepository(MemoryDatabaseModule.getDatabase(context))
                repository.deleteDerivedDataForSource(sourceId)
                // Phase 1 integrity: the JSON index files are NOT part of the
                // Room transaction — without this, deleted sources keep serving
                // BM25 + vector hits (ghost segments) until a manual rebuild.
                com.noteflowai.app.data.memory.pipeline.SegmentIndexService
                    .getInstance(context).removeSource(sourceId)
                com.noteflowai.app.data.memory.pipeline.SegmentEmbeddingService
                    .getInstance(context).pruneForSource(sourceId, emptySet())
                Log.i(TAG, "Deleted derived data for source: $sourceId")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cascade deletion for source: $sourceId", e)
            }
        }
    }
}
