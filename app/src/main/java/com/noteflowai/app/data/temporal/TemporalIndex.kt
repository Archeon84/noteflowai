package com.noteflowai.app.data.temporal

import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.concept.ConceptExtractor
import com.noteflowai.app.data.noteDisplayTitle
import java.io.File
import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import android.util.Log

/**
 * Tracks when concepts appear across notes over time.
 * Enables "idea evolution" queries: how has the user's thinking on topic X changed?
 */
class TemporalIndex(private val extractor: ConceptExtractor) {

    companion object {
        private const val TAG = "TemporalIndex"
        private const val DIR_NAME = "graph"
        private const val FILE_NAME = "temporal_index.json"
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * A concept's appearance in a specific note at a point in time.
     */
    data class ConceptAppearance(
        val noteFileName: String,
        val timestamp: Long,
        val context: String,      // snippet where concept appears
        val importance: Float
    )

    /**
     * Timeline of a concept across notes.
     */
    data class ConceptEvolution(
        val concept: String,
        val appearances: List<ConceptAppearance>,
        val noteCount: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        val summary: String       // generated summary of the evolution
    )

    @Volatile
    private var appearances: Map<String, List<ConceptAppearance>> = emptyMap()

    fun loadFromDisk(context: Context): Boolean {
        val file = getFile(context)
        if (!file.exists()) return false
        return try {
            val raw = file.readText()
            val type = object : TypeToken<Map<String, List<ConceptAppearance>>>() {}.type
            appearances = gson.fromJson(raw, type) ?: emptyMap()
            Log.i(TAG, "Loaded temporal index: ${appearances.size} concepts")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load temporal index: ${e.message}")
            false
        }
    }

    fun persistToDisk(context: Context) {
        val file = getFile(context)
        try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(appearances))
            Log.i(TAG, "Persisted temporal index: ${appearances.size} concepts")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist temporal index: ${e.message}")
        }
    }

    /**
     * Build the temporal index from all notes.
     */
    fun rebuildIndex(notes: List<NoteFile>, context: Context) {
        val start = System.currentTimeMillis()
        val newAppearances = mutableMapOf<String, MutableList<ConceptAppearance>>()

        for (note in notes) {
            val concepts = extractor.extractFromNote(note)
            // Creation time approximates first mention; edit time rewrites
            // history on every save, corrupting "idea evolution" timelines.
            val mentionedAt = note.createdAtEpoch.takeIf { it > 0 } ?: note.lastModifiedEpoch
            for (concept in concepts) {
                newAppearances.getOrPut(concept.canonicalForm) { mutableListOf() }
                    .add(ConceptAppearance(
                        noteFileName = note.fileName,
                        timestamp = mentionedAt,
                        context = concept.context,
                        importance = concept.importance
                    ))
            }
        }

        // Sort each concept's appearances by timestamp
        for ((concept, list) in newAppearances) {
            newAppearances[concept] = list.sortedBy { it.timestamp }.toMutableList()
        }

        appearances = newAppearances
        persistToDisk(context)

        val elapsed = System.currentTimeMillis() - start
        Log.i(TAG, "Temporal index built: ${appearances.size} concepts in ${elapsed}ms")
    }

    /**
     * Get the evolution of a concept over time.
     */
    fun getEvolution(concept: String): ConceptEvolution? {
        val apps = appearances[concept.lowercase()] ?: return null
        if (apps.isEmpty()) return null

        val summary = buildString {
            appendLine("Your thinking on '$concept' has evolved over ${apps.size} notes:")
            for ((i, app) in apps.withIndex()) {
                val timeStr = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
                    .format(java.util.Date(app.timestamp))
                appendLine("  ${i + 1}. $timeStr (${app.noteFileName.noteDisplayTitle()}): ${app.context.take(80)}")
            }
        }

        return ConceptEvolution(
            concept = concept,
            appearances = apps,
            noteCount = apps.map { it.noteFileName }.distinct().size,
            firstSeen = apps.first().timestamp,
            lastSeen = apps.last().timestamp,
            summary = summary
        )
    }

    /**
     * Get concepts that appear in a specific time range.
     */
    fun getConceptsInRange(startMs: Long, endMs: Long): Map<String, List<ConceptAppearance>> {
        return appearances.mapValues { (_, apps) ->
            apps.filter { it.timestamp in startMs..endMs }
        }.filter { it.value.isNotEmpty() }
    }

    /**
     * Get the most active concepts in recent notes.
     */
    fun getRecentConcepts(noteCount: Int = 5): List<Pair<String, Int>> {
        return appearances.map { (concept, apps) ->
            // Recency by timestamp, not insertion order: distinct() on the
            // raw list kept the first-seen order, so takeLast ranked stale
            // concepts as "recent".
            val recentNotes = apps.sortedByDescending { it.timestamp }
                .map { it.noteFileName }
                .distinct()
                .take(noteCount)
            concept to recentNotes.size
        }.sortedByDescending { it.second }
    }

    fun conceptCount(): Int = appearances.size

    private fun getFile(context: Context): File {
        return File(context.filesDir, "$DIR_NAME/$FILE_NAME")
    }
}
