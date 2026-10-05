package com.noteflowai.app.data.importer

import com.noteflowai.app.data.youtube.YouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YoutubeTranscriptImporter(
    private val youTubeRepository: YouTubeRepository
) : ContentImporter {

    override suspend fun inspect(input: ImportInput): ImportInspection = withContext(Dispatchers.IO) {
        val url = input.url ?: return@withContext ImportInspection(isSupported = false, formatLabel = "YouTube")
        val videoId = youTubeRepository.extractVideoId(url)
        ImportInspection(
            isSupported = videoId != null,
            formatLabel = "YouTube",
            requiresNetwork = true
        )
    }

    override suspend fun import(input: ImportInput): ImportResult = withContext(Dispatchers.IO) {
        val url = input.url
            ?: return@withContext ImportResult.Failure(ImportError.MalformedContent, "No YouTube URL provided")

        val videoId = youTubeRepository.extractVideoId(url)
            ?: return@withContext ImportResult.Failure(ImportError.UnsupportedFormat, "Invalid YouTube video URL")

        try {
            val title = youTubeRepository.fetchVideoTitle(videoId)
            val captionResult = youTubeRepository.fetchCaptions(videoId)

            if (!captionResult.success || captionResult.transcript.isBlank()) {
                val errMessage = captionResult.error ?: "Transcript unavailable"
                val importError = when {
                    errMessage.contains("private", ignoreCase = true) || errMessage.contains("unavailable", ignoreCase = true) ->
                        ImportError.PrivateContent
                    errMessage.contains("rate", ignoreCase = true) || errMessage.contains("429") ->
                        ImportError.RateLimited
                    else -> ImportError.TranscriptUnavailable
                }

                val fallback = when (importError) {
                    ImportError.RateLimited -> FallbackAction.RETRY_LATER
                    ImportError.PrivateContent -> FallbackAction.IMPORT_AUDIO
                    else -> FallbackAction.TRANSCRIBE_LOCALLY
                }

                return@withContext ImportResult.Failure(
                    error = importError,
                    rawMessage = errMessage,
                    retryable = importError == ImportError.RateLimited,
                    fallbackAction = fallback
                )
            }

            val text = captionResult.transcript.trim()
            val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size

            ImportResult.Success(
                text = text,
                title = title,
                format = "YouTube",
                wordCount = wordCount,
                charCount = text.length
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                error = ImportError.NetworkUnavailable,
                rawMessage = e.message ?: "Failed to connect to YouTube",
                retryable = true,
                fallbackAction = FallbackAction.RETRY_LATER
            )
        }
    }
}
