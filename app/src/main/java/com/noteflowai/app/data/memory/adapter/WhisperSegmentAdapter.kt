package com.noteflowai.app.data.memory.adapter

import com.google.gson.Gson
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import java.util.UUID

/**
 * Adapts Whisper transcription output into SourceSegments.
 *
 * The current Whisper bridge returns a single text string per recording.
 * This adapter creates one SourceSegment covering the full transcript.
 * When Whisper's word-level timestamps become available, this adapter
 * should be extended to produce per-utterance segments with startMs/endMs.
 */
object WhisperSegmentAdapter {

    private val gson = Gson()

    /**
     * Create a SourceSegment from a Whisper transcription result.
     *
     * @param sourceId The recording file name or identifier.
     * @param text The full transcript text from Whisper.
     * @param engine Which Whisper engine was used: "whisper_cpp" or "whisper_npu".
     * @param language Detected or specified language code.
     * @param confidence Overall transcription confidence (if available).
     * @return A single SourceSegment covering the full transcript.
     */
    fun adapt(
        sourceId: String,
        text: String,
        engine: String = "whisper_cpp",
        language: String? = null,
        confidence: Float? = null
    ): SourceSegment? {
        if (text.isBlank()) return null

        return SourceSegment(
            id = "whisper_${sourceId}_${UUID.randomUUID()}",
            sourceId = sourceId,
            sourceType = SourceType.AUDIO,
            text = text.trim(),
            normalizedText = text.trim().lowercase(),
            transcriptionEngine = engine,
            language = language,
            confidence = confidence,
            isOriginalContent = true,
            metadataJson = gson.toJson(mapOf("engine" to engine))
        )
    }

    /**
     * Create multiple SourceSegments by splitting transcript into paragraphs.
     * Useful for long recordings where paragraph breaks indicate utterance boundaries.
     */
    fun adaptByParagraph(
        sourceId: String,
        text: String,
        engine: String = "whisper_cpp",
        language: String? = null,
        confidence: Float? = null
    ): List<SourceSegment> {
        if (text.isBlank()) return emptyList()

        return text.split(Regex("\n\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapIndexed { index, paragraph ->
                SourceSegment(
                    id = "whisper_${sourceId}_p${index}_${UUID.randomUUID()}",
                    sourceId = sourceId,
                    sourceType = SourceType.AUDIO,
                    text = paragraph,
                    normalizedText = paragraph.lowercase(),
                    transcriptionEngine = engine,
                    language = language,
                    confidence = confidence,
                    isOriginalContent = true,
                    metadataJson = gson.toJson(mapOf("engine" to engine, "paragraphIndex" to index))
                )
            }
    }
}
