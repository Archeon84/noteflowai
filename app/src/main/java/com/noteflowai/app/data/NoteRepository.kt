package com.noteflowai.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
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

class NoteRepository(private val context: Context) {

    private val notesDir: File = File(context.filesDir, "notes").also { it.mkdirs() }
    private val sourceSegmentManager: SourceSegmentManager? by lazy {
        runCatching { SourceSegmentManager.getInstance(context) }.getOrNull()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\]"), "_")
                   .replace("..", "_")
                   .take(200)
    }

    private fun isWithinDirectory(file: File, dir: File): Boolean {
        return file.canonicalPath.startsWith(dir.canonicalPath)
    }

    private val _notesFlow = MutableStateFlow<List<NoteFile>>(emptyList())
    val notesFlow: StateFlow<List<NoteFile>> = _notesFlow.asStateFlow()

    /** Call from a coroutine after construction — loads note metadata off the main thread. */
    suspend fun init() {
        withContext(Dispatchers.IO) {
            refreshNotesList()
        }
    }

    fun refreshNotesList() {
        // Support both old .txt and new .json for transition
        val files = notesDir.listFiles()?.filter { it.extension == "txt" || it.extension == "json" }?.toTypedArray() ?: emptyArray()
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        _notesFlow.value = files.map { file ->
            val epoch = file.lastModified()
            if (file.extension == "json") {
                try {
                    val json = file.readText()
                    try {
                        val note = gson.fromJson(json, NoteFile::class.java).sanitized()
                        val createdEpoch = if (note.createdAtEpoch > 0) note.createdAtEpoch else epoch
                        val createdStr = if (note.createdAt.isNotBlank()) note.createdAt else dateFormat.format(Date(createdEpoch))
                        note.copy(
                            lastModified = dateFormat.format(Date(epoch)),
                            lastModifiedEpoch = epoch,
                            createdAt = createdStr,
                            createdAtEpoch = createdEpoch
                        )
                    } catch (e: Exception) {
                        val obj = JsonParser.parseString(json).asJsonObject
                        val content = obj.get("content")?.asString ?: ""
                        val category = obj.get("category")?.asString ?: "Uncategorized"
                        val createdEpoch = obj.get("createdAtEpoch")?.asLong ?: epoch
                        val createdStr = obj.get("createdAt")?.asString ?: dateFormat.format(Date(createdEpoch))
                        NoteFile(
                            fileName = file.name,
                            lastModified = dateFormat.format(Date(epoch)),
                            preview = content.take(100).replace("\n", " ").trim(),
                            content = "",
                            category = category,
                            pinned = obj.get("pinned")?.asBoolean ?: false,
                            tags = try {
                                obj.get("tags")?.asJsonArray?.map { it.asString } ?: emptyList()
                            } catch (_: Exception) { emptyList() },
                            lastModifiedEpoch = epoch,
                            createdAt = createdStr,
                            createdAtEpoch = createdEpoch
                        )
                    }
                } catch (e: Exception) {
                    NoteFile(
                        fileName = file.name,
                        lastModified = "Error",
                        preview = "Error reading file",
                        lastModifiedEpoch = epoch,
                        createdAt = dateFormat.format(Date(epoch)),
                        createdAtEpoch = epoch
                    )
                }
            } else {
                val content = file.readText()
                NoteFile(
                    fileName = file.name,
                    lastModified = dateFormat.format(Date(epoch)),
                    preview = content.take(100).replace("\n", " ").trim(),
                    content = "",
                    lastModifiedEpoch = epoch,
                    createdAt = dateFormat.format(Date(epoch)),
                    createdAtEpoch = epoch
                )
            }
        }.sortedWith(
            compareByDescending<NoteFile> { it.pinned }.thenByDescending { it.lastModifiedEpoch }
        )
    }

    /**
     * Return every note with its full content loaded, for backfill-style jobs
     * (e.g. rebuilding the memory layer for notes saved before memory flags
     * were enabled). Reuses [refreshNotesList] parsing and falls back to
     * [readNote] when a note's parsed content is blank.
     */
    suspend fun getAllNotesWithContent(): List<NoteFile> = withContext(Dispatchers.IO) {
        refreshNotesList()
        _notesFlow.value.map { note ->
            if (note.content.isBlank()) note.copy(content = readNote(note.fileName)) else note
        }
    }

    /** Apply a sorted order to the current list. */
    private fun sortedList(list: List<NoteFile>): List<NoteFile> = list.sortedWith(
        compareByDescending<NoteFile> { it.pinned }.thenByDescending { it.lastModifiedEpoch }
    )

    /** Replace a single note in the in-memory list (by fileName). */
    private fun updateNoteInList(updated: NoteFile) {
        _notesFlow.value = sortedList(
            _notesFlow.value.map { if (it.fileName == updated.fileName) updated else it }
        )
    }

    /** Remove a single note from the in-memory list. */
    private fun removeNoteFromList(fileName: String) {
        _notesFlow.value = _notesFlow.value.filter { it.fileName != fileName }
    }

    /**
     * Add a note to the in-memory list, replacing any existing entry with the
     * same fileName. A plain append duplicated re-saved notes, which crashed
     * LazyColumn with a duplicate-key IllegalArgumentException on next layout.
     */
    private fun addNoteToList(note: NoteFile) {
        _notesFlow.value = sortedList(_notesFlow.value.filter { it.fileName != note.fileName } + note)
    }

    suspend fun saveNote(
        fileName: String,
        content: String,
        category: String = "All",
        tags: List<String> = emptyList(),
        pinned: Boolean = false
    ) {
        withContext(Dispatchers.IO) {
            val note = persistNote(fileName, content, category, tags, pinned) ?: return@withContext

            // Personal Memory Layer: create source segments (non-blocking, behind feature flag)
            sourceSegmentManager?.onNoteSaved(note)

            // Phase 1: durable raw capture + WorkManager processing job (non-blocking).
            recordCaptureAndSchedule(note.fileName, note)
        }
    }

    /**
     * Save a note that was generated by AI (e.g. the chat create-note tool). Persists the note
     * exactly like [saveNote] but routes segment creation and durable capture through the
     * GENERATED_NOTE path (derived content, not original) instead of NOTE.
     */
    suspend fun saveGeneratedNote(
        fileName: String,
        content: String,
        category: String = "All",
        originatingSourceIds: List<String>? = null,
        generationModel: String? = null
    ) {
        withContext(Dispatchers.IO) {
            val note = persistNote(fileName, content, category) ?: return@withContext

            // Personal Memory Layer: GENERATED_NOTE segments + pipeline trigger (non-blocking).
            sourceSegmentManager?.onGeneratedNoteSaved(note, originatingSourceIds, generationModel)

            // Phase 1 extension: durable raw capture + WorkManager processing job.
            recordCaptureAndSchedule(note.fileName, note, SourceType.GENERATED_NOTE)
        }
    }

    /** Write a note file and update the in-memory list. Returns the persisted NoteFile or null. */
    private fun persistNote(
        fileName: String,
        content: String,
        category: String,
        tags: List<String> = emptyList(),
        pinned: Boolean = false
    ): NoteFile? {
        val safeName = sanitizeFileName(fileName)
        val jsonName = if (safeName.endsWith(".txt")) safeName.replace(".txt", ".json") else if (safeName.endsWith(".json")) safeName else "$safeName.json"
        val file = File(notesDir, jsonName)
        if (!isWithinDirectory(file, notesDir)) return null

        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        val now = System.currentTimeMillis()

        var existingCreatedAt = ""
        var existingCreatedAtEpoch = 0L
        var effectiveTags = tags
        var effectivePinned = pinned
        var effectiveCategory = category

        if (file.exists() && file.extension == "json") {
            try {
                val existingNote = gson.fromJson(file.readText(), NoteFile::class.java)?.sanitized()
                if (existingNote != null) {
                    if (existingNote.createdAtEpoch > 0) {
                        existingCreatedAtEpoch = existingNote.createdAtEpoch
                    }
                    if (existingNote.createdAt.isNotBlank()) {
                        existingCreatedAt = existingNote.createdAt
                    }
                    if (tags.isEmpty() && existingNote.tags.isNotEmpty()) {
                        effectiveTags = existingNote.tags
                    }
                    if (!pinned && existingNote.pinned) {
                        effectivePinned = existingNote.pinned
                    }
                    if (category == "All" && existingNote.category.isNotBlank() && existingNote.category != "All") {
                        effectiveCategory = existingNote.category
                    }
                }
            } catch (_: Exception) {}
        }

        if (existingCreatedAtEpoch == 0L) {
            existingCreatedAtEpoch = now
            existingCreatedAt = dateFormat.format(Date(now))
        } else if (existingCreatedAt.isBlank()) {
            existingCreatedAt = dateFormat.format(Date(existingCreatedAtEpoch))
        }

        val note = NoteFile(
            fileName = jsonName,
            lastModified = dateFormat.format(Date(now)),
            preview = content.take(100).replace("\n", " ").trim(),
            content = content,
            category = effectiveCategory,
            pinned = effectivePinned,
            tags = effectiveTags.distinctBy { it.lowercase() },
            lastModifiedEpoch = now,
            createdAt = existingCreatedAt,
            createdAtEpoch = existingCreatedAtEpoch
        )
        file.writeText(gson.toJson(note))

        // Delete old .txt if it exists
        val oldTxt = File(notesDir, fileName.removeSuffix(".json") + ".txt")
        if (oldTxt.exists()) oldTxt.delete()

        // Update in-memory list instead of full disk re-read
        val epoch = file.lastModified()
        addNoteToList(note.copy(lastModified = dateFormat.format(Date(epoch)), lastModifiedEpoch = epoch))
        return note
    }

    /**
     * Phase 1 durable capture: record the raw note and enqueue the processing job behind the
     * source-segments feature flag. Fire-and-forget so control returns immediately after save.
     */
    private fun recordCaptureAndSchedule(
        fileName: String,
        note: NoteFile,
        sourceType: SourceType = SourceType.NOTE
    ) {
        scope.launch {
            try {
                if (!SettingsManager.getInstance(context).enableSourceSegments.first()) return@launch
                val captureRepo = RawCaptureRepository.getInstance(context)
                val scheduler = CaptureJobScheduler
                captureRepo.recordCapture(
                    sourceId = fileName,
                    sourceType = sourceType,
                    title = note.fileName,
                    rawText = note.content
                )
                scheduler.enqueue(context, fileName, sourceType)
            } catch (e: Exception) {
                // Capture recording is best-effort; a failure must not block saving the note.
            }
        }
    }

    suspend fun updateNoteCategory(fileName: String, category: String) {
        withContext(Dispatchers.IO) {
            val file = File(notesDir, sanitizeFileName(fileName))
            if (!isWithinDirectory(file, notesDir)) return@withContext
            if (file.exists() && file.extension == "json") {
                val note = gson.fromJson(file.readText(), NoteFile::class.java).sanitized()
                val updated = note.copy(category = category)
                file.writeText(gson.toJson(updated))
                updateNoteInList(updated)
            }
        }
    }

    suspend fun readNote(fileName: String): String {
        return withContext(Dispatchers.IO) {
            val file = File(notesDir, sanitizeFileName(fileName))
            if (!isWithinDirectory(file, notesDir)) return@withContext ""
            if (!file.exists()) return@withContext ""
            if (file.extension == "json") {
                try {
                    gson.fromJson(file.readText(), NoteFile::class.java).sanitized().content
                } catch (e: Exception) { "" }
            } else file.readText()
        }
    }

    /**
     * Returns the [NoteFile] for [fileName], or null if not found.
     * Checks the in-memory flow first to avoid a disk read, then falls back to
     * reading the file directly (used by SourceProcessingPipeline self-healing).
     */
    suspend fun getNoteFile(fileName: String): NoteFile? = withContext(Dispatchers.IO) {
        _notesFlow.value.firstOrNull { it.fileName == fileName }?.let { cached ->
            // Ensure content is populated (metadata-only entries have blank content)
            if (cached.content.isNotBlank()) return@withContext cached
        }
        val file = File(notesDir, sanitizeFileName(fileName))
        if (!isWithinDirectory(file, notesDir) || !file.exists()) return@withContext null
        return@withContext try {
            if (file.extension == "json") {
                gson.fromJson(file.readText(), NoteFile::class.java)?.sanitized()
            } else {
                val content = file.readText()
                NoteFile(fileName = fileName, lastModified = "", preview = content.take(200), content = content)
            }
        } catch (e: Exception) { null }
    }

    suspend fun deleteNote(fileName: String) {
        withContext(Dispatchers.IO) {
            val file = File(notesDir, sanitizeFileName(fileName))
            if (!isWithinDirectory(file, notesDir)) return@withContext
            file.delete()
            removeNoteFromList(fileName)

            // Personal Memory Layer: cascade delete source segments (non-blocking, behind feature flag)
            sourceSegmentManager?.onSourceDeleted(fileName)

            // Phase 1: cancel any pending processing job and drop the raw capture row.
            CaptureJobScheduler.cancel(context, fileName)
            scope.launch {
                runCatching { RawCaptureRepository.getInstance(context).deleteBySourceId(fileName) }
            }
        }
    }

    suspend fun renameNote(oldName: String, newName: String) {
        withContext(Dispatchers.IO) {
            val oldFile = File(notesDir, sanitizeFileName(oldName))
            val cleanNewName = if (newName.endsWith(".json")) sanitizeFileName(newName) else "${sanitizeFileName(newName)}.json"
            val newFile = File(notesDir, cleanNewName)
            if (!isWithinDirectory(oldFile, notesDir) || !isWithinDirectory(newFile, notesDir)) return@withContext

            if (oldFile.exists() && !newFile.exists()) {
                if (oldFile.extension == "json") {
                    val note = gson.fromJson(oldFile.readText(), NoteFile::class.java).sanitized()
                    val updated = note.copy(fileName = cleanNewName)
                    newFile.writeText(gson.toJson(updated))
                    oldFile.delete()
                    // Update in-memory list: remove old, add updated
                    removeNoteFromList(oldName)
                    addNoteToList(updated)
                } else {
                    oldFile.renameTo(newFile)
                    refreshNotesList() // Fallback for .txt files
                }
            }
        }
    }

    suspend fun togglePin(fileName: String) {
        withContext(Dispatchers.IO) {
            val file = File(notesDir, sanitizeFileName(fileName))
            if (!isWithinDirectory(file, notesDir)) return@withContext
            if (file.exists() && file.extension == "json") {
                val note = gson.fromJson(file.readText(), NoteFile::class.java).sanitized()
                val updated = note.copy(pinned = !note.pinned)
                file.writeText(gson.toJson(updated))
                updateNoteInList(updated)
            }
        }
    }

    suspend fun updateNoteTags(fileName: String, tags: List<String>) {
        withContext(Dispatchers.IO) {
            val file = File(notesDir, sanitizeFileName(fileName))
            if (!isWithinDirectory(file, notesDir)) return@withContext
            if (file.exists() && file.extension == "json") {
                val note = gson.fromJson(file.readText(), NoteFile::class.java).sanitized()
                val updated = note.copy(tags = tags.distinctBy { it.lowercase() })
                file.writeText(gson.toJson(updated))
                updateNoteInList(updated)
            }
        }
    }
}
