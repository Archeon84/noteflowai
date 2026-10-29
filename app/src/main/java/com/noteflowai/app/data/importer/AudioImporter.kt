package com.noteflowai.app.data.importer

import android.content.Context
import com.noteflowai.app.data.AudioConverter
import com.noteflowai.app.data.DeepgramRepository
import com.noteflowai.app.data.RecordingRepository
import com.noteflowai.app.data.WhisperModelManager
import com.noteflowai.app.data.memory.repository.SourceSegmentManager
import com.noteflowai.app.data.settings.SettingsManager
import com.noteflowai.app.whisper.WhisperBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioImporter(
    private val context: Context,
    private val transcribeOverride: (suspend (File) -> String)? = null
) : ContentImporter {

    override suspend fun inspect(input: ImportInput): ImportInspection = withContext(Dispatchers.IO) {
        val estimatedBytes = input.file?.length() ?: 0L
        ImportInspection(
            isSupported = true,
            formatLabel = "Audio",
            estimatedBytes = estimatedBytes
        )
    }

    override suspend fun import(input: ImportInput): ImportResult = withContext(Dispatchers.IO) {
        var tempRawFile: File? = null
        var tempWavFile: File? = null
        try {
            val sourceFile: File = when {
                input.file != null && input.file.exists() -> input.file
                input.uri != null -> {
                    val raw = File(context.cacheDir, "audio_import_raw_${System.currentTimeMillis()}")
                    tempRawFile = raw
                    context.contentResolver.openInputStream(input.uri)?.use { inStream ->
                        FileOutputStream(raw).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    } ?: return@withContext ImportResult.Failure(
                        error = ImportError.MalformedContent,
                        rawMessage = "Cannot open selected audio file",
                        fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
                    )
                    raw
                }
                else -> return@withContext ImportResult.Failure(
                    error = ImportError.MalformedContent,
                    rawMessage = "Audio file not found",
                    fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
                )
            }

            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                return@withContext ImportResult.Failure(
                    error = ImportError.EmptyContent,
                    rawMessage = "Audio file is empty or unreadable",
                    fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
                )
            }

            // If a test/caller injected a direct transcription override, use it directly
            if (transcribeOverride != null) {
                val transcript = transcribeOverride.invoke(sourceFile).trim()
                if (transcript.isBlank()) {
                    return@withContext ImportResult.Failure(
                        error = ImportError.EmptyContent,
                        rawMessage = "No speech detected in audio file",
                        fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
                    )
                }
                val words = transcript.split(Regex("\\s+")).count { it.isNotBlank() }
                return@withContext ImportResult.Success(
                    text = transcript,
                    title = input.title ?: sourceFile.name,
                    format = "Audio",
                    wordCount = words,
                    charCount = transcript.length
                )
            }

            // Ensure 16kHz mono 16-bit PCM WAV for Whisper / RecordingRepository
            val wavFile: File = if (is16kMonoPcmWav(sourceFile)) {
                sourceFile
            } else {
                val converted = File(context.cacheDir, "audio_import_${System.currentTimeMillis()}.wav")
                tempWavFile = converted
                val ok = AudioConverter.toWav(sourceFile, converted)
                if (!ok || !converted.exists() || converted.length() <= 44L) {
                    if (isRiffWav(sourceFile)) {
                        sourceFile
                    } else {
                        return@withContext ImportResult.Failure(
                            error = ImportError.MalformedContent,
                            rawMessage = "Failed to decode audio file into 16kHz WAV. Format may be corrupted or unsupported.",
                            fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
                        )
                    }
                } else {
                    converted
                }
            }

            // Persist a copy in RecordingRepository so the audio is preserved in the library
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val recordingFileName = "imported_$dateStr.wav"
            runCatching {
                RecordingRepository.getInstance(context).saveRecording(wavFile, recordingFileName)
            }

            val settings = SettingsManager.getInstance(context)
            val isOnline = runCatching { settings.isOnlineMode.first() }.getOrDefault(false)
            val isLocalOnly = runCatching { settings.isLocalOnlyMode.first() }.getOrDefault(false)
            val deepgramKey = runCatching { settings.deepgramApiKey.first() }.getOrDefault("")
            val language = runCatching { settings.transcriptionLanguage.first() }.getOrDefault("English")

            val (transcript, modelTag) = if (isOnline && !isLocalOnly && deepgramKey.isNotBlank()) {
                val dgLang = toDeepgramCode(language)
                val dgText = DeepgramRepository().transcribeFile(wavFile, deepgramKey, dgLang)
                dgText to "deepgram"
            } else {
                val whisperLang = toWhisperCode(language)
                val bridge = WhisperBridge()
                var rawResult = bridge.transcribe(wavFile.absolutePath, whisperLang, true, "")
                if (rawResult.startsWith("[Error: Whisper engine not initialized")) {
                    val modelPath = resolveOfflineWhisperModelPath(settings)
                        ?: return@withContext ImportResult.Failure(
                            error = ImportError.ProviderUnavailable,
                            rawMessage = "No offline Whisper model downloaded. Download a Whisper model in Settings > Voice & Speech, or enable Online Mode (Deepgram).",
                            retryable = true
                        )
                    if (!bridge.initialize(modelPath, 0)) {
                        return@withContext ImportResult.Failure(
                            error = ImportError.ProviderUnavailable,
                            rawMessage = "Failed to initialize offline Whisper model.",
                            retryable = true
                        )
                    }
                    rawResult = bridge.transcribe(wavFile.absolutePath, whisperLang, true, "")
                }
                rawResult to "whisper"
            }

            val cleanTranscript = transcript.trim()
            if (cleanTranscript.startsWith("[Error:") || cleanTranscript.startsWith("Error:")) {
                return@withContext ImportResult.Failure(
                    error = ImportError.ProviderUnavailable,
                    rawMessage = cleanTranscript.removePrefix("[").removeSuffix("]"),
                    retryable = true
                )
            }

            if (cleanTranscript.isBlank() || cleanTranscript.startsWith("[No speech")) {
                return@withContext ImportResult.Failure(
                    error = ImportError.EmptyContent,
                    rawMessage = "No speech detected in the imported audio file.",
                    fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
                )
            }

            runCatching {
                SourceSegmentManager.getInstance(context).onTranscriptionSaved(
                    recordingFileName,
                    cleanTranscript,
                    modelTag,
                    language
                )
            }

            val wordCount = cleanTranscript.split(Regex("\\s+")).count { it.isNotBlank() }
            ImportResult.Success(
                text = cleanTranscript,
                title = input.title ?: sourceFile.name,
                format = "Audio",
                wordCount = wordCount,
                charCount = cleanTranscript.length,
                rawCaptureId = recordingFileName
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                error = ImportError.MalformedContent,
                rawMessage = "Audio transcription failed: ${e.message ?: "Unknown error"}",
                retryable = true
            )
        } finally {
            runCatching { tempRawFile?.delete() }
            runCatching { tempWavFile?.delete() }
        }
    }

    private suspend fun resolveOfflineWhisperModelPath(settings: SettingsManager): String? {
        val customPath = runCatching { settings.customModelPath.first() }.getOrNull()
        if (!customPath.isNullOrBlank() && File(customPath).exists()) {
            return customPath
        }
        val modelManager = WhisperModelManager(context)
        val preferred = runCatching { settings.whisperModel.first() }.getOrDefault("tiny")
        modelManager.getGgmlModelPath(preferred)?.let { return it }

        for (candidate in WhisperModelManager.MODELS) {
            modelManager.getGgmlModelPath(candidate)?.let { return it }
            for (quant in WhisperModelManager.GGML_QUANT_VARIANTS) {
                modelManager.getGgmlModelPath("$candidate-$quant")?.let { return it }
            }
        }
        return null
    }

    private fun isRiffWav(file: File): Boolean {
        if (file.length() < 44L) return false
        return runCatching {
            file.inputStream().use { fis ->
                val header = ByteArray(12)
                if (fis.read(header) < 12) return false
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
            }
        }.getOrDefault(false)
    }

    private fun is16kMonoPcmWav(file: File): Boolean {
        if (file.length() < 44L) return false
        return runCatching {
            file.inputStream().use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) < 44) return false
                val isRiff = String(header, 0, 4, Charsets.US_ASCII) == "RIFF"
                val isWave = String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
                if (!isRiff || !isWave) return false
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val audioFormat = buf.getShort(20).toInt()
                val channels = buf.getShort(22).toInt()
                val sampleRate = buf.getInt(24)
                val bitsPerSample = buf.getShort(34).toInt()
                audioFormat == 1 && channels == 1 && sampleRate == 16000 && bitsPerSample == 16
            }
        }.getOrDefault(false)
    }

    private fun toWhisperCode(lang: String): String = when (lang) {
        "English" -> "en"
        "Mandarin" -> "zh"
        "Japanese" -> "ja"
        "Korean" -> "ko"
        "Malay" -> "ms"
        "Auto" -> ""
        else -> "en"
    }

    private fun toDeepgramCode(lang: String): String = when (lang) {
        "English" -> "en"
        "Mandarin" -> "zh"
        "Japanese" -> "ja"
        "Korean" -> "ko"
        "Malay" -> "ms"
        else -> "multi"
    }
}
