package com.noteflowai.app.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noteflowai.app.data.NoteRepository
import com.noteflowai.app.data.capture.CaptureJobScheduler
import com.noteflowai.app.data.capture.RawCaptureRepository
import com.noteflowai.app.data.chat.AiChatRepository
import com.noteflowai.app.data.chat.ChatMessage
import com.noteflowai.app.data.document.DocumentMetadata
import com.noteflowai.app.data.document.DocumentRepository
import com.noteflowai.app.data.document.ExtractResult
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.SourceSegmentManager
import com.noteflowai.app.data.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DocumentViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DocumentVM"
        private const val MAX_CHARS_FOR_AI = 10000
    }

    private val documentRepository = DocumentRepository(application)
    private val aiChatRepository = AiChatRepository()
    private val settingsManager = SettingsManager.getInstance(application)
    private val noteRepository = NoteRepository(application)
    private val sourceSegmentManager by lazy { SourceSegmentManager.getInstance(application) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(DocumentUiState())
    val uiState: StateFlow<DocumentUiState> = _uiState.asStateFlow()

    private var processJob: Job? = null
    private var summarizeJob: Job? = null

    fun processDocument(uri: Uri, mimeType: String) {
        processJob?.cancel()
        processJob = viewModelScope.launch {
            _uiState.value = DocumentUiState(
                isLoading = true,
                mimeType = mimeType
            )

            try {
                val result = documentRepository.extractText(uri, mimeType)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    extractedText = result.text,
                    metadata = result.metadata,
                    hasText = true
                )
                // Personal Memory Layer: index imported document content (non-blocking, behind flag).
                sourceSegmentManager.onDocumentImported(
                    result.metadata.fileName,
                    result.text,
                    result.metadata.format,
                    result.metadata
                )
                recordDocumentCaptureAndSchedule(result.metadata.fileName, result.text)
            } catch (e: Exception) {
                Log.e(TAG, "Document processing failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to process document"
                )
            }
        }
    }

    /**
     * Phase 1 extension: record the imported document as a durable capture and enqueue the
     * processing job behind the source-segments feature flag, mirroring the note/recording path.
     */
    private fun recordDocumentCaptureAndSchedule(fileName: String, text: String) {
        try {
            val enabled = SettingsManager.getInstance(getApplication()).enableSourceSegmentsBlocking
            if (!enabled) return
            val captureRepo = RawCaptureRepository.getInstance(getApplication())
            scope.launch {
                captureRepo.recordCapture(
                    sourceId = fileName,
                    sourceType = SourceType.DOCUMENT,
                    title = fileName,
                    rawText = text
                )
                CaptureJobScheduler.enqueue(getApplication(), fileName, SourceType.DOCUMENT)
            }
        } catch (e: Exception) {
            // Capture is best-effort; a failure must not block showing the extracted document.
        }
    }

    fun summarizeDocument() {
        val text = _uiState.value.extractedText
        if (text.isBlank()) return

        summarizeJob?.cancel()
        summarizeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSummarizing = true,
                summary = "",
                summaryError = ""
            )

            try {
                val baseUrl = settingsManager.aiBaseUrl.first()
                val apiKey = settingsManager.aiApiKey.first()
                val model = settingsManager.aiModelName.first()
                val provider = settingsManager.aiProvider.first()

                if (baseUrl.isBlank() || apiKey.isBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isSummarizing = false,
                        summaryError = "AI not configured. Set API key in Settings."
                    )
                    return@launch
                }

                val truncatedText = if (text.length > MAX_CHARS_FOR_AI) {
                    text.take(MAX_CHARS_FOR_AI) + "\n\n[Truncated for AI processing]"
                } else {
                    text
                }

                val fileName = _uiState.value.metadata?.fileName ?: "document"
                val systemPrompt = """You are a helpful assistant that summarizes documents.
Provide a concise summary with:
1. Main topic (1 line)
2. Key points (bullet list)
3. Conclusion (1-2 sentences)
Keep the summary clear and well-structured."""

                val userMessage = "Summarize this document ($fileName):\n\n$truncatedText"

                val summaryBuilder = StringBuilder()
                aiChatRepository.streamResponse(
                    baseUrl = baseUrl,
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    messages = listOf(
                        ChatMessage(role = "user", content = userMessage)
                    ),
                    temperature = 0.3f,
                    topP = 0.9f
                ).collect { chunk ->
                    summaryBuilder.append(chunk)
                    _uiState.value = _uiState.value.copy(summary = summaryBuilder.toString())
                }

                _uiState.value = _uiState.value.copy(isSummarizing = false)
            } catch (e: Exception) {
                Log.e(TAG, "Summarize failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isSummarizing = false,
                    summaryError = "Summarization failed: ${e.message}"
                )
            }
        }
    }

    fun saveExtractedTextAsNote() {
        val text = _uiState.value.extractedText
        if (text.isBlank()) return
        viewModelScope.launch {
            val title = _uiState.value.metadata?.fileName?.substringBeforeLast(".") ?: "Document"
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
            val fileName = "${title.take(50)}_$dateStr.txt"
            noteRepository.saveNote(fileName, text)
            _uiState.value = _uiState.value.copy(textSaved = true)
        }
    }

    fun saveSummaryAsNote() {
        val summary = _uiState.value.summary
        if (summary.isBlank()) return
        viewModelScope.launch {
            val title = _uiState.value.metadata?.fileName?.substringBeforeLast(".") ?: "Document"
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
            val fileName = "${title.take(50)}_summary_$dateStr.txt"
            noteRepository.saveNote(fileName, summary)
            _uiState.value = _uiState.value.copy(summarySaved = true)
        }
    }

    fun clearAll() {
        processJob?.cancel()
        summarizeJob?.cancel()
        _uiState.value = DocumentUiState()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = "", summaryError = "")
    }
}

data class DocumentUiState(
    val isLoading: Boolean = false,
    val mimeType: String = "",
    val extractedText: String = "",
    val metadata: DocumentMetadata? = null,
    val hasText: Boolean = false,
    val isSummarizing: Boolean = false,
    val summary: String = "",
    val textSaved: Boolean = false,
    val summarySaved: Boolean = false,
    val error: String = "",
    val summaryError: String = ""
)
