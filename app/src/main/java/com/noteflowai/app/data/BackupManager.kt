package com.noteflowai.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.noteflowai.app.data.chat.ChatRepository
import com.noteflowai.app.data.chat.ChatSession
import com.noteflowai.app.data.settings.SettingsManager
import com.noteflowai.app.util.SafeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.*
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupManager(private val context: Context) {

    private val gson = Gson()
    private val notesDir = File(context.filesDir, "notes")
    private val recordingsDir = File(context.filesDir, "recordings")
    private val chatsDir = File(context.filesDir, "chats")

    private fun isWithinDirectory(file: File, dir: File): Boolean {
        return file.canonicalPath.startsWith(dir.canonicalPath)
    }

    data class BackupResult(val success: Boolean, val message: String, val file: File? = null)

    companion object {
        const val MAGIC_HEADER = "NFENC01"
        private const val SALT_LENGTH_BYTES = 16
        private const val IV_LENGTH_BYTES = 12
        private const val KEY_LENGTH_BITS = 256
        private const val PBKDF2_ITERATIONS = 100_000
        private const val GCM_TAG_LENGTH_BITS = 128

        fun encryptContainer(
            inputStream: InputStream,
            outputStream: OutputStream,
            password: String
        ) {
            val random = SecureRandom()
            val salt = ByteArray(SALT_LENGTH_BYTES).also { random.nextBytes(it) }
            val iv = ByteArray(IV_LENGTH_BYTES).also { random.nextBytes(it) }

            val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
            val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = secretKeyFactory.generateSecret(keySpec).encoded
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

            // Write Header: MAGIC (7 bytes) + Salt (16 bytes) + IV (12 bytes)
            outputStream.write(MAGIC_HEADER.toByteArray(Charsets.US_ASCII))
            outputStream.write(salt)
            outputStream.write(iv)

            // Encrypt and write payload
            CipherOutputStream(outputStream, cipher).use { cos ->
                inputStream.copyTo(cos)
            }
        }

        fun decryptContainer(
            inputStream: InputStream,
            outputStream: OutputStream,
            password: String
        ) {
            val magicBytes = ByteArray(7)
            val readMagic = inputStream.read(magicBytes)
            if (readMagic != 7 || String(magicBytes, Charsets.US_ASCII) != MAGIC_HEADER) {
                throw IllegalArgumentException("Invalid encrypted backup archive header")
            }

            val salt = ByteArray(SALT_LENGTH_BYTES)
            if (inputStream.read(salt) != SALT_LENGTH_BYTES) {
                throw IllegalArgumentException("Corrupted backup header: incomplete salt")
            }

            val iv = ByteArray(IV_LENGTH_BYTES)
            if (inputStream.read(iv) != IV_LENGTH_BYTES) {
                throw IllegalArgumentException("Corrupted backup header: incomplete IV")
            }

            val keySpec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
            val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = secretKeyFactory.generateSecret(keySpec).encoded
            val secretKey = SecretKeySpec(keyBytes, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            CipherInputStream(inputStream, cipher).use { cis ->
                cis.copyTo(outputStream)
            }
        }
    }

    /**
     * Create a full backup file containing:
     * - settings.json (all DataStore preferences, credentials stripped)
     * - notes/ (all note files)
     * - recordings/ (all WAV files)
     * - chats/ (all chat session files)
     *
     * If [password] is provided, exports an AES-256-GCM encrypted `.enc.zip` container.
     * Otherwise exports a standard `.zip` file.
     */
    suspend fun createBackup(
        settingsManager: SettingsManager,
        noteRepository: NoteRepository,
        recordingRepository: RecordingRepository,
        chatRepository: ChatRepository,
        includeRecordings: Boolean = true,
        password: String? = null
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val backupDir = File(context.getExternalFilesDir(null), "NoteFlowAI_Backup")
            backupDir.mkdirs()

            val isEncrypted = !password.isNullOrBlank()
            val extension = if (isEncrypted) "enc.zip" else "zip"
            val backupFile = File(backupDir, "NoteFlowAI_Backup_$dateStr.$extension")

            // Collect all settings (API keys and private credentials are intentionally excluded).
            val settingsJson = collectSettings(settingsManager)

            if (isEncrypted) {
                val tempZip = File(context.cacheDir, "temp_backup_$dateStr.zip")
                try {
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zos ->
                        putZipEntry(zos, "settings.json", settingsJson.toByteArray())

                        if (notesDir.exists()) {
                            notesDir.listFiles()?.forEach { file ->
                                streamFileToZip(zos, "notes/${file.name}", file)
                            }
                        }

                        if (includeRecordings && recordingsDir.exists()) {
                            recordingsDir.listFiles()?.forEach { file ->
                                streamFileToZip(zos, "recordings/${file.name}", file)
                            }
                        }

                        if (chatsDir.exists()) {
                            chatsDir.listFiles()?.forEach { file ->
                                streamFileToZip(zos, "chats/${file.name}", file)
                            }
                        }
                    }

                    BufferedInputStream(FileInputStream(tempZip)).use { bis ->
                        BufferedOutputStream(FileOutputStream(backupFile)).use { bos ->
                            encryptContainer(bis, bos, password)
                        }
                    }
                } finally {
                    tempZip.delete()
                }
            } else {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(backupFile))).use { zos ->
                    putZipEntry(zos, "settings.json", settingsJson.toByteArray())

                    if (notesDir.exists()) {
                        notesDir.listFiles()?.forEach { file ->
                            streamFileToZip(zos, "notes/${file.name}", file)
                        }
                    }

                    if (includeRecordings && recordingsDir.exists()) {
                        recordingsDir.listFiles()?.forEach { file ->
                            streamFileToZip(zos, "recordings/${file.name}", file)
                        }
                    }

                    if (chatsDir.exists()) {
                        chatsDir.listFiles()?.forEach { file ->
                            streamFileToZip(zos, "chats/${file.name}", file)
                        }
                    }
                }
            }

            SafeLogger.i("BackupManager", "Backup created: ${backupFile.absolutePath} (${backupFile.length()} bytes)")
            BackupResult(true, "Backup created: ${backupFile.name}", backupFile)
        } catch (e: Exception) {
            SafeLogger.e("BackupManager", "Backup failed", e)
            BackupResult(false, "Backup failed: ${e.message}")
        }
    }

    /**
     * Restore from a backup archive (.zip or .enc.zip).
     * overwrite=true replaces all data; overwrite=false merges (skips existing).
     */
    suspend fun restoreBackup(
        backupFile: File,
        settingsManager: SettingsManager,
        noteRepository: NoteRepository,
        recordingRepository: RecordingRepository,
        chatRepository: ChatRepository,
        overwrite: Boolean = true,
        password: String? = null
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            if (!backupFile.exists()) {
                return@withContext BackupResult(false, "Backup file not found")
            }

            // Check if file is encrypted with MAGIC_HEADER
            val headerBytes = ByteArray(7)
            val isEncrypted = FileInputStream(backupFile).use { fis ->
                val read = fis.read(headerBytes)
                read == 7 && String(headerBytes, Charsets.US_ASCII) == MAGIC_HEADER
            }

            val targetZipFile: File
            val isTempZip: Boolean

            if (isEncrypted) {
                if (password.isNullOrBlank()) {
                    return@withContext BackupResult(false, "Password required for encrypted backup archive")
                }
                targetZipFile = File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")
                isTempZip = true

                try {
                    BufferedInputStream(FileInputStream(backupFile)).use { bis ->
                        BufferedOutputStream(FileOutputStream(targetZipFile)).use { bos ->
                            decryptContainer(bis, bos, password)
                        }
                    }
                } catch (e: Exception) {
                    targetZipFile.delete()
                    return@withContext BackupResult(false, "Invalid password or corrupted backup archive: ${e.message}")
                }
            } else {
                targetZipFile = backupFile
                isTempZip = false
            }

            var notesRestored = 0
            var recordingsRestored = 0
            var chatsRestored = 0
            var settingsRestored = false

            try {
                ZipInputStream(BufferedInputStream(FileInputStream(targetZipFile))).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name

                        when {
                            name == "settings.json" -> {
                                val bytes = zis.readBytes()
                                restoreSettings(String(bytes), settingsManager)
                                settingsRestored = true
                            }
                            name.startsWith("notes/") && !entry.isDirectory -> {
                                val fileName = name.removePrefix("notes/")
                                val destFile = File(notesDir, fileName)
                                if (!isWithinDirectory(destFile, notesDir)) { zis.closeEntry(); entry = zis.nextEntry; continue }
                                if (overwrite || !destFile.exists()) {
                                    writeZipEntryTo(zis, destFile)
                                    notesRestored++
                                }
                            }
                            name.startsWith("recordings/") && !entry.isDirectory -> {
                                val fileName = name.removePrefix("recordings/")
                                val destFile = File(recordingsDir, fileName)
                                if (!isWithinDirectory(destFile, recordingsDir)) { zis.closeEntry(); entry = zis.nextEntry; continue }
                                if (overwrite || !destFile.exists()) {
                                    writeZipEntryTo(zis, destFile)
                                    recordingsRestored++
                                }
                            }
                            name.startsWith("chats/") && !entry.isDirectory -> {
                                val fileName = name.removePrefix("chats/")
                                val destFile = File(chatsDir, fileName)
                                if (!isWithinDirectory(destFile, chatsDir)) { zis.closeEntry(); entry = zis.nextEntry; continue }
                                if (overwrite || !destFile.exists()) {
                                    writeZipEntryTo(zis, destFile)
                                    chatsRestored++
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } finally {
                if (isTempZip) {
                    targetZipFile.delete()
                }
            }

            // Refresh repositories
            noteRepository.refreshNotesList()
            recordingRepository.refresh()
            chatRepository.refresh()

            val msg = buildString {
                append("Restored: ")
                if (settingsRestored) append("settings, ")
                append("$notesRestored notes, ")
                append("$recordingsRestored recordings, ")
                append("$chatsRestored chat sessions")
            }

            SafeLogger.i("BackupManager", msg)
            BackupResult(true, msg)
        } catch (e: Exception) {
            SafeLogger.e("BackupManager", "Restore failed", e)
            BackupResult(false, "Restore failed: ${e.message}")
        }
    }

    private suspend fun collectSettings(sm: SettingsManager): String {
        // Note: API keys and AI presets are deliberately NOT included. They are
        // credentials stored in EncryptedSharedPreferences; exporting them in a
        // plaintext settings.json (or to the cloud via Google Drive) would leak
        // working credentials. Users re-enter keys after a restore.
        val map = mutableMapOf<String, Any?>(
            "whisper_model" to sm.whisperModel.first(),
            "is_dark_mode" to sm.isDarkMode.first(),
            "translate_mode" to sm.isTranslateMode.first(),
            "transcription_language" to sm.transcriptionLanguage.first(),
            "app_theme" to sm.appTheme.first(),
            "app_font" to sm.appFont.first(),
            "is_online_mode" to sm.isOnlineMode.first(),
            "ai_provider" to sm.aiProvider.first(),
            "ai_base_url" to sm.aiBaseUrl.first(),
            "ai_model_name" to sm.aiModelName.first(),
            "ai_system_prompt" to sm.aiSystemPrompt.first(),
            "ai_thinking_mode" to sm.aiThinkingMode.first(),
            "ai_temperature" to sm.aiTemperature.first(),
            "ai_presence_penalty" to sm.aiPresencePenalty.first(),
            "ai_top_p" to sm.aiTopP.first(),
            "ai_context_tokens" to sm.aiContextTokens.first(),
            "ai_kv_cache" to sm.aiKvCache.first(),
            "ai_web_search_enabled" to sm.aiWebSearchEnabled.first(),
            "ai_search_api_url" to sm.aiSearchApiUrl.first(),
            "ai_max_requests_per_min" to sm.aiMaxRequestsPerMin.first()
        )
        return gson.toJson(map)
    }

    private suspend fun restoreSettings(json: String, sm: SettingsManager) {
        try {
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(json, Map::class.java) as Map<String, Any>

            (map["whisper_model"] as? String)?.let { sm.setWhisperModel(it) }
            (map["is_dark_mode"] as? Boolean)?.let { sm.setDarkMode(it) }
            (map["translate_mode"] as? Boolean)?.let { sm.setTranslateMode(it) }
            (map["transcription_language"] as? String)?.let { sm.setTranscriptionLanguage(it) }
            (map["app_theme"] as? String)?.let { sm.setAppTheme(it) }
            (map["app_font"] as? String)?.let { sm.setAppFont(it) }
            (map["is_online_mode"] as? Boolean)?.let { sm.setOnlineMode(it) }
            (map["ai_provider"] as? String)?.let { provider ->
                sm.updateAiSettings(
                    provider = provider,
                    apiKey = sm.aiApiKeyBlocking,
                    baseUrl = (map["ai_base_url"] as? String) ?: "",
                    modelName = (map["ai_model_name"] as? String) ?: "",
                    systemPrompt = (map["ai_system_prompt"] as? String) ?: "",
                    temperature = (map["ai_temperature"] as? Double)?.toFloat() ?: 0.7f,
                    presencePenalty = (map["ai_presence_penalty"] as? Double)?.toFloat() ?: 0.0f,
                    topP = (map["ai_top_p"] as? Double)?.toFloat() ?: 1.0f,
                    contextTokens = (map["ai_context_tokens"] as? Double)?.toInt() ?: 2048,
                    useKvCache = (map["ai_kv_cache"] as? Boolean) ?: true
                )
            }
            (map["ai_thinking_mode"] as? Boolean)?.let { sm.setAiThinkingMode(it) }
            val wsEnabled = (map["ai_web_search_enabled"] as? Boolean) ?: false
            val wsUrl = (map["ai_search_api_url"] as? String) ?: ""
            val wsKey = sm.aiSearchApiKeyBlocking
            if (wsEnabled || wsUrl.isNotBlank() || wsKey.isNotBlank()) {
                sm.setAiSearchSettings(wsEnabled, wsUrl, wsKey)
            }
            (map["ai_max_requests_per_min"] as? Double)?.toInt()?.let { sm.setAiMaxRequestsPerMin(it) }
        } catch (e: Exception) {
            SafeLogger.e("BackupManager", "Failed to restore settings", e)
        }
    }

    private fun putZipEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(data)
        zos.closeEntry()
    }

    private fun streamFileToZip(zos: ZipOutputStream, name: String, file: java.io.File) {
        zos.putNextEntry(ZipEntry(name))
        file.inputStream().use { input ->
            input.copyTo(zos, bufferSize = 8192)
        }
        zos.closeEntry()
    }

    private fun writeZipEntryTo(zis: ZipInputStream, destFile: java.io.File) {
        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile).use { output ->
            zis.copyTo(output, bufferSize = 8192)
        }
    }
}
