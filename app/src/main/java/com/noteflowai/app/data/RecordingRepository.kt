package com.noteflowai.app.data

import android.content.Context
import com.noteflowai.app.data.capture.CaptureJobScheduler
import com.noteflowai.app.data.capture.RawCaptureRepository
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.SourceSegmentManager
import com.noteflowai.app.data.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingRepository(private val context: Context) {

    private val recordingsDir = File(context.filesDir, "recordings").also { it.mkdirs() }
    private val sourceSegmentManager by lazy { SourceSegmentManager.getInstance(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\]"), "_")
                   .replace("..", "_")
                   .take(200)
    }

    private fun isWithinDirectory(file: File, dir: File): Boolean {
        return file.canonicalPath.startsWith(dir.canonicalPath)
    }

    private val _recordingsFlow = MutableStateFlow<List<AudioRecording>>(emptyList())
    val recordingsFlow: StateFlow<List<AudioRecording>> = _recordingsFlow.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val files = recordingsDir.listFiles()?.filter { it.extension == "wav" }?.toTypedArray() ?: emptyArray()
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        _recordingsFlow.value = files.sortedByDescending { it.lastModified() }.map { file ->
            val duration = calculateWavDuration(file)
            val epoch = file.lastModified()
            AudioRecording(
                fileName = file.name,
                filePath = file.absolutePath,
                lastModified = dateFormat.format(Date(epoch)),
                fileSize = file.length(),
                durationSeconds = duration,
                lastModifiedEpoch = epoch
            )
        }
    }

    private fun calculateWavDuration(file: File): Int {
        // Try MediaExtractor first (most reliable)
        try {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            if (extractor.trackCount > 0) {
                val durationUs = extractor.getTrackFormat(0).getLong(android.media.MediaFormat.KEY_DURATION)
                extractor.release()
                if (durationUs > 0) return (durationUs / 1_000_000).toInt()
            }
            extractor.release()
        } catch (_: Exception) {}

        // Fallback: parse WAV header manually
        return try {
            if (file.length() < 44) return 0
            val raf = java.io.RandomAccessFile(file, "r")
            raf.seek(12)
            var byteRate = 0
            var dataSize = 0
            var foundData = false
            val headerBuf = ByteArray(4)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(headerBuf)
                val chunkId = String(headerBuf)
                val chunkSize = raf.readInt()
                if (chunkId == "fmt ") {
                    val fmtBuf = ByteArray(16)
                    raf.readFully(fmtBuf)
                    byteRate = java.nio.ByteBuffer.wrap(fmtBuf, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    if (chunkSize > 16) raf.seek(raf.filePointer + (chunkSize - 16))
                } else if (chunkId == "data") {
                    dataSize = chunkSize
                    foundData = true
                    break
                } else {
                    raf.seek(raf.filePointer + chunkSize)
                }
            }
            raf.close()
            if (foundData && byteRate > 0) (dataSize.toDouble() / byteRate).toInt() else 0
        } catch (e: Exception) {
            0
        }
    }

    /** Remove a single recording from the in-memory list. */
    private fun removeRecordingFromList(fileName: String) {
        _recordingsFlow.value = _recordingsFlow.value.filter { it.fileName != fileName }
    }

    /**
     * Add a recording to the in-memory list, replacing any existing entry with
     * the same fileName (saveRecording overwrites files in place — a plain
     * append would duplicate re-saved recordings and crash LazyColumn).
     */
    private fun addRecordingToList(recording: AudioRecording) {
        _recordingsFlow.value = listOf(recording) +
                _recordingsFlow.value.filter { it.fileName != recording.fileName }
    }

    suspend fun saveRecording(sourceFile: File, desiredFileName: String) {
        withContext(Dispatchers.IO) {
            val safeName = sanitizeFileName(desiredFileName)
            val dest = File(recordingsDir, safeName)
            if (!isWithinDirectory(dest, recordingsDir)) return@withContext
            sourceFile.copyTo(dest, overwrite = true)
            // Add to in-memory list instead of full disk re-read
            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            val epoch = dest.lastModified()
            addRecordingToList(AudioRecording(
                fileName = dest.name,
                filePath = dest.absolutePath,
                lastModified = dateFormat.format(Date(epoch)),
                fileSize = dest.length(),
                durationSeconds = calculateWavDuration(dest),
                lastModifiedEpoch = epoch
            ))

            // Phase 1: durable raw capture + WorkManager processing job (non-blocking).
            recordCaptureAndSchedule(dest)
        }
    }

    /**
     * Phase 1 durable capture: record the raw recording and enqueue the processing job behind the
     * source-segments feature flag. Fire-and-forget so control returns immediately after save.
     */
    private fun recordCaptureAndSchedule(dest: File) {
        val fileName = dest.name
        scope.launch {
            try {
                if (!SettingsManager.getInstance(context).enableSourceSegments.first()) return@launch
                val captureRepo = RawCaptureRepository.getInstance(context)
                captureRepo.recordCapture(
                    sourceId = fileName,
                    sourceType = SourceType.AUDIO,
                    title = fileName,
                    audioUri = dest.absolutePath
                )
                CaptureJobScheduler.enqueue(context, fileName, SourceType.AUDIO)
            } catch (e: Exception) {
                // Capture recording is best-effort; a failure must not block saving the recording.
            }
        }
    }

    suspend fun deleteRecording(fileName: String) {
        withContext(Dispatchers.IO) {
            val file = File(recordingsDir, sanitizeFileName(fileName))
            if (!isWithinDirectory(file, recordingsDir)) return@withContext
            file.delete()
            removeRecordingFromList(fileName)

            // Personal Memory Layer: cascade delete source segments (non-blocking, behind feature flag)
            sourceSegmentManager.onSourceDeleted(fileName)

            // Phase 1: cancel any pending processing job and drop the raw capture row.
            CaptureJobScheduler.cancel(context, fileName)
            scope.launch {
                runCatching { RawCaptureRepository.getInstance(context).deleteBySourceId(fileName) }
            }
        }
    }

    suspend fun renameRecording(oldName: String, newName: String) {
        withContext(Dispatchers.IO) {
            val cleanNewName = if (newName.endsWith(".wav")) sanitizeFileName(newName) else "${sanitizeFileName(newName)}.wav"
            val oldFile = File(recordingsDir, sanitizeFileName(oldName))
            val newFile = File(recordingsDir, cleanNewName)
            if (!isWithinDirectory(oldFile, recordingsDir) || !isWithinDirectory(newFile, recordingsDir)) return@withContext

            if (oldFile.exists() && !newFile.exists()) {
                oldFile.renameTo(newFile)
                // Remove old, add updated
                removeRecordingFromList(oldName)
                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                val epoch = newFile.lastModified()
                addRecordingToList(AudioRecording(
                    fileName = newFile.name,
                    filePath = newFile.absolutePath,
                    lastModified = dateFormat.format(Date(epoch)),
                    fileSize = newFile.length(),
                    durationSeconds = calculateWavDuration(newFile),
                    lastModifiedEpoch = epoch
                ))
            }
        }
    }
}