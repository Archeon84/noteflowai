package com.noteflowai.app.data.graph

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.noteflowai.app.data.NoteFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * File-based persistence for the note knowledge graph.
 *
 * Stores connections in [Context.filesDir]/graph/note_graph.json.
 * Provides a reactive [StateFlow] for UI observation.
 */
class NoteGraphRepository {

    companion object {
        private const val TAG = "NoteGraphRepository"
        private const val DIR_NAME = "graph"
        private const val FILE_NAME = "note_graph.json"
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val _graph = MutableStateFlow(NoteGraph())
    val graph: StateFlow<NoteGraph> = _graph.asStateFlow()

    /**
     * Load the graph from disk.
     */
    fun loadFromDisk(context: Context): Boolean {
        val file = getFile(context)
        if (!file.exists()) return false

        return try {
            val raw = file.readText()
            val graph = gson.fromJson(raw, NoteGraph::class.java)
            if (graph != null) {
                _graph.value = graph
                Log.i(TAG, "Loaded graph: ${graph.connections.size} connections")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load graph: ${e.message}")
            false
        }
    }

    /**
     * Persist the current graph to disk.
     */
    fun persistToDisk(context: Context) {
        val file = getFile(context)
        try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(_graph.value))
            Log.i(TAG, "Persisted graph: ${_graph.value.connections.size} connections")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist graph: ${e.message}")
        }
    }

    /**
     * Update the entire graph (after auto-linking recomputation).
     */
    fun updateGraph(graph: NoteGraph, context: Context) {
        _graph.value = graph
        persistToDisk(context)
    }

    /**
     * Get connections for a specific note.
     */
    fun getConnections(fileName: String): List<NoteConnection> {
        return _graph.value.connectionsFor(fileName)
    }

    /**
     * Get connected note fileNames for a given note, sorted by strength.
     */
    fun getConnectedNotes(fileName: String): List<Pair<String, Float>> {
        return _graph.value.connectedNotes(fileName)
    }

    /**
     * Add a manual connection between two notes.
     */
    fun addConnection(
        sourceFileName: String,
        targetFileName: String,
        relationship: String = "related",
        context: Context
    ) {
        val current = _graph.value
        // Check if connection already exists
        val exists = current.connections.any {
            (it.sourceFileName == sourceFileName && it.targetFileName == targetFileName) ||
            (it.sourceFileName == targetFileName && it.targetFileName == sourceFileName)
        }
        if (exists) return

        val connection = NoteConnection(
            sourceFileName = sourceFileName,
            targetFileName = targetFileName,
            relationship = relationship,
            strength = 1.0f,  // Manual connections are always strongest
            source = "manual"
        )

        _graph.value = current.copy(
            connections = current.connections + connection
        )
        persistToDisk(context)
    }

    /**
     * Remove a manual connection between two notes.
     */
    fun removeConnection(
        sourceFileName: String,
        targetFileName: String,
        context: Context
    ) {
        val current = _graph.value
        _graph.value = current.copy(
            connections = current.connections.filter {
                !(it.sourceFileName == sourceFileName && it.targetFileName == targetFileName &&
                    it.source == "manual")
            }
        )
        persistToDisk(context)
    }

    /**
     * Remove all connections (auto + manual) for a specific note.
     */
    fun removeNoteFromGraph(fileName: String, context: Context) {
        val current = _graph.value
        _graph.value = current.copy(
            connections = current.connections.filter {
                it.sourceFileName != fileName && it.targetFileName != fileName
            }
        )
        persistToDisk(context)
    }

    private fun getFile(context: Context): File {
        return File(context.filesDir, "$DIR_NAME/$FILE_NAME")
    }
}
