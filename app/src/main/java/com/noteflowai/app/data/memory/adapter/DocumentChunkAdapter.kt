package com.noteflowai.app.data.memory.adapter

import com.google.gson.Gson
import com.noteflowai.app.data.document.DocumentMetadata
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import java.util.UUID

/**
 * Adapts imported document text into SourceSegments.
 *
 * PDF documents can be split by pages; other formats by paragraphs.
 * The DocumentRepository.ExtractResult provides the full extracted text
 * and metadata about the source file.
 */
object DocumentChunkAdapter {

    private const val MAX_CHUNK_CHARS = 2000

    /**
     * Create SourceSegments from an extracted document.
     * For PDFs, attempts to preserve page information if available.
     * For other formats, splits by paragraphs.
     *
     * @param sourceId The document file name or identifier.
     * @param text The full extracted text.
     * @param format The document format: "pdf", "docx", "html", "txt".
     * @param metadata Document metadata (fileName, wordCount, etc.).
     * @return List of SourceSegments.
     */
    fun adapt(
        sourceId: String,
        text: String,
        format: String,
        metadata: DocumentMetadata? = null
    ): List<SourceSegment> {
        if (text.isBlank()) return emptyList()

        return when (format.lowercase()) {
            "pdf" -> adaptPdf(sourceId, text, metadata)
            else -> adaptByParagraph(sourceId, text, format, metadata)
        }
    }

    /**
     * Create SourceSegments from PDF text, split by page markers if present,
     * otherwise by paragraphs.
     */
    private fun adaptPdf(
        sourceId: String,
        text: String,
        metadata: DocumentMetadata?
    ): List<SourceSegment> {
        // Try to split by page markers (common PDF text extraction pattern)
        val pagePattern = Regex("""(?m)^---\s*Page\s+(\d+)\s*---|(?m)^Page\s+(\d+)""")
        val pageMatches = pagePattern.findAll(text).toList()

        if (pageMatches.isNotEmpty()) {
            // Each marker's page number owns the text AFTER it up to the next
            // marker. Splitting on the pattern (which drops delimiters) yields
            // N+1 pieces for N markers and misaligns every page number, so
            // slice by match ranges instead. Pre-marker text belongs to the
            // first marker's page.
            val bodies = mutableListOf<Pair<Int, String>>()
            val preText = text.substring(0, pageMatches.first().range.first).trim()
            pageMatches.forEachIndexed { index, match ->
                // groupValues[0] is the whole match ("--- Page 2 ---"), never
                // a page number — only capture groups count.
                val pageNumber = match.groupValues.drop(1)
                    .firstOrNull { it.isNotEmpty() }
                    ?.toIntOrNull() ?: (index + 1)
                val start = match.range.last + 1
                val end = pageMatches.getOrNull(index + 1)?.range?.first ?: text.length
                var body = text.substring(start, end).trim()
                if (index == 0 && preText.isNotBlank()) {
                    body = "$preText\n\n$body".trim()
                }
                if (body.isNotBlank()) bodies.add(pageNumber to body)
            }
            return bodies.flatMapIndexed { index, (pageNumber, body) ->
                splitLongBody(body).mapIndexed { chunkIndex, chunk ->
                    SourceSegment(
                        id = "pdf_${sourceId}_page${pageNumber}_${UUID.randomUUID()}",
                        sourceId = sourceId,
                        sourceType = SourceType.PDF,
                        text = chunk,
                        normalizedText = chunk.lowercase(),
                        pageNumber = pageNumber,
                        blockId = "page${pageNumber}_c$chunkIndex",
                        isOriginalContent = true,
                        metadataJson = metadataJson(metadata, "pdf", mapOf("chunkIndex" to index))
                    )
                }
            }
        }

        // No page markers — fall back to paragraph splitting
        return adaptByParagraph(sourceId, text, "pdf", metadata)
    }

    /** Split an over-long body on paragraph boundaries so no chunk blows past the limit. */
    private fun splitLongBody(body: String): List<String> {
        if (body.length <= MAX_CHUNK_CHARS) return listOf(body)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (paragraph in body.split(Regex("\n\n+")).map { it.trim() }.filter { it.isNotBlank() }) {
            if (current.length + paragraph.length < MAX_CHUNK_CHARS) {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(paragraph)
            } else {
                if (current.isNotEmpty()) chunks.add(current.toString())
                current.clear()
                current.append(paragraph)
            }
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks.ifEmpty { listOf(body) }
    }

    private fun metadataJson(
        metadata: DocumentMetadata?,
        format: String,
        extra: Map<String, Any?> = emptyMap()
    ): String? {
        if (metadata == null && extra.isEmpty()) return null
        val map = mutableMapOf<String, Any?>("format" to format)
        metadata?.let { map["fileName"] = it.fileName }
        map.putAll(extra)
        return Gson().toJson(map)
    }

    /**
     * Create SourceSegments by splitting text into paragraphs,
     * merging very short paragraphs into adjacent ones.
     */
    private fun adaptByParagraph(
        sourceId: String,
        text: String,
        format: String,
        metadata: DocumentMetadata?
    ): List<SourceSegment> {
        val paragraphs = text.split(Regex("\n\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (paragraphs.isEmpty()) {
            return listOf(
                SourceSegment(
                    id = "${format}_${sourceId}_full_${UUID.randomUUID()}",
                    sourceId = sourceId,
                    sourceType = when (format.lowercase()) {
                        "pdf" -> SourceType.PDF
                        "html" -> SourceType.DOCUMENT
                        else -> SourceType.DOCUMENT
                    },
                    text = text.trim(),
                    normalizedText = text.trim().lowercase(),
                    isOriginalContent = true,
                    metadataJson = metadataJson(metadata, format)
                )
            )
        }

        // Merge short paragraphs to avoid tiny segments
        val merged = mutableListOf<String>()
        val current = StringBuilder()

        for (paragraph in paragraphs) {
            if (current.length + paragraph.length < MAX_CHUNK_CHARS) {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(paragraph)
            } else {
                if (current.isNotEmpty()) merged.add(current.toString())
                current.clear()
                current.append(paragraph)
            }
        }
        if (current.isNotEmpty()) merged.add(current.toString())

        val sourceType = when (format.lowercase()) {
            "pdf" -> SourceType.PDF
            "html" -> SourceType.DOCUMENT
            else -> SourceType.DOCUMENT
        }

        return merged.mapIndexed { index, chunk ->
            SourceSegment(
                id = "${format}_${sourceId}_c${index}_${UUID.randomUUID()}",
                sourceId = sourceId,
                sourceType = sourceType,
                text = chunk,
                normalizedText = chunk.lowercase(),
                    blockId = "chunk_$index",
                    isOriginalContent = true,
                    metadataJson = metadataJson(metadata, format, mapOf("chunkIndex" to index))
            )
        }
    }
}
