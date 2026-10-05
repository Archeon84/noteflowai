package com.noteflowai.app.data.chat

/**
 * Phase 6: Single owner of terminal GroundingDisposition and retry-vs-terminal decision.
 *
 * This resolver is the ONLY place where a terminal disposition is declared.
 * It evaluates:
 * - Trigger 4: HIGH uncertainty + no valid quote range -> HOLE_IN_EVIDENCE
 * - Trigger 5: Unsupported claims with retry logic
 * - Mutual exclusivity of terminal states
 */
class GroundingDispositionResolver(
    private val maxAttempts: Int = 2
) {

    /**
     * Resolves the validated response to a terminal GroundingDisposition or RETRY.
     *
     * @param validated The validation results from ClaimValidator
     * @param attempt Current attempt number (1-indexed). maxAttempts=2 means attempt 1 can retry, attempt 2 is final.
     * @return The disposition for this response
     */
    fun resolve(validated: ValidatedResponse, attempt: Int): GroundingDisposition {
        // 1. Model already abstained
        if (validated.response.abstained) {
            return GroundingDisposition.ABSTAIN(validated.response.abstention_reason ?: "Insufficient evidence")
        }

        // 2. No claims at all
        if (validated.totalClaims == 0) {
            return GroundingDisposition.FULLY_VALIDATED
        }

        // 3. Check Trigger 4: HIGH uncertainty AND no claim has a valid quote range
        val hasHighUncertaintyClaim = validated.validatedClaims.any {
            it.claim.uncertainty == UncertaintyLevel.HIGH && !it.hasValidQuoteRange
        }
        if (hasHighUncertaintyClaim) {
            return GroundingDisposition.HOLE_IN_EVIDENCE
        }

        // 4. Check Trigger 5: unsupported claims
        val hasUnsupported = UnsupportedClaimDetector.hasUnsupportedClaims(validated)
        if (hasUnsupported) {
            if (attempt < maxAttempts) {
                // Retry with specific prompt
                val retryPrompt = UnsupportedClaimDetector.buildRetryPrompt(validated)
                return GroundingDisposition.RETRY(retryPrompt)
            } else {
                // Max attempts exhausted -> UNVERIFIED
                val unsupportedClaims = validated.validatedClaims.filter { !it.isValid }
                return GroundingDisposition.UNVERIFIED(unsupportedClaims)
            }
        }

        // 5. All claims supported
        return GroundingDisposition.FULLY_VALIDATED
    }
}

/**
 * The sealed disposition type representing the terminal state of a grounded response.
 * Mutually exclusive by construction.
 */
sealed class GroundingDisposition {
    /** All claims validated -- show the response as-is. */
    data object FULLY_VALIDATED : GroundingDisposition()

    /** Some claims unsupported but max attempts exhausted -- show with "unverified" badge. */
    data class UNVERIFIED(val unsupportedClaims: List<ValidatedClaim>) : GroundingDisposition()

    /** Too many unsupported claims or model abstained -- show abstention message instead. */
    data class ABSTAIN(val reason: String) : GroundingDisposition()

    /** Retry the model with a stricter prompt. */
    data class RETRY(val retryPrompt: String) : GroundingDisposition()

    /** Trigger 4: HIGH uncertainty AND no valid quote range. */
    data object HOLE_IN_EVIDENCE : GroundingDisposition()

    /** Malformed model response that couldn't be parsed. */
    data object UNRESPONSIVE : GroundingDisposition()
}