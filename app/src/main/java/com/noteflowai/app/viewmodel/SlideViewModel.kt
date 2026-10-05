package com.noteflowai.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noteflowai.app.data.NoteRepository
import com.noteflowai.app.data.chat.AiChatRepository
import com.noteflowai.app.data.chat.ChatMessage
import com.noteflowai.app.data.settings.SettingsManager
import com.noteflowai.app.data.LiteRtInferenceManager
import com.noteflowai.app.pptx.PptxGenerator
import com.noteflowai.app.pptx.SlideData
import com.noteflowai.app.pptx.SlideBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class SlideViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SlideVM"
    }

    private val settingsManager = SettingsManager.getInstance(application)
    private val noteRepository = NoteRepository(application)
    private val aiChatRepository = AiChatRepository()
    private val liteRtInferenceManager = LiteRtInferenceManager(application)

    // Slide generation state
    private val _showSlideGenerateDialog = MutableStateFlow(false)
    val showSlideGenerateDialog: StateFlow<Boolean> = _showSlideGenerateDialog.asStateFlow()

    private val _slideGenerateSource = MutableStateFlow("")
    val slideGenerateSource: StateFlow<String> = _slideGenerateSource.asStateFlow()

    private val _slideGenerateTitle = MutableStateFlow("")
    val slideGenerateTitle: StateFlow<String> = _slideGenerateTitle.asStateFlow()

    private val _generatedSlides = MutableStateFlow<List<SlideData>>(emptyList())
    val generatedSlides: StateFlow<List<SlideData>> = _generatedSlides.asStateFlow()

    private val _showSlidePreview = MutableStateFlow(false)
    val showSlidePreview: StateFlow<Boolean> = _showSlidePreview.asStateFlow()

    // Local processing state (not shared with MainViewModel)
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _processingStep = MutableStateFlow("")
    val processingStep: StateFlow<String> = _processingStep.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    fun showSlideGenerateDialog(note: com.noteflowai.app.data.NoteFile) {
        viewModelScope.launch {
            val content = noteRepository.readNote(note.fileName)
            _slideGenerateSource.value = content
            _slideGenerateTitle.value = note.fileName.removeSuffix(".json").removeSuffix(".txt")
            _showSlideGenerateDialog.value = true
        }
    }

    fun showSlideGenerateDialogFromText(text: String, title: String) {
        _slideGenerateSource.value = text
        _slideGenerateTitle.value = title
        _showSlideGenerateDialog.value = true
    }

    fun dismissSlideGenerateDialog() {
        _showSlideGenerateDialog.value = false
    }

    fun generateSlides(customPrompt: String, mode: String) {
        val content = _slideGenerateSource.value
        val title = _slideGenerateTitle.value
        if (content.isBlank()) {
            _errorMessage.value = "No content to generate slides from"
            return
        }

        _showSlideGenerateDialog.value = false
        _isProcessing.value = true
        _processingStep.value = "Generating slide outline..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val onlineMode = settingsManager.isOnlineMode.first()
                var slideOutline: String? = null

                // Try offline LiteRT-LM first (skip if online mode)
                if (!onlineMode && liteRtInferenceManager.getModelPath() != null) {
                    _processingStep.value = "Processing with offline AI..."
                    try {
                        val llamaContent = if (content.length > 4000) content.take(4000) + "\n\n[Truncated]" else content
                        val prompt = "<start_of_turn>user\n$customPrompt\n\n$llamaContent<end_of_turn>\n<start_of_turn>model\n"
                        slideOutline = withTimeoutOrNull(150_000L) {
                            liteRtInferenceManager.generate(
                                prompt,
                                maxTokens = 2048,
                                temperature = settingsManager.aiTemperature.first(),
                                topP = settingsManager.aiTopP.first(),
                                topK = 40,
                                repeatPenalty = 1.1f,
                                frequencyPenalty = 0.0f,
                                presencePenalty = settingsManager.aiPresencePenalty.first()
                            )
                        }
                        if (slideOutline.isNullOrBlank() || slideOutline.startsWith("Error:")) {
                            Log.w(TAG, "Qwen3 slide generation failed: $slideOutline")
                            slideOutline = null
                        } else {
                            Log.i(TAG, "Qwen3 slide generation succeeded")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Qwen3 slide generation exception: ${e.message}")
                        slideOutline = null
                    }
                }

                // Fallback to remote AI
                if (slideOutline == null) {
                    _processingStep.value = "Processing with cloud AI..."
                    val remoteContent = if (content.length > 10000) content.take(10000) else content
                    val prompt = "$customPrompt\n\n$remoteContent"
                    val provider = settingsManager.aiProvider.first()
                    val apiKey = settingsManager.aiApiKey.first()
                    val baseUrl = settingsManager.aiBaseUrl.first()
                    val needsApiKey = provider !in listOf("Ollama", "LM Studio")

                    if (needsApiKey && apiKey.isBlank()) {
                        withContext(Dispatchers.Main) { _errorMessage.value = "No offline AI available and no API key configured." }
                        return@launch
                    }

                    slideOutline = withTimeoutOrNull(150_000L) {
                        aiChatRepository.getResponse(
                            baseUrl = baseUrl,
                            provider = provider,
                            apiKey = apiKey,
                            model = settingsManager.aiModelName.first(),
                            systemPrompt = "You are a presentation assistant. Convert text into structured slide outlines.",
                            messages = listOf(ChatMessage(role = "user", content = prompt)),
                            temperature = settingsManager.aiTemperature.first(),
                            presencePenalty = settingsManager.aiPresencePenalty.first(),
                            topP = settingsManager.aiTopP.first(),
                            contextTokens = settingsManager.aiContextTokens.first(),
                            useKvCache = settingsManager.aiKvCache.first()
                        )?.content
                    }

                    if (slideOutline.isNullOrBlank() || slideOutline.startsWith("Error:")) {
                        withContext(Dispatchers.Main) { _errorMessage.value = "AI slide generation failed. Try again." }
                        return@launch
                    }
                }

                // Parse slide outline
                val slides = parseSlideOutline(slideOutline!!)
                if (slides.isEmpty()) {
                    withContext(Dispatchers.Main) { _errorMessage.value = "Could not parse slide outline from AI response." }
                    return@launch
                }

                _generatedSlides.value = slides
                withContext(Dispatchers.Main) { _showSlidePreview.value = true }

            } catch (e: Exception) {
                Log.e(TAG, "Slide generation failed: ${e.message}")
                withContext(Dispatchers.Main) { _errorMessage.value = "Slide generation failed: ${e.message}" }
            } finally {
                _isProcessing.value = false
                _processingStep.value = ""
            }
        }
    }

    private fun parseSlideOutline(aiOutput: String): List<SlideData> {
        val slides = mutableListOf<SlideData>()
        val slidePattern = Regex("(?i)^SLIDE\\s*\\d*\\s*:\\s*(.*)", RegexOption.MULTILINE)
        val matches = slidePattern.findAll(aiOutput).toList()

        if (matches.isEmpty()) {
            val lines = aiOutput.lines().filter { it.isNotBlank() }
            if (lines.isNotEmpty()) {
                slides.add(SlideData(
                    title = lines.first().trim().removePrefix("#").trim(),
                    bullets = lines.drop(1).map { it.trim().removePrefix("-").removePrefix("*").trim() }.filter { it.isNotBlank() }
                ))
            }
            return slides
        }

        for (i in matches.indices) {
            val title = matches[i].groupValues[1].trim()

            val contentText = aiOutput.substringAfter(matches[i].value)
                .substringBefore(if (i + 1 < matches.size) matches[i + 1].value else "###END###")
                .trim()

            val bullets = mutableListOf<String>()
            val contentBlocks = mutableListOf<SlideBlock>()

            for (line in contentText.lines()) {
                val trimmed = line.trim()
                if (trimmed.isBlank()) continue

                when {
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        bullets.add(trimmed.removePrefix("- ").removePrefix("* ").trim())
                        contentBlocks.add(SlideBlock.Bullet(trimmed.removePrefix("- ").removePrefix("* ").trim()))
                    }
                    trimmed.startsWith("```") -> {
                        val codeLines = mutableListOf<String>()
                        val codeStart = trimmed.removePrefix("```")
                        if (codeStart.isNotBlank()) codeLines.add(codeStart)
                        var j = i + 1
                        while (j < matches.size) {
                            val nextContent = aiOutput.substringAfter(matches[j - 1].value)
                                .substringBefore(if (j < matches.size) matches[j].value else "###END###")
                            for (codeLine in nextContent.lines()) {
                                if (codeLine.trim() == "```") break
                                codeLines.add(codeLine)
                            }
                            break
                        }
                        contentBlocks.add(SlideBlock.Code(codeLines.joinToString("\n")))
                    }
                    trimmed.contains("|") && trimmed.count { it == '|' } >= 2 -> {
                        val cells = trimmed.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (cells.isNotEmpty()) {
                            contentBlocks.add(SlideBlock.Table(cells, emptyList()))
                        }
                    }
                    else -> {
                        if (trimmed.isNotBlank()) {
                            bullets.add(trimmed)
                            contentBlocks.add(SlideBlock.Bullet(trimmed))
                        }
                    }
                }
            }

            slides.add(SlideData(
                title = title,
                bullets = bullets,
                content = contentBlocks,
                isTitleSlide = i == 0 && slides.isEmpty()
            ))
        }

        return slides
    }

    fun dismissSlidePreview() {
        _showSlidePreview.value = false
    }

    fun shareSlidePptx(context: Context) {
        val slides = _generatedSlides.value
        val title = _slideGenerateTitle.value
        if (slides.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "exports")
                cacheDir.mkdirs()
                val pptxFile = File(cacheDir, "${title}_slides.pptx")
                PptxGenerator.generatePptx(slides, title, pptxFile)

                var savedToDownloads = false
                try {
                    @Suppress("DEPRECATION")
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    if (downloadsDir.exists() || downloadsDir.mkdirs()) {
                        val destFile = File(downloadsDir, "${title}_slides.pptx")
                        pptxFile.copyTo(destFile, overwrite = true)
                        savedToDownloads = true
                        Log.i(TAG, "PPTX saved to: ${destFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Direct save failed: ${e.message}")
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", pptxFile
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "$title.pptx")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    if (savedToDownloads) {
                        _errorMessage.value = "Saved to Downloads folder"
                    }
                    context.startActivity(Intent.createChooser(intent, "Save or Share Presentation"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "PPTX share failed: ${e.message}")
                withContext(Dispatchers.Main) { _errorMessage.value = "Failed to create PPTX: ${e.message}" }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // NOTE: liteRtInferenceManager.free() intentionally NOT called here.
        // MainViewModel owns the primary LiteRtInferenceManager lifecycle.
    }
}
