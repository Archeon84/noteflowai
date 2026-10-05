package com.noteflowai.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noteflowai.app.data.capture.CaptureJobScheduler
import com.noteflowai.app.data.capture.RawCaptureRepository
import com.noteflowai.app.data.chat.AiChatRepository
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.SourceSegmentManager
import com.noteflowai.app.data.settings.SettingsManager
import com.noteflowai.app.data.youtube.YouTubeCaptionResult
import com.noteflowai.app.data.youtube.YouTubeRepository
import com.noteflowai.app.data.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class YouTubeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "YouTubeVM"
    }

    private val repository = YouTubeRepository()
    private val aiChatRepository = AiChatRepository()
    private val settingsManager = SettingsManager.getInstance(application)
    private val noteRepository = NoteRepository(application)
    private val sourceSegmentManager by lazy { SourceSegmentManager.getInstance(application) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(YouTubeUiState())
    val uiState: StateFlow<YouTubeUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null
    private var summarizeJob: Job? = null

    fun processUrl(url: String) {
        val videoId = repository.extractVideoId(url)
        if (videoId == null) {
            _uiState.value = _uiState.value.copy(
                error = "Invalid YouTube URL"
            )
            return
        }

        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = "",
                videoUrl = url,
                videoId = videoId
            )

            try {
                val title = repository.fetchVideoTitle(videoId)
                _uiState.value = _uiState.value.copy(videoTitle = title)

                val result = repository.fetchCaptions(videoId)
                if (result.success) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        transcript = result.transcript,
                        captionLanguage = result.language,
                        hasTranscript = true
                    )
                    // Personal Memory Layer: index the fetched transcript (non-blocking, behind flag).
                    sourceSegmentManager.onYouTubeTranscriptFetched(
                        videoId,
                        result.transcript,
                        url,
                        result.language,
                        title
                    )
                    recordYouTubeCaptureAndSchedule(videoId, result.transcript, title)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.error ?: "No captions available",
                        hasTranscript = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fetch failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed: ${e.message}"
                )
            }
        }
    }

    /**
     * Phase 1 extension: record the fetched transcript as a durable capture and enqueue the
     * processing job behind the source-segments feature flag, mirroring the note/recording path.
     */
    private fun recordYouTubeCaptureAndSchedule(videoId: String, transcript: String, title: String) {
        try {
            val enabled = SettingsManager.getInstance(getApplication()).enableSourceSegmentsBlocking
            if (!enabled) return
            val captureRepo = RawCaptureRepository.getInstance(getApplication())
            scope.launch {
                captureRepo.recordCapture(
                    sourceId = videoId,
                    sourceType = SourceType.YOUTUBE,
                    title = title,
                    rawText = transcript
                )
                CaptureJobScheduler.enqueue(getApplication(), videoId, SourceType.YOUTUBE)
            }
        } catch (e: Exception) {
            // Capture is best-effort; a failure must not block showing the transcript.
        }
    }

    fun summarizeTranscript() {
        val transcript = _uiState.value.transcript
        if (transcript.isBlank()) return

        summarizeJob?.cancel()
        summarizeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSummarizing = true,
                summary = ""
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

                val systemPrompt = """You are a helpful assistant that summarizes YouTube video transcripts.
Provide a concise summary with:
1. Main topic (1 line)
2. Key points (bullet list)
3. Conclusion (1-2 sentences)
Keep the summary clear and well-structured."""

                val userMessage = "Summarize this YouTube video transcript:\n\nTitle: ${_uiState.value.videoTitle}\n\n$transcript"

                val summaryBuilder = StringBuilder()
                aiChatRepository.streamResponse(
                    baseUrl = baseUrl,
                    provider = provider,
                    apiKey = apiKey,
                    model = model,
                    systemPrompt = systemPrompt,
                    messages = listOf(
                        com.noteflowai.app.data.chat.ChatMessage(role = "user", content = userMessage)
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

    fun clearAll() {
        fetchJob?.cancel()
        summarizeJob?.cancel()
        _uiState.value = YouTubeUiState()
    }

    fun saveSummaryAsNote() {
        val summary = _uiState.value.summary
        if (summary.isBlank()) return
        viewModelScope.launch {
            val title = _uiState.value.videoTitle.ifBlank { "YouTube Summary" }
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
            val fileName = "${title.take(50)}_summary_$dateStr.txt"
            noteRepository.saveNote(fileName, summary)
            _uiState.value = _uiState.value.copy(summarySaved = true)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = "", summaryError = "")
    }
}

data class YouTubeUiState(
    val isLoading: Boolean = false,
    val videoUrl: String = "",
    val videoId: String = "",
    val videoTitle: String = "",
    val hasTranscript: Boolean = false,
    val transcript: String = "",
    val captionLanguage: String = "",
    val isSummarizing: Boolean = false,
    val summary: String = "",
    val summarySaved: Boolean = false,
    val error: String = "",
    val summaryError: String = ""
)
