package com.noteflowai.app.data

/**
 * Typed, app-level product feature flags (Phase 0 of the Agentic Guide).
 *
 * These are the canonical product-flag surface. They are deliberately distinct
 * from the memory-layer feature flags in `data/memory/FeatureFlags.kt` (which are
 * string-key constants scoped to the Personal Memory Layer and consumed via
 * SettingsManager as per-key `Flow<Boolean>`); the two are disjoint concerns and
 * are not merged.
 *
 * [voiceCommandService] from the upstream guide is intentionally omitted: the
 * voice-command feature was removed from the app.
 *
 * Where a flag has a real persisted equivalent in [com.noteflowai.app.data.settings.SettingsManager],
 * the [from] factory reads it; otherwise the guide's defaults stand.
 */
data class FeatureFlags(
    val semanticSearch: Boolean = true,
    val entityExtraction: Boolean = true,
    val timelineExtraction: Boolean = true,
    val webGrounding: Boolean = false,
    val proactiveRecall: Boolean = false,
    val localLlm: Boolean = true,
    val cloudLlm: Boolean = true
) {
    companion object {
        /**
         * Map the subset of flags that have a genuine persisted source onto a
         * [FeatureFlags] snapshot. Fields without a distinct persisted source
         * keep their declared defaults (entity/timeline extraction, local/cloud
         * LLM). `cloudLlm` intentionally stays at its default `true` rather than
         * being inferred from an unrelated connectivity toggle.
         */
        fun from(
            semanticSearch: Boolean = true,
            webGrounding: Boolean = false,
            proactiveRecall: Boolean = false
        ): FeatureFlags = FeatureFlags(
            semanticSearch = semanticSearch,
            webGrounding = webGrounding,
            proactiveRecall = proactiveRecall
        )
    }
}
