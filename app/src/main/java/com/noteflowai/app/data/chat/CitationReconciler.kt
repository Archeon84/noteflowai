package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.search.RetrievalResult

/**
 * A citation after resolution against the retrieval context and the segment DB.
 * A failed resolution is KEPT (resolved=null) and marked non-authoritative -
 * it is never silently dropped (spec: non-destructive reconciliation).
 */
data class ReconciledCitation(
    val citation: Citation,
    val resolved: RetrievalResult?,
    val segment: SourceSegment?,
    val computedQuoteRange: GroundingSupport.QuoteRange?,
    val authoritative: Boolean,
    val conflictNote: String? = null
)

class CitationReconciler(
    private val sourceSegmentRepository: SourceSegmentRepository
) {
    suspend fun reconcile(
        response: GroundedChatResponse,
        retrievalResults: List<RetrievalResult>
    ): List<ReconciledCitation> {
        val byChunkId = retrievalResults.associateBy { it.sourceSegmentId }
        return response.citations.map { citation ->
            val resolved = byChunkId[citation.chunkId]
            val segment = resolved?.let { sourceSegmentRepository.getById(it.sourceSegmentId) }
            val blockMappable = GroundingSupport.noteBlockIndexFromSegmentId(citation.chunkId) != null
            val isNoteCitation = citation.chunkId.startsWith("note_") || resolved?.sourceType == com.noteflowai.app.data.memory.model.SourceType.NOTE
            val authoritative = when {
                resolved == null -> false                                    // V2: chunk not retrieved
                citation.sourceId != resolved.sourceId -> false              // V3-consistency
                segment == null && !blockMappable && !isNoteCitation -> false // V4: no persistent segment
                else -> true
            }
            val computedQuoteRange = resolved?.let {
                GroundingSupport.computeQuoteSpan(it.text, citation.quoteStart, citation.quoteEnd)
            }
            ReconciledCitation(
                citation = citation,
                resolved = resolved,
                segment = segment,
                computedQuoteRange = computedQuoteRange,
                authoritative = authoritative
            )
        }
    }
}