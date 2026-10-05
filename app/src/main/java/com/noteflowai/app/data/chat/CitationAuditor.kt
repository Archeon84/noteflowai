package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.dao.AnswerCitationDao
import com.noteflowai.app.data.memory.model.AnswerCitation
import com.noteflowai.app.data.search.OnDeviceEmbedder
import java.util.UUID

/**
 * Validates and persists AI answer citations into the Room database.
 * Supports both standard RAG responses (with [1], [2] tags) and
 * GroundedChatPipeline structured responses.
 */
class CitationAuditor(
    private val citationDao: AnswerCitationDao,
    private val embedder: OnDeviceEmbedder? = null
) {
    companion object {
        private val CITATION_MARKER_REGEX = Regex("\\[(\\d+)\\]")
        private val STOP_WORDS = setOf(
            "the", "and", "is", "in", "it", "of", "to", "a", "for", "with", "on", "as",
            "at", "by", "from", "an", "be", "this", "that", "are", "or", "was", "will",
            "your", "my", "have", "has", "not", "but", "about", "can", "also"
        )
    }

    /**
     * Audit citations in a standard RAG response text against retrieved [ragSources].
     * Parses markers like [1], [2], isolates the claim sentence for each marker,
     * checks entailment/overlap against the cited source excerpt, and persists the AnswerCitation rows.
     *
     * @return List of persisted [AnswerCitation] records.
     */
    suspend fun auditAndPersistRagResponse(
        answerId: String,
        responseText: String,
        ragSources: List<RagSource>
    ): List<AnswerCitation> {
        if (responseText.isBlank() || ragSources.isEmpty()) return emptyList()

        val matches = CITATION_MARKER_REGEX.findAll(responseText).toList()
        if (matches.isEmpty()) return emptyList()

        val citations = mutableListOf<AnswerCitation>()

        // Deduplicate citation markers by citation number in natural numerical order
        val distinctCitationNumbers = matches.mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().sorted()

        for (citationNumber in distinctCitationNumbers) {
            // Find all claim sentences preceding any occurrence of this citation marker
            val claimsForNumber = matches
                .filter { it.groupValues[1].toIntOrNull() == citationNumber }
                .map { match ->
                    val markerStart = match.range.first
                    val textBefore = responseText.substring(0, markerStart)
                    val lastSentenceBreak = textBefore.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                    if (lastSentenceBreak >= 0 && lastSentenceBreak < textBefore.length - 1) {
                        textBefore.substring(lastSentenceBreak + 1).trim()
                    } else {
                        textBefore.trim().takeLast(250)
                    }
                }

            // Check source bounds
            val sourceIndex = citationNumber - 1
            if (sourceIndex !in ragSources.indices) {
                // Fabricated / out-of-bounds citation index
                citations.add(
                    AnswerCitation(
                        id = UUID.randomUUID().toString(),
                        answerId = answerId,
                        sourceSegmentId = "unknown_source_$citationNumber",
                        claimIndex = citationNumber,
                        locationType = "NOTE",
                        supportStatus = "INVALID",
                        quoteText = null,
                        createdAt = System.currentTimeMillis()
                    )
                )
                continue
            }

            val source = ragSources[sourceIndex]
            val sourceText = (source.noteTitle + " " + source.excerpt).trim()
            val isSupported = claimsForNumber.any { isClaimSupported(it, sourceText) }

            citations.add(
                AnswerCitation(
                    id = UUID.randomUUID().toString(),
                    answerId = answerId,
                    sourceSegmentId = source.noteFileName,
                    claimIndex = citationNumber,
                    locationType = source.noteTitle.ifBlank { "NOTE" },
                    supportStatus = if (isSupported) "VALIDATED" else "INVALID",
                    quoteText = source.excerpt.take(300),
                    createdAt = System.currentTimeMillis()
                )
            )
        }

        if (citations.isNotEmpty()) {
            try {
                citationDao.insertAll(citations)
            } catch (_: Exception) {
            }
        }
        return citations
    }

    /**
     * Persists citations produced by GroundedChatPipeline's [ValidatedResponse].
     */
    suspend fun auditAndPersistGroundedResponse(
        answerId: String,
        validatedResponse: ValidatedResponse
    ): List<AnswerCitation> {
        val citations = mutableListOf<AnswerCitation>()
        val response = validatedResponse.response
        val validatedClaims = validatedResponse.validatedClaims

        val validCitationIds = validatedClaims.flatMap { it.validCitationIds }.toSet()
        val invalidCitationIds = validatedClaims.flatMap { it.invalidCitationIds }.toSet()

        response.citations.forEachIndexed { idx, cite ->
            val status = when {
                cite.id in validCitationIds -> "VALIDATED"
                cite.id in invalidCitationIds -> "INVALID"
                validatedResponse.validClaims > 0 -> "VALIDATED"
                else -> "INVALID"
            }

            citations.add(
                AnswerCitation(
                    id = UUID.randomUUID().toString(),
                    answerId = answerId,
                    sourceSegmentId = cite.chunkId,
                    claimIndex = idx + 1,
                    locationType = cite.sourceType.name,
                    supportStatus = status,
                    quoteText = cite.sourceId,
                    createdAt = System.currentTimeMillis()
                )
            )
        }

        if (citations.isNotEmpty()) {
            try {
                citationDao.insertAll(citations)
            } catch (_: Exception) {
            }
        }
        return citations
    }

    /**
     * Evaluates whether a claim is substantiated by the source text using
     * exact quote presence, word overlap, or semantic embedding cosine.
     */
    suspend fun isClaimSupported(claimText: String, sourceText: String): Boolean {
        if (claimText.isBlank() || sourceText.isBlank()) return false

        // 1. Exact quote or phrase presence
        if (GroundingSupport.checkQuotePresence(sourceText, claimText)) {
            return true
        }

        // 2. Token overlap fallback using GroundingSupport
        if (GroundingSupport.checkTokenOverlapFallback(sourceText, claimText, minRatio = 0.30f)) {
            return true
        }

        // 3. Content word overlap (handles short sentences and minor phrasing differences)
        val claimWords = claimText.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }

        if (claimWords.isNotEmpty()) {
            val sourceLower = sourceText.lowercase()
            var matches = 0
            for (word in claimWords) {
                if (sourceLower.contains(word)) matches++
            }
            val ratio = matches.toFloat() / claimWords.size.toFloat()
            if (ratio >= 0.30f) {
                return true
            }
        }

        // 4. Semantic embedding cosine check if embedder is active
        if (embedder != null && embedder.isReady()) {
            try {
                val claimEmbed = embedder.embed(claimText)
                val sourceEmbed = embedder.embed(sourceText.take(500))
                if (claimEmbed != null && sourceEmbed != null) {
                    val cosine = cosineSimilarity(claimEmbed, sourceEmbed)
                    if (cosine >= GroundingSupport.BORDERLINE_COSINE_FLOOR) {
                        return true
                    }
                }
            } catch (_: Exception) {
            }
        }

        return false
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt((normA * normB).toDouble()).toFloat()
        return if (denom > 0f) dot / denom else 0f
    }
}
