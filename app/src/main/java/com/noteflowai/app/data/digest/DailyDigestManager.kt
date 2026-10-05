package com.noteflowai.app.data.digest

import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.search.NoteSearchIndex
import com.noteflowai.app.data.search.RecallPredictor
import com.noteflowai.app.data.temporal.TemporalIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Assembles a DailyDigest from existing data layer components.
 * Runs entirely on-device.
 */
class DailyDigestManager(
    private val temporalIndex: TemporalIndex,
    private val conceptGraph: ConceptGraphRepository,
    private val searchIndex: NoteSearchIndex
) {

    suspend fun generateDigest(): DailyDigest = withContext(Dispatchers.IO) {
        val recentConcepts = temporalIndex.getRecentConcepts(noteCount = 10)
        val predictor = RecallPredictor(temporalIndex, conceptGraph)
        val recallSuggestions = predictor.findRecallSuggestions()

        // Recent activity: top concepts from recent notes
        val recentActivity = recentConcepts.map { (concept, count) ->
            val evolution = temporalIndex.getEvolution(concept)
            DailyDigest.RecentItem(
                noteTitle = concept.replaceFirstChar { it.uppercase() },
                concepts = listOf(concept),
                summary = evolution?.summary ?: "Appeared in $count notes"
            )
        }

        // Open questions: UNRESOLVED_IDEA and FOLLOW_UP suggestions
        val openQuestions = recallSuggestions
            .filter { it.type == RecallPredictor.RecallType.UNRESOLVED_IDEA || it.type == RecallPredictor.RecallType.FOLLOW_UP }
            .map { suggestion ->
                DailyDigest.OpenQuestion(
                    concept = suggestion.concept,
                    detail = suggestion.detail,
                    fromNote = suggestion.relatedNotes.firstOrNull() ?: "unknown"
                )
            }

        // Stale concepts: STALE_CONCEPT suggestions
        val staleConcepts = recallSuggestions
            .filter { it.type == RecallPredictor.RecallType.STALE_CONCEPT }
            .map { suggestion ->
                DailyDigest.StaleItem(
                    concept = suggestion.concept,
                    daysSinceLastSeen = suggestion.stalenessDays,
                    relatedNotes = suggestion.relatedNotes
                )
            }

        // Hidden connections: concepts with high co-occurrence not obviously related
        val allConcepts = conceptGraph.getAllConcepts()
        val hiddenConnections = mutableListOf<DailyDigest.ConnectionItem>()
        for (concept in allConcepts.take(20)) {
            val related = conceptGraph.getRelatedConcepts(concept.canonicalForm)
            for ((relatedConcept, strength) in related) {
                if (strength > 0.5f && hiddenConnections.size < 5) {
                    hiddenConnections.add(
                        DailyDigest.ConnectionItem(
                            conceptA = concept.displayForm,
                            conceptB = relatedConcept,
                            strength = strength,
                            reason = "Co-occurs in ${concept.noteCount} notes"
                        )
                    )
                }
            }
        }

        DailyDigest(
            recentActivity = recentActivity,
            openQuestions = openQuestions,
            staleConcepts = staleConcepts,
            hiddenConnections = hiddenConnections.distinctBy { "${it.conceptA}-${it.conceptB}" }
        )
    }
}
