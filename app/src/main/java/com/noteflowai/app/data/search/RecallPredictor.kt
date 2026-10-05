package com.noteflowai.app.data.search

import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.noteDisplayTitle
import com.noteflowai.app.data.temporal.TemporalIndex
import com.noteflowai.app.data.search.ConflictDetector

/**
 * Proactive Recall: surfaces stale concepts and follow-up ideas the user hasn't revisited.
 *
 * Uses the TemporalIndex to find concepts that appeared in notes but haven't been
 * mentioned recently, and the ConceptGraph to find related follow-up concepts.
 */
class RecallPredictor(
    private val temporalIndex: TemporalIndex,
    private val conceptGraph: ConceptGraphRepository
) {
    private val conflictDetector = ConflictDetector(temporalIndex, conceptGraph)

    data class RecallSuggestion(
        val concept: String,
        val type: RecallType,
        val detail: String,
        val relatedNotes: List<String>,
        val stalenessDays: Long
    )

    enum class RecallType {
        STALE_CONCEPT,       // concept not seen in N days
        FOLLOW_UP,           // related concept that was discussed but not expanded
        UNRESOLVED_IDEA,     // concept with only one appearance (never revisited)
        CONFLICT_DETECTED    // contradicting views on the same concept
    }

    companion object {
        /** Minimum days without activity before a concept is considered stale. */
        private const val STALE_THRESHOLD_DAYS = 7L

        /** Maximum suggestions to return. */
        private const val MAX_SUGGESTIONS = 5
    }

    /**
     * Find recall suggestions based on temporal patterns.
     *
     * @param currentQuery the user's current search query (to filter relevant suggestions)
     * @param recentNoteFiles notes the user has recently interacted with
     * @return list of recall suggestions, sorted by priority
     */
    fun findRecallSuggestions(
        currentQuery: String = "",
        recentNoteFiles: Set<String> = emptySet()
    ): List<RecallSuggestion> {
        val now = System.currentTimeMillis()
        val oneDayMs = 24L * 60 * 60 * 1000
        val suggestions = mutableListOf<RecallSuggestion>()

        // Get all concepts with temporal data
        val allConcepts = conceptGraph.getAllConcepts()

        for (concept in allConcepts) {
            val evolution = temporalIndex.getEvolution(concept.canonicalForm) ?: continue
            val daysSinceLastSeen = (now - evolution.lastSeen) / oneDayMs

            // Filter by relevance to current query if provided
            if (currentQuery.isNotBlank()) {
                val queryLower = currentQuery.lowercase(java.util.Locale.ROOT)
                val conceptLower = concept.canonicalForm.lowercase(java.util.Locale.ROOT)
                val displayLower = concept.displayForm.lowercase(java.util.Locale.ROOT)
                if (!conceptLower.contains(queryLower) && !queryLower.contains(conceptLower) &&
                    !displayLower.contains(queryLower) && !queryLower.contains(displayLower)) {
                    continue
                }
            }

            // Skip if concept was seen recently
            if (daysSinceLastSeen < STALE_THRESHOLD_DAYS) continue

            // Skip if the concept's notes are all in recentNoteFiles (user is already engaged)
            if (evolution.appearances.all { it.noteFileName in recentNoteFiles }) continue

            val relatedNotes = evolution.appearances.map { it.noteFileName }.distinct()

            when {
                // Single appearance = unresolved idea
                evolution.noteCount == 1 -> {
                    suggestions.add(RecallSuggestion(
                        concept = concept.displayForm,
                        type = RecallType.UNRESOLVED_IDEA,
                        detail = "You wrote about ${concept.displayForm} once but never revisited it.",
                        relatedNotes = relatedNotes,
                        stalenessDays = daysSinceLastSeen
                    ))
                }
                // Multiple appearances but long gap = stale concept
                daysSinceLastSeen > STALE_THRESHOLD_DAYS * 2 -> {
                    suggestions.add(RecallSuggestion(
                        concept = concept.displayForm,
                        type = RecallType.STALE_CONCEPT,
                        detail = "You haven't explored ${concept.displayForm} in $daysSinceLastSeen days. " +
                                "Last mentioned in ${relatedNotes.last().noteDisplayTitle()}.",
                        relatedNotes = relatedNotes,
                        stalenessDays = daysSinceLastSeen
                    ))
                }
                // Moderate gap = potential follow-up
                else -> {
                    // Check for related concepts that could be follow-ups
                    val related = conceptGraph.getRelatedConcepts(concept.canonicalForm).take(3)
                    val unexplored = related.filter { (relConcept, _) ->
                        temporalIndex.getEvolution(relConcept)?.noteCount ?: 0 <= 1
                    }
                    if (unexplored.isNotEmpty()) {
                        suggestions.add(RecallSuggestion(
                            concept = concept.displayForm,
                            type = RecallType.FOLLOW_UP,
                            detail = "Related to ${concept.displayForm}: you touched on " +
                                    "${unexplored.first().first} but haven't explored it further.",
                            relatedNotes = relatedNotes,
                            stalenessDays = daysSinceLastSeen
                        ))
                    }
                }
            }
        }

        // Add conflict detections
        val conflicts = conflictDetector.findConflicts(maxResults = 2)
        for (conflict in conflicts) {
            suggestions.add(
                RecallSuggestion(
                    concept = conflict.concept,
                    type = RecallType.CONFLICT_DETECTED,
                    detail = "Conflicting views: ${conflict.reason}",
                    relatedNotes = listOf(conflict.noteA, conflict.noteB),
                    stalenessDays = 0L
                )
            )
        }

        return suggestions
            .sortedByDescending { it.stalenessDays }  // most stale first
            .take(MAX_SUGGESTIONS)
    }
}
