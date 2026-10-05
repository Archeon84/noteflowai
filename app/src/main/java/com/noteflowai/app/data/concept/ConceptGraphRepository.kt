package com.noteflowai.app.data.concept

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
 * File-based persistence for the concept graph.
 * Stores in [Context.filesDir]/graph/concept_graph.json
 */
class ConceptGraphRepository(private val extractor: ConceptExtractor) {

    companion object {
        private const val TAG = "ConceptGraphRepository"
        private const val DIR_NAME = "graph"
        private const val FILE_NAME = "concept_graph.json"

        @Volatile
        private var INSTANCE: ConceptGraphRepository? = null

        fun getInstance(context: Context): ConceptGraphRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConceptGraphRepository(
                    ConceptExtractor.getInstance(context)
                ).also { INSTANCE = it }
            }
        }
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val _graph = MutableStateFlow(ConceptGraph())
    val graph: StateFlow<ConceptGraph> = _graph.asStateFlow()

    fun loadFromDisk(context: Context): Boolean {
        val file = getFile(context)
        if (!file.exists()) return false
        return try {
            val raw = file.readText()
            val graph = gson.fromJson(raw, ConceptGraph::class.java)
            if (graph != null) {
                _graph.value = graph
                Log.i(TAG, "Loaded concept graph: ${graph.nodes.size} nodes, ${graph.edges.size} edges")
                true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load concept graph: ${e.message}")
            false
        }
    }

    fun persistToDisk(context: Context) {
        val file = getFile(context)
        try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(_graph.value))
            Log.i(TAG, "Persisted concept graph: ${_graph.value.nodes.size} nodes")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist concept graph: ${e.message}")
        }
    }

    /**
     * Rebuild the concept graph from all notes.
     */
    fun rebuildGraph(notes: List<NoteFile>, context: Context) {
        val start = System.currentTimeMillis()
        val corpusConcepts = extractor.extractCorpusConcepts(notes)

        // Index notes once: the old notes.find{} per extraction was O(N·M).
        val notesByName = notes.associateBy { it.fileName }

        // Build nodes
        val nodes = mutableMapOf<String, ConceptNode>()
        for ((concept, extractions) in corpusConcepts) {
            val display = extractions.maxByOrNull { it.importance }?.displayForm ?: concept
            val type = extractions.maxByOrNull { it.importance }?.type
                ?: ConceptExtractor.ConceptType.THEME
            val sampleNotes = extractions.map { it.sourceNote }.distinct().take(5)

            // Compute firstSeen/lastSeen from NoteFile timestamps
            var firstSeen = Long.MAX_VALUE
            var lastSeen = 0L
            for (extraction in extractions) {
                val note = notesByName[extraction.sourceNote]
                if (note != null) {
                    if (note.lastModifiedEpoch > 0L) {
                        firstSeen = minOf(firstSeen, note.lastModifiedEpoch)
                        lastSeen = maxOf(lastSeen, note.lastModifiedEpoch)
                    }
                }
            }
            if (firstSeen == Long.MAX_VALUE) firstSeen = 0L

            nodes[concept] = ConceptNode(
                canonicalForm = concept,
                displayForm = display,
                type = type,
                noteCount = extractions.size,
                importance = extractions.maxOf { it.importance },
                sampleNotes = sampleNotes,
                firstSeen = firstSeen,
                lastSeen = lastSeen
            )
        }

        // Build edges (concepts that co-occur in the same notes)
        val edges = mutableListOf<ConceptEdge>()
        val conceptByNote = mutableMapOf<String, MutableSet<String>>()
        for ((concept, extractions) in corpusConcepts) {
            for (extraction in extractions) {
                conceptByNote.getOrPut(extraction.sourceNote) { mutableSetOf() }.add(concept)
            }
        }

        val edgeCounts = mutableMapOf<Pair<String, String>, Int>()
        for ((_, conceptSet) in conceptByNote) {
            val conceptList = conceptSet.toList()
            for (i in conceptList.indices) {
                for (j in i + 1 until conceptList.size) {
                    val key = if (conceptList[i] < conceptList[j])
                        conceptList[i] to conceptList[j]
                    else
                        conceptList[j] to conceptList[i]
                    edgeCounts[key] = (edgeCounts[key] ?: 0) + 1
                }
            }
        }

        for ((pair, count) in edgeCounts) {
            val noteCountA = nodes[pair.first]?.noteCount ?: 1
            val noteCountB = nodes[pair.second]?.noteCount ?: 1
            val strength = (count.toFloat() / (noteCountA + noteCountB - count)).coerceIn(0.1f, 1.0f)
            if (strength >= 0.2f) {  // Only meaningful connections
                edges.add(ConceptEdge(
                    sourceConcept = pair.first,
                    targetConcept = pair.second,
                    strength = strength,
                    coOccurrenceCount = count
                ))
            }
        }

        _graph.value = ConceptGraph(
            nodes = nodes,
            edges = edges.sortedByDescending { it.strength },
            lastComputed = System.currentTimeMillis()
        )
        persistToDisk(context)

        val elapsed = System.currentTimeMillis() - start
        Log.i(TAG, "Concept graph built: ${nodes.size} nodes, ${edges.size} edges in ${elapsed}ms")
    }

    fun getConcepts(query: String): List<ConceptNode> {
        return _graph.value.searchConcepts(query)
    }

    /** Get all concepts in the graph (for proactive recall, temporal analysis). */
    fun getAllConcepts(): List<ConceptNode> {
        return _graph.value.nodes.values.toList()
    }

    fun getNotesForConcept(concept: String): List<String> {
        return _graph.value.getNotesForConcept(concept)
    }

    fun getRelatedConcepts(concept: String): List<Pair<String, Float>> {
        return _graph.value.getRelatedConcepts(concept)
    }

    fun nodeCount(): Int = _graph.value.nodes.size
    fun edgeCount(): Int = _graph.value.edges.size

    private fun getFile(context: Context): File {
        return File(context.filesDir, "$DIR_NAME/$FILE_NAME")
    }
}
