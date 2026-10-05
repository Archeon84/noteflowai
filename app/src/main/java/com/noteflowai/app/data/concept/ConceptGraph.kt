package com.noteflowai.app.data.concept

import androidx.compose.runtime.Immutable

/**
 * A concept-centric knowledge graph.
 * Nodes are concepts (ideas, entities, themes), not files.
 * Edges connect concepts that co-occur in the same notes.
 */
@Immutable
data class ConceptNode(
    val canonicalForm: String,
    val displayForm: String,
    val type: ConceptExtractor.ConceptType,
    val noteCount: Int,              // how many notes mention this concept
    val importance: Float,           // corpus-wide importance
    val sampleNotes: List<String>,   // top notes containing this concept
    val firstSeen: Long,             // earliest note timestamp
    val lastSeen: Long               // latest note timestamp
)

@Immutable
data class ConceptEdge(
    val sourceConcept: String,
    val targetConcept: String,
    val strength: Float,             // co-occurrence strength [0,1]
    val coOccurrenceCount: Int       // how many notes contain both
)

@Immutable
data class ConceptGraph(
    val nodes: Map<String, ConceptNode> = emptyMap(),
    val edges: List<ConceptEdge> = emptyList(),
    val lastComputed: Long = 0L
) {
    /** Get all notes that mention a concept. */
    fun getNotesForConcept(concept: String): List<String> {
        return nodes[concept]?.sampleNotes ?: emptyList()
    }

    /** Get concepts related to a given concept (via edges). */
    fun getRelatedConcepts(concept: String, maxResults: Int = 5): List<Pair<String, Float>> {
        return edges
            .filter { it.sourceConcept == concept || it.targetConcept == concept }
            .map { edge ->
                val other = if (edge.sourceConcept == concept) edge.targetConcept else edge.sourceConcept
                other to edge.strength
            }
            .distinctBy { it.first }
            .sortedByDescending { it.second }
            .take(maxResults)
    }

    /** Search concepts by name with tiered relevance scoring: exact > prefix > substring. */
    fun searchConcepts(query: String, maxResults: Int = 10): List<ConceptNode> {
        val queryLower = query.lowercase()
        val queryTokens = queryLower.split("\\s+".toRegex()).filter { it.length >= 2 }

        data class ScoredConcept(val node: ConceptNode, val relevanceScore: Float)

        val scored = nodes.values.mapNotNull { node ->
            val canonLower = node.canonicalForm.lowercase()
            val displayLower = node.displayForm.lowercase()

            // Tier 1: exact match on canonical or display form
            if (canonLower == queryLower || displayLower == queryLower) {
                return@mapNotNull ScoredConcept(node, 1.0f)
            }

            // Tier 2: all query tokens appear as whole words in canonical/display
            val allTokensMatch = queryTokens.all { qt ->
                canonLower.split("\\s+".toRegex()).any { it == qt } ||
                displayLower.split("\\s+".toRegex()).any { it == qt }
            }
            if (allTokensMatch) {
                return@mapNotNull ScoredConcept(node, 0.85f)
            }

            // Tier 3: query is a prefix of canonical or display form
            if (canonLower.startsWith(queryLower) || displayLower.startsWith(queryLower)) {
                return@mapNotNull ScoredConcept(node, 0.7f)
            }

            // Tier 4: any query token matches a whole word
            val anyTokenMatch = queryTokens.any { qt ->
                canonLower.split("\\s+".toRegex()).any { it == qt } ||
                displayLower.split("\\s+".toRegex()).any { it == qt }
            }
            if (anyTokenMatch) {
                return@mapNotNull ScoredConcept(node, 0.5f)
            }

            // Tier 5: substring containment
            if (canonLower.contains(queryLower) || displayLower.contains(queryLower)) {
                return@mapNotNull ScoredConcept(node, 0.3f)
            }

            null  // no match
        }

        return scored
            .sortedWith(compareByDescending<ScoredConcept> { it.relevanceScore }
                .thenByDescending { it.node.importance })
            .take(maxResults)
            .map { it.node }
    }
}
