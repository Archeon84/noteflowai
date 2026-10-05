package com.noteflowai.app.data.search

import com.noteflowai.app.data.concept.ConceptGraphRepository

/**
 * Explains WHY a search result was retrieved.
 * Provides transparency into the RAG system's decision-making.
 */
class SearchExplainer(private val conceptGraph: ConceptGraphRepository) {

    data class SearchExplanation(
        val noteTitle: String,
        val overallScore: Float,
        val reasons: List<RetrievalReason>,
        val concepts: List<String>     // concepts shared with query
    )

    data class RetrievalReason(
        val type: String,      // "title_match", "tag_match", "content_match", "concept_match", "graph_connection", "embedding_match"
        val detail: String,    // human-readable explanation
        val weight: Float      // contribution to final score
    )

    /**
     * Generate an explanation for why a result was retrieved.
     */
    fun explain(
        result: NoteSearchIndex.SearchResult,
        query: String,
        isGraphExpanded: Boolean = false,
        isConceptExpanded: Boolean = false
    ): SearchExplanation {
        val reasons = mutableListOf<RetrievalReason>()

        // Check title match. Tokens are non-blank split (a double space used
        // to yield "", and "".let { title.contains("") } is always true —
        // every result got a spurious title_match). The dead queryTokens var
        // derived from the title is gone.
        val titleLower = result.title.lowercase(java.util.Locale.ROOT)
        val queryTerms = query.lowercase(java.util.Locale.ROOT)
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
        if (queryTerms.any { it.isNotBlank() && titleLower.contains(it) }) {
            reasons.add(RetrievalReason(
                type = "title_match",
                detail = "Title contains query terms",
                weight = 0f  // computed after all reasons collected
            ))
        }

        // Check field matches from SearchResult.matchedFields
        if (result.matchedFields.contains("tags")) {
            reasons.add(RetrievalReason(
                type = "tag_match",
                detail = "Tags match the query",
                weight = 0f
            ))
        }
        if (result.matchedFields.contains("category")) {
            reasons.add(RetrievalReason(
                type = "category_match",
                detail = "Same category as query context",
                weight = 0f
            ))
        }
        if (result.matchedFields.contains("content")) {
            reasons.add(RetrievalReason(
                type = "content_match",
                detail = "Content contains relevant terms",
                weight = 0f
            ))
        }

        // Graph expansion
        if (isGraphExpanded) {
            reasons.add(RetrievalReason(
                type = "graph_connection",
                detail = "Connected via knowledge graph",
                weight = 0f
            ))
        }

        // Concept expansion
        if (isConceptExpanded) {
            reasons.add(RetrievalReason(
                type = "concept_match",
                detail = "Shares extracted concepts with query",
                weight = 0f
            ))
        }

        // Normalize weights so they sum to 1.0 (each reason's contribution to the explanation)
        if (reasons.isNotEmpty()) {
            val perReason = 1.0f / reasons.size
            val normalizedReasons = reasons.map { it.copy(weight = perReason) }
            reasons.clear()
            reasons.addAll(normalizedReasons)
        }

        // Find shared concepts
        val sharedConcepts = conceptGraph.getConcepts(query)
            .filter { concept ->
                concept.sampleNotes.any { it == result.fileName }
            }
            .map { it.displayForm }

        return SearchExplanation(
            noteTitle = result.title,
            overallScore = result.score,
            reasons = reasons,
            concepts = sharedConcepts
        )
    }
}
