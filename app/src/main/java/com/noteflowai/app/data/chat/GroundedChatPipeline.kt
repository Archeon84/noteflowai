package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.search.OnDeviceEmbedder
import com.noteflowai.app.data.search.RetrievalConfig
import com.noteflowai.app.data.search.RetrievalResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Phase 6: Grounded chat pipeline (Phase B post-call processing).
 *
 * Runs the full validation pipeline after a model response:
 * parse → reconcile → validate → unsupported detector → disposition resolver
 * with retry loop for Trigger 5.
 */
class GroundedChatPipeline(
    private val scope: CoroutineScope,
    private val sourceSegmentRepository: SourceSegmentRepository,
    private val embedder: OnDeviceEmbedder,
    private val preCallRefuser: PreCallRefuser,
    private val retrievalConfig: RetrievalConfig = RetrievalConfig()
) {

    /** Result of the grounded chat pipeline. */
    sealed class Result {
        /** LLM call should be skipped; refusal message provided. */
        data class Refuse(val message: String, val reason: PreCallRefuser.PreCallDecision.RefusalReason) : Result()
        /** Pipeline completed with a terminal disposition. */
        data class Complete(
            val disposition: GroundingDisposition,
            val validatedResponse: ValidatedResponse? = null
        ) : Result()
        /** Pipeline should retry with a stricter prompt. */
        data class Retry(val retryPrompt: String, val validatedResponse: ValidatedResponse) : Result()
    }

    /**
     * Run the full grounded pipeline for a single turn.
     *
     * @param retrievalResults The retrieval results for this turn (used for Phase A and Phase B)
     * @param responseJson The raw JSON response from the LLM
     * @param attempt Current attempt number (1-indexed). Max 2 attempts.
     * @return The pipeline result (Refuse, Complete, or Retry)
     */
    suspend fun run(
        retrievalResults: List<RetrievalResult>,
        responseJson: String,
        attempt: Int = 1
    ): Result {
        // Phase A: Pre-call checks (T1/T2/T3)
        val preCallDecision = preCallRefuser.decide(retrievalResults, retrievalConfig)
        if (preCallDecision is PreCallRefuser.PreCallDecision.REFUSE) {
            return Result.Refuse(preCallDecision.message, preCallDecision.reason)
        }

        // Phase B: Parse the model response
        val parseResult = GroundedResponseParser.parse(responseJson)
        return when (parseResult) {
            is ParseResult.Failure -> {
                // UNRESPONSIVE: malformed JSON
                Result.Complete(GroundingDisposition.UNRESPONSIVE)
            }
            is ParseResult.Success -> {
                val response = parseResult.response
                // Reconcile citations against retrieval context
                val reconciler = CitationReconciler(sourceSegmentRepository)
                val reconciledCitations = reconciler.reconcile(response, retrievalResults)

                // Validate claims
                val claimValidator = ClaimValidator(embedder)
                val validatedResponse = claimValidator.validate(response, reconciledCitations)

                // Resolve disposition
                val resolver = GroundingDispositionResolver(maxAttempts = 2)
                val disposition = resolver.resolve(validatedResponse, attempt)

                return when (disposition) {
                    is GroundingDisposition.RETRY -> {
                        Result.Retry(disposition.retryPrompt, validatedResponse)
                    }
                    else -> {
                        Result.Complete(disposition, validatedResponse)
                    }
                }
            }
        }
    }

    /**
     * Run the pipeline with automatic retry loop.
     *
     * @param retrievalResults The retrieval results for this turn
     * @param callModel Function that calls the LLM and returns the raw JSON response.
     *                  Called with optional retry prompt on retry attempts.
     * @return The final pipeline result (Complete or Refuse)
     */
    suspend fun runWithRetry(
        retrievalResults: List<RetrievalResult>,
        callModel: suspend (String?) -> String
    ): Result {
        var attempt = 0
        var retryPrompt: String? = null

        do {
            attempt++
            val responseJson = callModel(retryPrompt)
            retryPrompt = null

            val result = run(retrievalResults, responseJson, attempt)
            when (result) {
                is Result.Refuse -> return result
                is Result.Complete -> {
                    // Phase 2: malformed JSON is often a truncated stream, not a
                    // defiant model — spend the second attempt on a repair retry
                    // instead of failing closed immediately.
                    if (result.disposition is GroundingDisposition.UNRESPONSIVE && attempt < 2) {
                        retryPrompt = "Your previous reply was not valid JSON (it may have been cut off). " +
                            "Reply with ONLY the JSON object in the required schema, no other text."
                    } else {
                        return result
                    }
                }
                is Result.Retry -> {
                    if (attempt < 2) {
                        retryPrompt = result.retryPrompt
                    } else {
                        // Max attempts exhausted, return UNVERIFIED
                        val unsupportedClaims = result.validatedResponse.validatedClaims.filter { !it.isValid }
                        return Result.Complete(GroundingDisposition.UNVERIFIED(unsupportedClaims), result.validatedResponse)
                    }
                }
            }
        } while (attempt < 2)

        // Should not reach here
        return Result.Complete(GroundingDisposition.UNRESPONSIVE)
    }
}