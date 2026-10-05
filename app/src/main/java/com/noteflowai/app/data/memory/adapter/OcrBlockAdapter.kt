package com.noteflowai.app.data.memory.adapter

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import java.util.UUID

/**
 * Adapts OCR-extracted text into SourceSegments.
 *
 * OCR text comes from ML Kit text recognition via the ScanScreen.
 * Each OCR scan produces a single block of text; if the result contains
 * multiple detected text blocks, they can be split by line breaks.
 */
object OcrBlockAdapter {

    /**
     * Create a SourceSegment from OCR-extracted text.
     *
     * @param sourceId The image file name or identifier.
     * @param text The OCR-extracted text.
     * @param language The language used for OCR recognition.
     * @param imageUri Optional URI of the source image.
     * @return A SourceSegment, or null if text is blank.
     */
    fun adapt(
        sourceId: String,
        text: String,
        language: String? = null,
        imageUri: String? = null
    ): SourceSegment? {
        if (text.isBlank()) return null

        return SourceSegment(
            id = "ocr_${sourceId}_${UUID.randomUUID()}",
            sourceId = sourceId,
            sourceType = SourceType.OCR,
            text = text.trim(),
            normalizedText = text.trim().lowercase(),
            blockId = "ocr_full",
            language = language,
            isOriginalContent = true,
            metadataJson = imageUri?.let { """{"imageUri":"$it"}""" }
        )
    }

    /**
     * Create multiple SourceSegments by splitting OCR text into lines/blocks.
     * Useful when OCR detects multiple distinct text regions.
     */
    fun adaptByBlock(
        sourceId: String,
        text: String,
        language: String? = null,
        imageUri: String? = null
    ): List<SourceSegment> {
        if (text.isBlank()) return emptyList()

        val blocks = text.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (blocks.size <= 1) {
            val single = adapt(sourceId, text, language, imageUri)
            return if (single != null) listOf(single) else emptyList()
        }

        return blocks.mapIndexed { index, block ->
            SourceSegment(
                id = "ocr_${sourceId}_b${index}_${UUID.randomUUID()}",
                sourceId = sourceId,
                sourceType = SourceType.OCR,
                text = block,
                normalizedText = block.lowercase(),
                blockId = "ocr_block_$index",
                language = language,
                isOriginalContent = true,
                metadataJson = """{"imageUri":${imageUri?.let { "\"$it\"" } ?: "null"},"blockIndex":$index}"""
            )
        }
    }
}
