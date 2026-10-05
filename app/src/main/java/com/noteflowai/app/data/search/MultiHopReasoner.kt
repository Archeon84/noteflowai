package com.noteflowai.app.data.search

import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.noteDisplayTitle

/**
 * Chains connections across notes for complex queries.
 *
 * When an answer spans multiple notes, follows the reasoning chain:
 * Note A mentions concept X -> concept X appears in note B -> note B relates to note C
 *
 * Returns the chain with explanations for each hop.
 */
class MultiHopReasoner(
    private val conceptGraph: ConceptGraphRepository,
    private val searchIndex: NoteSearchIndex
) {

    /**
     * A single hop in a multi-hop reasoning chain.
     */
    data class Hop(
        val noteFileName: String,
        val noteTitle: String,
        val reason: String,          // why this note was included
        val viaConcept: String?,     // concept that connected to this note
        val strength: Float
    )

    /**
     * Result of multi-hop reasoning.
     */
    data class MultiHopResult(
        val hops: List<Hop>,
        val chainExplanation: String,  // human-readable explanation of the chain
        val confidence: Float          // overall confidence in the chain
    )

    companion object {
        private const val MAX_HOPS = 4
        private const val MIN_HOP_STRENGTH = 0.3f
    }

    /**
     * Find multi-hop connections starting from initial search results.
     *
     * @param query The original search query
     * @param startNotes The initial BM25/embedding search results
     * @return Multi-hop reasoning chain, or empty if no connections found
     */
    fun findConnections(
        query: String,
        startNotes: List<NoteSearchIndex.SearchResult>
    ): MultiHopResult? {
        if (startNotes.isEmpty()) return null

        val visitedNotes = mutableSetOf<String>()
        val hops = mutableListOf<Hop>()

        // Start with initial results
        for (note in startNotes.take(2)) {
            hops.add(Hop(
                noteFileName = note.fileName,
                noteTitle = note.title,
                reason = "Direct match for query",
                viaConcept = null,
                strength = note.score
            ))
            visitedNotes.add(note.fileName)
        }

        // Memoized per-note concept sets: the old code re-queried the graph
        // for the same target note inside the concepts x notes nest.
        val conceptsCache = mutableMapOf<String, Set<String>>()
        fun conceptsOf(noteFileName: String): Set<String> =
            conceptsCache.getOrPut(noteFileName) {
                conceptGraph.getConcepts(noteFileName.noteDisplayTitle())
                    .map { it.canonicalForm }.toSet()
            }

        // Follow concept connections
        for (i in 0 until MAX_HOPS - hops.size) {
            val lastNote = hops.lastOrNull()?.noteFileName ?: break

            // Find concepts in the last note
            val noteConcepts = conceptGraph.getConcepts(lastNote.noteDisplayTitle())
            if (noteConcepts.isEmpty()) break

            // Find notes connected via the strongest concept
            var bestNext: Pair<String, Float>? = null
            var bestConcept: String? = null

            for (concept in noteConcepts.take(3)) {
                // Use ConceptEdge strength (co-occurrence-based) for related concepts
                val relatedConcepts = conceptGraph.getRelatedConcepts(concept.canonicalForm)

                val relatedNotes = conceptGraph.getNotesForConcept(concept.canonicalForm)
                for (relatedNote in relatedNotes) {
                    if (relatedNote !in visitedNotes) {
                        // Find the strongest edge connecting this concept to any concept in the target note
                        val targetNoteConcepts = conceptsOf(relatedNote)
                        val bestEdgeStrength = relatedConcepts
                            .filter { (relConcept, _) -> relConcept in targetNoteConcepts }
                            .maxOfOrNull { it.second }
                            ?: concept.importance  // fall back to node importance if no direct edge found

                        if (bestEdgeStrength >= MIN_HOP_STRENGTH) {
                            if (bestNext == null || bestEdgeStrength > bestNext.second) {
                                bestNext = relatedNote to bestEdgeStrength
                                bestConcept = concept.displayForm
                            }
                        }
                    }
                }
            }

            if (bestNext == null) break

            visitedNotes.add(bestNext.first)
            hops.add(Hop(
                noteFileName = bestNext.first,
                noteTitle = bestNext.first.noteDisplayTitle(),
                reason = "Connected via concept: $bestConcept",
                viaConcept = bestConcept,
                strength = bestNext.second
            ))
        }

        if (hops.size <= 1) return null  // No actual multi-hop found

        val chainExplanation = buildString {
            appendLine("Reasoning chain:")
            for ((i, hop) in hops.withIndex()) {
                if (i == 0) {
                    appendLine("  ${i + 1}. \"${hop.noteTitle}\" -- ${hop.reason}")
                } else {
                    appendLine("  ${i + 1}. \"${hop.noteTitle}\" -- ${hop.reason}")
                }
            }
        }

        val avgStrength = hops.map { it.strength }.average().toFloat()

        return MultiHopResult(
            hops = hops,
            chainExplanation = chainExplanation,
            confidence = avgStrength
        )
    }
}
