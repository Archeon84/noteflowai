package com.noteflowai.app.data.graph

import androidx.compose.runtime.Immutable

/**
 * Represents a directed connection between two notes.
 */
@Immutable
data class NoteConnection(
    val sourceFileName: String,
    val targetFileName: String,
    val relationship: String,   // "related", "references", "continuation", "manual"
    val strength: Float,        // 0.0 to 1.0 (auto = computed, manual = 1.0)
    val source: String,         // dominant signal: "auto_tags", "auto_content", "auto_similar", "auto_semantic", "auto_concept", "auto_entity", "auto_category", "auto_temporal", "manual"
    val signals: List<String> = emptyList(),  // all contributing signals (per-source attribution)
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * The full knowledge graph of note connections.
 */
@Immutable
data class NoteGraph(
    val connections: List<NoteConnection> = emptyList(),
    val lastComputed: Long = 0L
) {
    /** Get connections originating from a specific note. */
    fun connectionsFrom(fileName: String): List<NoteConnection> =
        connections.filter { it.sourceFileName == fileName }

    /** Get all connections involving a specific note (both directions). */
    fun connectionsFor(fileName: String): List<NoteConnection> =
        connections.filter { it.sourceFileName == fileName || it.targetFileName == fileName }

    /** Get connected note fileNames for a given note, sorted by strength. */
    fun connectedNotes(fileName: String): List<Pair<String, Float>> =
        connectionsFor(fileName)
            .map { conn ->
                val other = if (conn.sourceFileName == fileName) conn.targetFileName else conn.sourceFileName
                other to conn.strength
            }
            .distinctBy { it.first }
            .sortedByDescending { it.second }
}
