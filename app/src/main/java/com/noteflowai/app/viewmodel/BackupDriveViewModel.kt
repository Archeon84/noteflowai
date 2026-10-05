package com.noteflowai.app.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noteflowai.app.data.BackupManager
import com.noteflowai.app.data.GoogleDriveManager
import com.noteflowai.app.data.NoteRepository
import com.noteflowai.app.data.RecordingRepository
import com.noteflowai.app.data.chat.ChatRepository
import com.noteflowai.app.data.settings.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BackupDriveViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "BackupDriveVM"
    }

    private val settingsManager = SettingsManager.getInstance(application)
    private val noteRepository = NoteRepository(application)
    private val recordingRepository = RecordingRepository(application)
    private val chatRepository = ChatRepository(application)
    private val backupManager = BackupManager(application)
    private val googleDriveManager = GoogleDriveManager(application)

    // Backup state
    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    private val _backupMessage = MutableStateFlow("")
    val backupMessage: StateFlow<String> = _backupMessage.asStateFlow()

    // Backup options
    private val _includeRecordings = MutableStateFlow(true)
    val includeRecordings: StateFlow<Boolean> = _includeRecordings.asStateFlow()

    // Google Drive state
    private val _isGoogleSignedIn = MutableStateFlow(googleDriveManager.isSignedIn())
    val isGoogleSignedIn: StateFlow<Boolean> = _isGoogleSignedIn.asStateFlow()

    private val _googleEmail = MutableStateFlow(googleDriveManager.getSignedInEmail())
    val googleEmail: StateFlow<String?> = _googleEmail.asStateFlow()

    fun setIncludeRecordings(value: Boolean) { _includeRecordings.value = value }

    fun backupToLocal(password: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBackingUp.value = true
            _backupMessage.value = ""
            try {
                val result = backupManager.createBackup(
                    settingsManager, noteRepository, recordingRepository, chatRepository,
                    includeRecordings = _includeRecordings.value,
                    password = password
                )
                _backupMessage.value = result.message
            } catch (e: Exception) {
                _backupMessage.value = "Backup failed: ${e.message}"
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    fun restoreFromLocal(overwrite: Boolean = true, password: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBackingUp.value = true
            _backupMessage.value = ""
            try {
                val backupDir = File(getApplication<Application>().getExternalFilesDir(null), "NoteFlowAI_Backup")
                val latestBackup = backupDir.listFiles()?.filter { it.name.endsWith(".zip") || it.name.endsWith(".enc.zip") }
                    ?.maxByOrNull { it.lastModified() }

                if (latestBackup == null) {
                    _backupMessage.value = "No backup file found"
                    return@launch
                }

                val result = backupManager.restoreBackup(latestBackup, settingsManager, noteRepository, recordingRepository, chatRepository, overwrite, password)
                _backupMessage.value = result.message
            } catch (e: Exception) {
                _backupMessage.value = "Restore failed: ${e.message}"
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    fun restoreFromBackupFile(file: File, overwrite: Boolean = true, password: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBackingUp.value = true
            _backupMessage.value = ""
            try {
                val result = backupManager.restoreBackup(file, settingsManager, noteRepository, recordingRepository, chatRepository, overwrite, password)
                _backupMessage.value = result.message
            } catch (e: Exception) {
                _backupMessage.value = "Restore failed: ${e.message}"
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    fun clearBackupMessage() { _backupMessage.value = "" }

    fun handleGoogleSignInResult(data: Intent?) {
        val success = googleDriveManager.handleSignInResult(data)
        _isGoogleSignedIn.value = success
        _googleEmail.value = googleDriveManager.getSignedInEmail()
        if (success) _backupMessage.value = "Signed in to Google Drive"
    }

    fun googleSignOut() {
        googleDriveManager.signOut()
        _isGoogleSignedIn.value = false
        _googleEmail.value = null
        _backupMessage.value = "Signed out of Google Drive"
    }

    fun getGoogleSignInIntent(): Intent = googleDriveManager.getSignInIntent()

    fun backupToGoogleDrive(password: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBackingUp.value = true
            _backupMessage.value = "Creating backup..."
            try {
                if (com.noteflowai.app.data.NetworkModule.isLocalOnly()) {
                    _backupMessage.value = "Blocked by Local-Only Mode: cloud upload disabled."
                    return@launch
                }
                val result = backupManager.createBackup(
                    settingsManager, noteRepository, recordingRepository, chatRepository,
                    includeRecordings = _includeRecordings.value,
                    password = password
                )
                if (!result.success || result.file == null) {
                    _backupMessage.value = result.message
                    return@launch
                }

                _backupMessage.value = "Uploading to Google Drive..."
                val driveResult = googleDriveManager.uploadBackup(result.file!!)
                _backupMessage.value = driveResult.message
            } catch (e: Exception) {
                _backupMessage.value = "Google Drive backup failed: ${e.message}"
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    fun restoreFromGoogleDrive(password: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBackingUp.value = true
            _backupMessage.value = "Downloading from Google Drive..."
            try {
                if (com.noteflowai.app.data.NetworkModule.isLocalOnly()) {
                    _backupMessage.value = "Blocked by Local-Only Mode: cloud download disabled."
                    return@launch
                }
                val driveResult = googleDriveManager.downloadLatestBackup()
                if (!driveResult.success || driveResult.file == null) {
                    _backupMessage.value = driveResult.message
                    return@launch
                }

                _backupMessage.value = "Restoring..."
                val restoreResult = backupManager.restoreBackup(driveResult.file, settingsManager, noteRepository, recordingRepository, chatRepository, true, password)
                _backupMessage.value = restoreResult.message
            } catch (e: Exception) {
                _backupMessage.value = "Google Drive restore failed: ${e.message}"
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    fun exportAllNotes() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                noteRepository.init()
                val notes = noteRepository.notesFlow.value
                if (notes.isEmpty()) {
                    _backupMessage.value = "No notes to export"
                    return@launch
                }
                val exportDir = File(getApplication<Application>().getExternalFilesDir(null), "NoteFlowAI_Backup")
                exportDir.mkdirs()
                var exported = 0
                for (note in notes) {
                    val content = noteRepository.readNote(note.fileName)
                    val outFile = File(exportDir, note.fileName)
                    outFile.writeText(content)
                    exported++
                }
                withContext(Dispatchers.Main) {
                    _backupMessage.value = "Exported $exported notes to ${exportDir.absolutePath}"
                }
            } catch (e: Exception) {
                _backupMessage.value = "Export failed: ${e.message}"
            }
        }
    }

    // Auto-sync to Google Drive (debounced)
    private var autoSyncJob: Job? = null

    fun scheduleAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            delay(5000)
            withContext(Dispatchers.IO) {
                if (_isGoogleSignedIn.value) {
                    try {
                        val result = backupManager.createBackup(settingsManager, noteRepository, recordingRepository, chatRepository, includeRecordings = false)
                        if (result.success && result.file != null) {
                            googleDriveManager.uploadBackup(result.file)
                            Log.i(TAG, "Auto-sync completed")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Auto-sync failed: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        autoSyncJob?.cancel()
    }
}
