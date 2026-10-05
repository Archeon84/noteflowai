package com.noteflowai.app.data.memory.adapter

import com.google.gson.Gson
import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.displayTitle
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import java.util.UUID

/**
 * Adapts a NoteFile into one or more SourceSegments.
 *
 * Notes are split into paragraph-level segments to enable granular citations.
 * Each paragraph becomes a separate SourceSegment with a blockId for navigation.
 * Injects creation and modification metadata directly into the text chunk for temporal awareness.
 */
object NoteBlockAdapter {

    private val gson = Gson()
    private const val MAX_CHUNK_CHARS = 2000
    private val HEADER_REGEX = Regex("""^#{1,6}\s+.*""")

    /**
     * Create SourceSegments from a NoteFile using semantic, list-aware chunking.
     * Merges short consecutive paragraphs and list items up to MAX_CHUNK_CHARS (~512 tokens)
     * while respecting section headers.
     *
     * @param note The NoteFile to adapt.
     * @return List of SourceSegments. Empty if note has no content.
     */
    fun adapt(note: NoteFile): List<SourceSegment> {
        val content = note.content ?: ""
        if (content.isBlank()) return emptyList()

        val sourceId = note.fileName ?: return emptyList()
        val chunks = chunkContent(content)

        val createdDate = (note.createdAt ?: "").ifBlank { note.lastModified ?: "" }
        val modifiedDate = note.lastModified ?: ""
        val createdEpoch = if (note.createdAtEpoch > 0) note.createdAtEpoch else note.lastModifiedEpoch
        val modifiedEpoch = if (note.lastModifiedEpoch > 0) note.lastModifiedEpoch else System.currentTimeMillis()
        val title = note.displayTitle

        val baseMeta = mapOf(
            "title" to title,
            "createdDate" to createdDate,
            "modifiedDate" to modifiedDate,
            "createdAtEpoch" to createdEpoch,
            "modifiedAtEpoch" to modifiedEpoch,
            "category" to note.category,
            "tags" to note.tags
        )

        val metadataHeader = "[Note: \"$title\" | Created: $createdDate | Modified: $modifiedDate]"

        return chunks.mapIndexed { index, chunk ->
            val chunkText = "$metadataHeader\n$chunk"
            SourceSegment(
                id = "note_${sourceId}_block${index}_${UUID.randomUUID()}",
                sourceId = sourceId,
                sourceType = SourceType.NOTE,
                text = chunkText,
                normalizedText = chunkText.lowercase(),
                blockId = "block_$index",
                createdAt = createdEpoch,
                updatedAt = modifiedEpoch,
                isOriginalContent = true,
                metadataJson = gson.toJson(baseMeta + ("blockIndex" to index))
            )
        }
    }

    /**
     * Group note content into semantic chunks respecting headers and list boundaries.
     */
    fun chunkContent(content: String, maxChars: Int = MAX_CHUNK_CHARS): List<String> {
        val rawBlocks = content.split(Regex("\n\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (rawBlocks.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()

        for (block in rawBlocks) {
            val isHeader = isHeaderBlock(block)

            if (isHeader && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder(block)
            } else if (currentChunk.isNotEmpty() && currentChunk.length + block.length + 2 > maxChars) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder(block)
            } else {
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append("\n\n")
                }
                currentChunk.append(block)
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks
    }

    private fun isHeaderBlock(block: String): Boolean {
        val firstLine = block.lines().firstOrNull()?.trim() ?: return false
        return firstLine.startsWith("#") && HEADER_REGEX.matches(firstLine)
    }

    /**
     * Create a single SourceSegment for the entire note.
     * Use when paragraph-level granularity is not needed.
     */
    fun adaptAsSingle(note: NoteFile): SourceSegment? {
        val content = note.content ?: ""
        if (content.isBlank()) return null

        val createdDate = (note.createdAt ?: "").ifBlank { note.lastModified ?: "" }
        val modifiedDate = note.lastModified ?: ""
        val createdEpoch = if (note.createdAtEpoch > 0) note.createdAtEpoch else note.lastModifiedEpoch
        val modifiedEpoch = if (note.lastModifiedEpoch > 0) note.lastModifiedEpoch else System.currentTimeMillis()
        val title = note.displayTitle

        val metadataHeader = "[Note: \"$title\" | Created: $createdDate | Modified: $modifiedDate]"
        val chunkText = "$metadataHeader\n${content.trim()}"

        return SourceSegment(
            id = "note_${note.fileName}_${UUID.randomUUID()}",
            sourceId = note.fileName,
            sourceType = SourceType.NOTE,
            text = chunkText,
            normalizedText = chunkText.lowercase(),
            blockId = "full",
            createdAt = createdEpoch,
            updatedAt = modifiedEpoch,
            isOriginalContent = true,
            metadataJson = gson.toJson(
                mapOf(
                    "title" to title,
                    "createdDate" to createdDate,
                    "modifiedDate" to modifiedDate,
                    "createdAtEpoch" to createdEpoch,
                    "modifiedAtEpoch" to modifiedEpoch,
                    "category" to note.category,
                    "tags" to note.tags
                )
            )
        )
    }
}
