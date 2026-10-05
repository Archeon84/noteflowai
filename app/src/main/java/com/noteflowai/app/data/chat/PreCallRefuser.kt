package com.noteflowai.app.data.chat

import android.content.Context
import com.noteflowai.app.data.search.ConflictDetector
import com.noteflowai.app.data.search.RetrievalConfig
import com.noteflowai.app.data.search.RetrievalResult
import com.noteflowai.app.R

/**
 * Phase 6: Pre-call refusal logic (Phase A of the two-phase pipeline).
 *
 * Runs BEFORE the LLM call when retrieval has already populated results for this turn.
 * Three triggers can short-circuit the LLM call:
 * - T1: Empty retrieval (no results)
 * - T2: All results below minimumScore floor
 * - T3: ConflictDetector surfaces conflicting sources
 */
class PreCallRefuser(
    private val context: Context,
    private val conflictDetector: ConflictDetector
) {

    sealed class PreCallDecision {
        data class REFUSE(val reason: RefusalReason, val message: String) : PreCallDecision()
        data object PROCEED : PreCallDecision()

        enum class RefusalReason {
            T1_EMPTY_RETRIEVAL,
            T2_BELOW_FLOOR,
            T3_CONFLICT
        }
    }

    /**
     * Decides whether to proceed with the LLM call or refuse based on pre-call triggers.
     * Uses default RetrievalConfig if not provided.
     */
    fun decide(
        retrievalResults: List<RetrievalResult>,
        config: RetrievalConfig = RetrievalConfig()
    ): PreCallDecision {
        // T1: Empty retrieval
        if (retrievalResults.isEmpty()) {
            return PreCallDecision.REFUSE(
                reason = PreCallDecision.RefusalReason.T1_EMPTY_RETRIEVAL,
                message = context.getString(R.string.grounding_t1_empty_retrieval)
            )
        }

        // T2: All results below minimumScore floor
        val hasAboveFloor = retrievalResults.any { it.score >= config.minimumScore }
        if (!hasAboveFloor) {
            return PreCallDecision.REFUSE(
                reason = PreCallDecision.RefusalReason.T2_BELOW_FLOOR,
                message = context.getString(R.string.grounding_t2_below_floor)
            )
        }

        // T3: Conflicting sources
        val conflicts = conflictDetector.findConflicts(maxResults = 3)
        if (conflicts.isNotEmpty()) {
            val count = conflicts.size
            return PreCallDecision.REFUSE(
                reason = PreCallDecision.RefusalReason.T3_CONFLICT,
                message = context.getString(R.string.grounding_t3_conflict, count)
            )
        }

        return PreCallDecision.PROCEED
    }
}