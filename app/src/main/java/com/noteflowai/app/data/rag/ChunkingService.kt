package com.noteflowai.app.data.rag

import kotlin.math.ceil
import kotlin.math.max

data class Chunk(
    val noteId: String,
    val title: String,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val createdDate: String = "",
    val modifiedDate: String = ""
)

class ChunkingService {

    private fun estimateTokens(text: String): Int = ceil(text.length / 4.0).toInt()

    fun chunkNote(
        noteId: String,
        title: String,
        content: String,
        createdDate: String = "",
        modifiedDate: String = "",
        targetChunkTokens: Int = 512,
        overlapRatio: Double = 0.15,
    ): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val targetChunkChars = targetChunkTokens * 4
        val overlapChars = (targetChunkChars * overlapRatio).toInt()

        // Hard-split over-long paragraphs first: a single 10k-char paragraph
        // used to bypass the chunk limit and fill the whole prompt budget.
        val paragraphs = content
            .split(Regex("\n\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { splitLongParagraph(it, targetChunkChars) }

        val chunks = mutableListOf<Chunk>()

        var buffer = ""
        var startOffset = 0
        // Forward-only search cursor: indexOf from 0 returned the FIRST
        // occurrence for duplicate paragraphs, misaligning every later
        // citation/highlight offset.
        var searchFrom = 0

        for (para in paragraphs) {
            if (buffer.isEmpty()) {
                val idx = content.indexOf(para, searchFrom)
                startOffset = if (idx >= 0) {
                    searchFrom = idx + para.length
                    idx
                } else {
                    0
                }
            }

            buffer += if (buffer.isEmpty()) para else "\n\n$para"

            if (estimateTokens(buffer) >= targetChunkTokens) {
                val endOffset = startOffset + buffer.length
                chunks += Chunk(
                    noteId = noteId,
                    title = title,
                    text = buffer,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    createdDate = createdDate,
                    modifiedDate = modifiedDate
                )

                val overlapStart = max(0, buffer.length - overlapChars)
                buffer = buffer.substring(overlapStart).trimStart()
                startOffset = endOffset - buffer.length
            }
        }

        if (buffer.isNotEmpty()) {
            val endOffset = startOffset + buffer.length
            chunks += Chunk(
                noteId = noteId,
                title = title,
                text = buffer,
                startOffset = startOffset,
                endOffset = endOffset,
                createdDate = createdDate,
                modifiedDate = modifiedDate
            )
        }

        return chunks
    }

    /**
     * Split a paragraph exceeding the chunk budget on word boundaries so no
     * single paragraph can fill the prompt alone.
     */
    private fun splitLongParagraph(paragraph: String, maxChars: Int): List<String> {
        if (paragraph.length <= maxChars) return listOf(paragraph)
        val parts = mutableListOf<String>()
        var remaining = paragraph
        while (remaining.length > maxChars) {
            var cut = remaining.lastIndexOf(' ', maxChars)
            if (cut <= 0) cut = maxChars
            parts.add(remaining.substring(0, cut).trim())
            remaining = remaining.substring(cut).trim()
        }
        if (remaining.isNotEmpty()) parts.add(remaining)
        return parts
    }
}
