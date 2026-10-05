package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.model.SourceType

/**
 * Phase 6: Guide-literal structured response models for grounded, evidence-first chat.
 *
 * Gson bypasses constructors (see AiChatModels.kt), so these models carry only
 * defaults, never init-time invariant checks. Invariants live in the validator/
 * resolver layer.
 */

/** Full structured response from the model. */
data class GroundedChatResponse(
    val answer: String,
    val citations: List<Citation> = emptyList(),
    val claims: List<Claim> = emptyList(),
    val suggested_actions: List<SuggestedAction> = emptyList(),
    val needs_clarification: Boolean = false,
    val clarification_question: String? = null,
    val abstained: Boolean = false,
    val abstention_reason: String? = null
)

/**
 * A first-class citation object (guide schema). quoteStart/quoteEnd are advisory
 * UTF-16 code-unit offsets into the resolved chunk text; the app recomputes the
 * canonical span via GroundingSupport.computeQuoteSpan.
 */
data class Citation(
    val id: String,
    val sourceType: SourceType,
    val sourceId: String,
    val chunkId: String,
    val quoteStart: Int? = null,
    val quoteEnd: Int? = null,
    val relevanceScore: Float? = null
)

/** A single factual claim; citations by id, memory references in a separate channel. */
data class Claim(
    val text: String,
    val citationIds: List<String> = emptyList(),
    val memory_object_ids: List<String> = emptyList(),
    val uncertainty: UncertaintyLevel = UncertaintyLevel.LOW,
    val confidence: Float? = null
)

enum class UncertaintyLevel { LOW, MEDIUM, HIGH }

/** An action the model suggests the user might want to take. */
data class SuggestedAction(
    val type: ActionType,
    val label: String,
    val payload: Map<String, Any> = emptyMap()
)

enum class ActionType { CREATE_NOTE, CREATE_TASK, CONFIRM_DECISION, OPEN_SOURCE, NONE }

/** A claim with validation status attached. */
data class ValidatedClaim(
    val claim: Claim,
    val isValid: Boolean,
    val validCitationIds: List<String>,
    val invalidCitationIds: List<String>,
    val validMemoryIds: List<String> = emptyList(),
    val invalidMemoryIds: List<String> = emptyList(),
    val hasValidQuoteRange: Boolean,
    /** citationId -> specific failure reason (for the retry prompt). */
    val invalidCitationReasons: Map<String, String> = emptyMap(),
    val reason: String? = null
)

/** The full response with validation results attached. */
data class ValidatedResponse(
    val response: GroundedChatResponse,
    val validatedClaims: List<ValidatedClaim>,
    val totalClaims: Int,
    val validClaims: Int,
    val unsupportedClaims: Int
)