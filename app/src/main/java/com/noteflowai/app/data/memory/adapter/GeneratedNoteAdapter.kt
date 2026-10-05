package com.noteflowai.app.data.memory.adapter

import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import java.util.UUID

/**
 * Adapts AI-generated notes into SourceSegments.
 *
 * Generated notes must be clearly marked as generated content and must
 * link back to their originating source segments if they were derived
 * from existing content (e.g., summaries, digests).
 */
object GeneratedNoteAdapter {

    /**
     * Create a SourceSegment for an AI-generated note.
     *
     * @param note The generated NoteFile.
     * @param originatingSourceIds Source IDs this note was derived from (nullable).
     * @param generationModel The model used to generate the note (nullable).
     * @return A SourceSegment marked as generated content.
     */
    fun adapt(
        note: NoteFile,
        originatingSourceIds: List<String>? = null,
        generationModel: String? = null
    ): SourceSegment? {
        val content = note.content
        if (content.isBlank()) return null

        // Gson escaping: hand-built interpolation produced malformed JSON on
        // any quote/backslash in model names or source ids.
        val metadataMap = mutableMapOf<String, Any?>("generated" to true)
        generationModel?.let { metadataMap["model"] = it }
        originatingSourceIds?.let { metadataMap["originatingSourceIds"] = it }
        val metadata = com.google.gson.Gson().toJson(metadataMap)

        return SourceSegment(
            id = "generated_${note.fileName}_${UUID.randomUUID()}",
            sourceId = note.fileName,
            sourceType = SourceType.GENERATED_NOTE,
            text = content.trim(),
            normalizedText = content.trim().lowercase(),
            isOriginalContent = false, // Generated content is NOT original
            parentSegmentId = originatingSourceIds?.firstOrNull(),
            metadataJson = metadata
        )
    }

    /**
     * Create SourceSegments for a generated note, split by paragraphs.
     */
    fun adaptByParagraph(
        note: NoteFile,
        originatingSourceIds: List<String>? = null,
        generationModel: String? = null
    ): List<SourceSegment> {
        val content = note.content
        if (content.isBlank()) return emptyList()

        val paragraphs = content.split(Regex("\n\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (paragraphs.isEmpty()) {
            val single = adapt(note, originatingSourceIds, generationModel)
            return if (single != null) listOf(single) else emptyList()
        }

        return paragraphs.mapIndexed { index, paragraph ->
            SourceSegment(
                id = "generated_${note.fileName}_p${index}_${UUID.randomUUID()}",
                sourceId = note.fileName,
                sourceType = SourceType.GENERATED_NOTE,
                text = paragraph,
                normalizedText = paragraph.lowercase(),
                isOriginalContent = false,
                parentSegmentId = originatingSourceIds?.firstOrNull(),
                metadataJson = com.google.gson.Gson().toJson(mapOf(
                    "generated" to true,
                    "paragraphIndex" to index,
                    "model" to (generationModel ?: "unknown"),
                    "originatingSourceIds" to originatingSourceIds
                ))
            )
        }
    }
}
