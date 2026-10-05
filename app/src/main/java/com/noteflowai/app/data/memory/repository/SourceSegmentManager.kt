package com.noteflowai.app.data.memory.repository

import android.content.Context
import android.util.Log
import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.memory.adapter.DeepgramUtteranceAdapter
import com.noteflowai.app.data.memory.adapter.DocumentChunkAdapter
import com.noteflowai.app.data.memory.adapter.GeneratedNoteAdapter
import com.noteflowai.app.data.memory.adapter.NoteBlockAdapter
import com.noteflowai.app.data.memory.adapter.OcrBlockAdapter
import com.noteflowai.app.data.memory.adapter.WhisperSegmentAdapter
import com.noteflowai.app.data.memory.adapter.YouTubeSegmentAdapter
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.pipeline.SourceProcessingPipeline
import com.noteflowai.app.data.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Central manager for creating and managing SourceSegments.
 *
 * Provides non-blocking hook methods that can be called from existing code paths
 * (NoteRepository, RecordingRepository, etc.) to create SourceSegments when
 * content is saved or processed.
 *
 * All operations are behind the ENABLE_SOURCE_SEGMENTS feature flag.
 */
/** Result of a [SourceSegmentManager.rebuildFromNotes] run. */
data class NotesRebuildResult(
    val notesProcessed: Int,
    val notesWithSegments: Int
)

class SourceSegmentManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = SourceSegmentRepository(context)
    private val memoryRepository = MemoryRepository(context)
    private val pipeline = SourceProcessingPipeline.getInstance(context)

    /**
     * Per-source locks serializing segment delete→insert→process sequences.
     * The pipeline's in-flight set guards processing, but rapid saves used to
     * interleave one save's delete with another's insert, losing segments —
     * and the row the LLM just read could be deleted underneath it.
     */
    private val sourceLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(sourceId: String): Mutex =
        sourceLocks.getOrPut(sourceId) { Mutex() }

    /** Launch [block] serialized against other work for the same source. */
    private fun lockedLaunch(sourceId: String, block: suspend () -> Unit) {
        scope.launch {
            lockFor(sourceId).withLock { block() }
        }
    }

    companion object {
        private const val TAG = "SourceSegmentManager"

        @Volatile
        private var INSTANCE: SourceSegmentManager? = null

        fun getInstance(context: Context): SourceSegmentManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SourceSegmentManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Check if source segments are enabled.
     * Reads from SettingsManager DataStore — returns false by default.
     */
    private fun isEnabled(): Boolean {
        return try {
            SettingsManager.getInstance(context).enableSourceSegmentsBlocking
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Trigger the ingestion pipeline for a source. The pipeline itself gates
     * each stage on the relevant feature flags, so no pre-check is needed here.
     */
    private fun triggerPipeline(sourceId: String, sourceType: SourceType) {
        try {
            scope.launch {
                pipeline.processSource(sourceId, sourceType)
                Log.d(TAG, "Triggered pipeline for source type: $sourceType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger pipeline for source type: $sourceType", e)
        }
    }

    // -- Hook methods (call from existing code paths) --

    /**
     * Rebuild the memory layer for a set of existing notes (backfill).
     *
     * Mirrors [onNoteSaved] per note but awaits each note's pipeline run
     * sequentially, so a rebuild does not fire N concurrent LLM extraction
     * calls. All derived rows are wiped first (not just segments), making this
     * idempotent: a segments-only wipe would leave fresh segments with stale
     * derived rows pointing at deleted segment ids, or stack duplicates on
     * re-extraction.
     */
    suspend fun rebuildFromNotes(
        notes: List<NoteFile>,
        onProgress: (done: Int) -> Unit
    ): NotesRebuildResult {
        if (!isEnabled()) return NotesRebuildResult(0, 0)
        var withSegments = 0
        notes.forEachIndexed { index, note ->
            val created = lockFor(note.fileName).withLock {
                resetAndProcessNote(note)
            }
            if (created) withSegments++
            onProgress(index + 1)
        }
        return NotesRebuildResult(notes.size, withSegments)
    }

    /**
     * Wipe → adapt → insert → process for one note. Runs under the source
     * lock so concurrent saves/rebuilds cannot interleave.
     * @return true when segments were created.
     */
    private suspend fun resetAndProcessNote(note: NoteFile): Boolean {
        memoryRepository.resetSourceForRebuild(note.fileName, SourceType.NOTE)
        val segments = NoteBlockAdapter.adapt(note)
        if (segments.isNotEmpty()) {
            // Transactional (see replaceSourceSegments): the wipe above runs
            // in its own transaction, so the insert must be atomic too.
            repository.replaceSourceSegments(note.fileName, segments)
            pipeline.processSource(note.fileName, SourceType.NOTE)
            return true
        }
        return false
    }

    /**
     * Hook: called after a note is saved.
     * Creates SourceSegments from the note content.
     */
    fun onNoteSaved(note: NoteFile) {
        if (!isEnabled()) return
        lockedLaunch(note.fileName) {
            try {
                val segments = NoteBlockAdapter.adapt(note)
                // Delete+insert in one transaction: a crash between them used
                // to strand the source segment-less (recovery never rebuilds
                // hook-created segments).
                repository.replaceSourceSegments(note.fileName, segments)
                if (segments.isNotEmpty()) {
                    Log.i(TAG, "Created ${segments.size} source segments for note")
                    triggerPipeline(note.fileName, SourceType.NOTE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating source segments for note", e)
            }
        }
    }

    /**
     * Hook: called after a recording transcription completes and is saved.
     * Creates SourceSegments from the transcript text.
     */
    fun onTranscriptionSaved(
        sourceId: String,
        transcriptText: String,
        engine: String,
        language: String? = null
    ) {
        if (!isEnabled()) return
        lockedLaunch(sourceId) {
            try {
                // Adapted before the transactional replace below: without the
                // transaction every re-transcription risked torn deletes.
                val segments = when (engine) {
                    "deepgram" -> listOfNotNull(DeepgramUtteranceAdapter.adapt(sourceId, transcriptText, language))
                    else -> listOfNotNull(WhisperSegmentAdapter.adapt(sourceId, transcriptText, engine, language))
                }
                repository.replaceSourceSegments(sourceId, segments)
                if (segments.isNotEmpty()) {
                    Log.i(TAG, "Created ${segments.size} source segments for recording: $sourceId")
                    triggerPipeline(sourceId, SourceType.AUDIO)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating source segments for recording: $sourceId", e)
            }
        }
    }

    /**
     * Hook: called after a document is imported.
     * Creates SourceSegments from the extracted text.
     */
    fun onDocumentImported(
        sourceId: String,
        text: String,
        format: String,
        metadata: com.noteflowai.app.data.document.DocumentMetadata? = null
    ) {
        if (!isEnabled()) return
        lockedLaunch(sourceId) {
            try {
                val segments = DocumentChunkAdapter.adapt(sourceId, text, format, metadata)
                repository.replaceSourceSegments(sourceId, segments)
                if (segments.isNotEmpty()) {
                    Log.i(TAG, "Created ${segments.size} source segments for document: $sourceId")
                    // Pipeline source type follows the segments (PDF for PDFs):
                    // hardcoding DOCUMENT here desynced the status row from the
                    // segment rows and broke navigator routing + source filters.
                    triggerPipeline(sourceId, segments.first().sourceType)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating source segments for document: $sourceId", e)
            }
        }
    }

    /**
     * Hook: called after OCR text is extracted.
     * Creates SourceSegments from the OCR result.
     */
    fun onOcrCompleted(
        sourceId: String,
        text: String,
        language: String? = null,
        imageUri: String? = null
    ) {
        if (!isEnabled()) return
        lockedLaunch(sourceId) {
            try {
                val segment = OcrBlockAdapter.adapt(sourceId, text, language, imageUri)
                repository.replaceSourceSegments(sourceId, listOfNotNull(segment))
                if (segment != null) {
                    Log.i(TAG, "Created source segment for OCR: $sourceId")
                    triggerPipeline(sourceId, SourceType.OCR)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating source segment for OCR: $sourceId", e)
            }
        }
    }

    /**
     * Hook: called after a YouTube transcript is fetched.
     * Creates SourceSegments from the caption text.
     */
    fun onYouTubeTranscriptFetched(
        sourceId: String,
        text: String,
        videoUrl: String? = null,
        language: String? = null,
        videoTitle: String? = null
    ) {
        if (!isEnabled()) return
        lockedLaunch(sourceId) {
            try {
                val segments = YouTubeSegmentAdapter.adapt(sourceId, text, videoUrl, language, videoTitle)
                repository.replaceSourceSegments(sourceId, segments)
                if (segments.isNotEmpty()) {
                    Log.i(TAG, "Created ${segments.size} source segments for YouTube: $sourceId")
                    triggerPipeline(sourceId, SourceType.YOUTUBE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating source segments for YouTube: $sourceId", e)
            }
        }
    }

    /**
     * Hook: called after a generated note is created.
     * Creates SourceSegments marked as generated content.
     */
    fun onGeneratedNoteSaved(
        note: NoteFile,
        originatingSourceIds: List<String>? = null,
        generationModel: String? = null
    ) {
        if (!isEnabled()) return
        lockedLaunch(note.fileName) {
            try {
                // Delete previous segments for this note so re-generation is idempotent.
                val segment = GeneratedNoteAdapter.adapt(note, originatingSourceIds, generationModel)
                repository.replaceSourceSegments(note.fileName, listOfNotNull(segment))
                if (segment != null) {
                    Log.i(TAG, "Created source segment for generated note")
                    triggerPipeline(note.fileName, SourceType.GENERATED_NOTE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating source segment for generated note", e)
            }
        }
    }

    /**
     * Hook: called when a source is deleted.
     * Triggers cascade deletion of associated SourceSegments and drops the
     * pipeline status row so the source is not re-processed.
     */
    fun onSourceDeleted(sourceId: String) {
        if (!isEnabled()) return
        SourceDeletionHook.onSourceDeleted(context, sourceId)
        try {
            scope.launch { pipeline.deleteSource(sourceId) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete pipeline status for source", e)
        }
    }
}
