package com.noteflowai.app.data.chat

/**
 * Phase 6: UnsupportedClaimDetector reworked to drive Trigger 5 only.
 *
 * This class no longer decides terminal states. It only:
 * - Detects whether any claim is unsupported after validation (Trigger 5 condition)
 * - Builds a specific retry prompt with the exact failure reasons
 *
 * Terminal state decisions (FULLY_VALIDATED, UNVERIFIED, ABSTAIN, HOLE_IN_EVIDENCE,
 * UNRESPONSIVE, RETRY) are now owned by GroundingDispositionResolver.
 */
object UnsupportedClaimDetector {

    /**
     * Checks if the validated response has any unsupported claims.
     *
     * This is the Trigger 5 condition: if any claim is not valid, a retry may be warranted.
     *
     * @param validated The validation results from ClaimValidator
     * @return true if at least one claim is unsupported (isValid == false)
     */
    fun hasUnsupportedClaims(validated: ValidatedResponse): Boolean {
        return validated.validatedClaims.any { !it.isValid }
    }

    /**
     * Builds a stricter retry prompt that explicitly asks the model to fix
     * the unsupported claims with specific failure reasons.
     *
     * @param validated The original validation results with per-claim reasons
     * @return A prompt addition for the retry attempt with specific failure details
     */
    fun buildRetryPrompt(validated: ValidatedResponse): String {
        val unsupportedClaims = validated.validatedClaims
            .filter { !it.isValid }
            .map { it.claim }

        val unsupportedSummary = unsupportedClaims.mapIndexed { i, claim ->
            val reasons = mutableListOf<String>()

            // Add citation-related failure reasons
            if (claim.citationIds.isNotEmpty()) {
                val vc = validated.validatedClaims.first { it.claim == claim }
                if (vc.invalidCitationIds.isNotEmpty()) {
                    for (cid in vc.invalidCitationIds) {
                        val reason = vc.invalidCitationReasons[cid]
                            ?: "Citation '$cid' invalid"
                        reasons.add(reason)
                    }
                }
                if (vc.validCitationIds.isNotEmpty() && !vc.hasValidQuoteRange) {
                    reasons.add("No cited chunk supports the claim (entailment failed)")
                }
                if (vc.validCitationIds.isEmpty() && vc.invalidCitationIds.isEmpty()) {
                    reasons.add("No valid citations")
                }
            }

            // Add memory channel failure reasons
            if (claim.memory_object_ids.isNotEmpty()) {
                val vc = validated.validatedClaims.first { it.claim == claim }
                if (vc.invalidMemoryIds.isNotEmpty()) {
                    reasons.add("Memory object IDs invalid: ${vc.invalidMemoryIds.joinToString(", ")}")
                }
            }

            val reasonText = if (reasons.isNotEmpty()) reasons.joinToString("; ") else "Unknown validation failure"
            "  ${i + 1}. \"${claim.text.take(100)}\" — $reasonText"
        }.joinToString("\n")

        return """
--- RETRY INSTRUCTION ---
Your previous response had claims that could not be validated against the provided sources:
$unsupportedSummary

Please retry your answer with these corrections:
1. Only make claims that are directly supported by the provided source context.
2. For each claim, cite a VALID source_segment_id from the provided context.
3. If you cannot support a claim with evidence, remove it from your answer.
4. If you cannot answer the question from the evidence, set "abstained": true.

Respond with corrected JSON matching the same schema.
""".trimIndent()
    }
}