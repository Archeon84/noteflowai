package com.noteflowai.app.data.chat

import com.noteflowai.app.data.search.OnDeviceEmbedder
import kotlinx.coroutines.runBlocking

/**
 * Phase 6: Single claim validator combining V1 + entailment (V5) + memory channel.
 * Replaces the old CitationValidator.
 *
 * A claim is valid when:
 * - V1: All its citationIds resolve to authoritative ReconciledCitations
 * - V5: At least one authoritative citation passes entailment (embed cosine OR quote presence)
 * - OR memory-object channel has valid IDs (separate validation layer)
 *
 * Embedder-unavailable = conservative reject (fail-closed).
 */
class ClaimValidator(
    private val embedder: OnDeviceEmbedder
) {

    suspend fun validate(
        response: GroundedChatResponse,
        reconciledCitations: List<ReconciledCitation>
    ): ValidatedResponse {
        val citationMap = reconciledCitations.associateBy { it.citation.id }
        val validatedClaims = response.claims.map { claim ->
            runBlocking { validateClaim(claim, citationMap) }
        }
        val validCount = validatedClaims.count { it.isValid }
        val unsupportedCount = validatedClaims.count { !it.isValid }

        return ValidatedResponse(
            response = response,
            validatedClaims = validatedClaims,
            totalClaims = response.claims.size,
            validClaims = validCount,
            unsupportedClaims = unsupportedCount
        )
    }

    private suspend fun validateClaim(
        claim: Claim,
        citationMap: Map<String, ReconciledCitation>
    ): ValidatedClaim {
        val citationIds = claim.citationIds
        val memoryIds = claim.memory_object_ids

        // V1: validate citation IDs - ANY unknown or non-authoritative citation ID invalidates the claim
        val validCitationIds = mutableListOf<String>()
        val invalidCitationIds = mutableListOf<String>()
        val invalidCitationReasons = mutableMapOf<String, String>()

        for (cid in citationIds) {
            val rec = citationMap[cid]
            if (rec == null) {
                invalidCitationIds.add(cid)
                invalidCitationReasons[cid] = "Citation '$cid' not in response"
            } else if (!rec.authoritative) {
                invalidCitationIds.add(cid)
                invalidCitationReasons[cid] = "Citation '$cid' not authoritative (chunk not retrieved or source mismatch)"
            } else {
                validCitationIds.add(cid)
            }
        }

        // Memory-object channel: validated as a distinct channel, OR with citationIds for claim support.
        // For now, accept all memory IDs as valid (placeholder for future MemoryRepository validation).
        val validMemoryIds = memoryIds.toMutableList()
        val invalidMemoryIds = mutableListOf<String>()

        // V1 check: if any citationIds are invalid, the claim fails (unless memory channel saves it)
        val v1Passed = invalidCitationIds.isEmpty()

        // Entailment check (V5): passes if any valid citation passes entailment
        var hasValidQuoteRange = false
        var citationEntailmentPassed = false

        if (validCitationIds.isNotEmpty()) {
            for (cid in validCitationIds) {
                val rec = citationMap[cid]!!
                if ((rec.segment != null || rec.resolved != null) && checkEntailment(claim.text, rec)) {
                    citationEntailmentPassed = true
                    if (rec.computedQuoteRange != null) {
                        hasValidQuoteRange = true
                    }
                    break
                }
            }
        }

        // Determine overall validity
        // Citation channel: V1 must pass AND entailment must pass for at least one valid citation
        val citationChannelValid = citationIds.isEmpty() || (v1Passed && validCitationIds.isNotEmpty() && citationEntailmentPassed)
        // Memory channel: OR with citation channel (all memory IDs considered valid for now)
        val memoryChannelValid = memoryIds.isNotEmpty() && invalidMemoryIds.isEmpty()
        val isValid = citationChannelValid || memoryChannelValid

        // Build reason for invalid claims
        var reason: String? = null
        if (!isValid) {
            val reasons = mutableListOf<String>()
            if (citationIds.isNotEmpty() && !citationChannelValid) {
                if (!v1Passed) {
                    reasons.add("Invalid citation IDs: ${invalidCitationIds.joinToString(", ")}")
                } else if (validCitationIds.isEmpty()) {
                    reasons.add("No valid citations")
                } else {
                    reasons.add("No cited chunk supports the claim (entailment failed)")
                }
            }
            if (memoryIds.isNotEmpty() && !memoryChannelValid) {
                reasons.add("Memory object IDs invalid")
            }
            reason = reasons.joinToString("; ")
        }

        return ValidatedClaim(
            claim = claim,
            isValid = isValid,
            validCitationIds = validCitationIds,
            invalidCitationIds = invalidCitationIds,
            validMemoryIds = validMemoryIds,
            invalidMemoryIds = invalidMemoryIds,
            hasValidQuoteRange = hasValidQuoteRange,
            invalidCitationReasons = invalidCitationReasons,
            reason = reason
        )
    }

    private suspend fun checkEntailment(claimText: String, rec: ReconciledCitation): Boolean {
        val candidateTexts = listOfNotNull(rec.resolved?.text, rec.segment?.text).distinct()
        if (candidateTexts.isEmpty()) return false

        for (chunkText in candidateTexts) {
            if (chunkText.isBlank()) continue

            // Quote presence channel (fast, no embedder needed)
            if (GroundingSupport.checkQuotePresence(chunkText, claimText)) {
                return true
            }

            // Embedding cosine channel (semantic entailment)
            if (embedder.isReady()) {
                val claimEmbedding = embedder.embed(claimText)
                val chunkEmbedding = embedder.embed(chunkText)
                if (claimEmbedding != null && chunkEmbedding != null) {
                    val cosine = cosineSimilarity(claimEmbedding, chunkEmbedding)
                    if (cosine >= GroundingSupport.EMBED_COSINE_FLOOR) {
                        return true
                    }
                    if (cosine >= GroundingSupport.BORDERLINE_COSINE_FLOOR &&
                        GroundingSupport.checkTokenOverlapFallback(chunkText, claimText)
                    ) {
                        return true
                    }
                }
            }
            // Embedder unavailable or cosine below threshold: fail-closed unless quote presence passed above
        }

        // Fail-closed: all candidate texts failed quote presence and cosine entailment
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