package com.noteflowai.app.data

import androidx.compose.runtime.Immutable

@Immutable
data class NoteFile(
    val fileName: String,
    val lastModified: String,
    val preview: String,
    val content: String = "",
    val category: String = "Uncategorized",
    val pinned: Boolean = false,
    val tags: List<String> = emptyList(),
    val lastModifiedEpoch: Long = 0L,
    val createdAt: String = "",
    val createdAtEpoch: Long = 0L
)

/**
 * Returns a copy with every String field guaranteed non-null.
 * Gson uses Unsafe to allocate instances, bypassing constructors, so fields
 * without JSON values arrive as null even though Kotlin declares them non-null.
 */
fun NoteFile.sanitized() = copy(
    fileName = fileName ?: "",
    lastModified = lastModified ?: "",
    preview = preview ?: "",
    content = content ?: "",
    category = category ?: "Uncategorized",
    createdAt = createdAt ?: "",
)

/** Clean display title from a note filename — strips .json/.txt extension and replaces underscores with spaces. */
val NoteFile.displayTitle: String
    get() = fileName.noteDisplayTitle()

/** Clean display title from a note filename string — strips .json/.txt extension and replaces underscores with spaces. */
fun String.noteDisplayTitle(): String = this
    .removeSuffix(".json")
    .removeSuffix(".txt")
    .replace("_", " ")