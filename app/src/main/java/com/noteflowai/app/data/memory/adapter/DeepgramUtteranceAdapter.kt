package com.noteflowai.app.data.memory.adapter

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import java.util.UUID

/**
 * Adapts Deepgram transcription output into SourceSegments.
 *
 * The current DeepgramRepository returns a single text string per transcription.
 * This adapter creates one SourceSegment covering the full transcript.
 * When Deepgram's paragraph-level or utterance-level data becomes available
 * (the DeepgramParagraphs model exists but isn't fully utilized), this adapter
 * should be extended to produce per-utterance segments with timestamps and speakers.
 */
object DeepgramUtteranceAdapter {

    /**
     * Create a SourceSegment from a Deepgram transcription result.
     *
     * @param sourceId The recording file name or identifier.
     * @param text The full transcript text from Deepgram.
     * @param language Detected language code.
     * @return A single SourceSegment covering the full transcript.
     */
    fun adapt(
        sourceId: String,
        text: String,
        language: String? = null
    ): SourceSegment? {
        if (text.isBlank()) return null

        return SourceSegment(
            id = "deepgram_${sourceId}_${UUID.randomUUID()}",
            sourceId = sourceId,
            sourceType = SourceType.AUDIO,
            text = text.trim(),
            normalizedText = text.trim().lowercase(),
            transcriptionEngine = "deepgram",
            language = language,
            isOriginalContent = true,
            metadataJson = """{"engine":"deepgram"}"""
        )
    }

    /**
     * Create multiple SourceSegments by splitting transcript into paragraphs.
     * Deepgram's paragraph detection can provide natural break points.
     */
    fun adaptByParagraph(
        sourceId: String,
        text: String,
        language: String? = null
    ): List<SourceSegment> {
        if (text.isBlank()) return emptyList()

        return text.split(Regex("\n\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapIndexed { index, paragraph ->
                SourceSegment(
                    id = "deepgram_${sourceId}_p${index}_${UUID.randomUUID()}",
                    sourceId = sourceId,
                    sourceType = SourceType.AUDIO,
                    text = paragraph,
                    normalizedText = paragraph.lowercase(),
                    transcriptionEngine = "deepgram",
                    language = language,
                    isOriginalContent = true,
                    metadataJson = """{"engine":"deepgram","paragraphIndex":$index}"""
                )
            }
    }
}
