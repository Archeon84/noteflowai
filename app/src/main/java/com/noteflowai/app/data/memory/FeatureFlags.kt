package com.noteflowai.app.data.memory

/**
 * Feature flags for the Personal Memory Layer.
 *
 * All new features must be behind feature flags until validated.
 * Flags are read from the SettingsManager DataStore at runtime.
 *
 * Phase 1: ENABLE_SOURCE_SEGMENTS
 * Phase 2: ENABLE_MEMORY_DATABASE
 * Phase 3: ENABLE_MEMORY_EXTRACTION
 * Phase 4: ENABLE_QUERY_PLANNER
 * Phase 5: ENABLE_GROUNDED_MEMORY_CHAT
 * Phase 6: ENABLE_DECISION_TIMELINE, ENABLE_COMMITMENT_DASHBOARD
 * Phase 7: ENABLE_CHANGE_ANALYSIS, ENABLE_CONFLICT_DETECTION, ENABLE_WEEKLY_REVIEW
 */
object FeatureFlags {

    // Phase 1
    const val ENABLE_SOURCE_SEGMENTS = "enable_source_segments"

    // Phase 2
    const val ENABLE_MEMORY_DATABASE = "enable_memory_database"

    // Phase 3
    const val ENABLE_MEMORY_EXTRACTION = "enable_memory_extraction"

    // Phase 4
    const val ENABLE_QUERY_PLANNER = "enable_query_planner"

    // Phase 5
    const val ENABLE_GROUNDED_MEMORY_CHAT = "enable_grounded_memory_chat"

    // Phase 6
    const val ENABLE_DECISION_TIMELINE = "enable_decision_timeline"
    const val ENABLE_COMMITMENT_DASHBOARD = "enable_commitment_dashboard"

    // Phase 7
    const val ENABLE_CHANGE_ANALYSIS = "enable_change_analysis"
    const val ENABLE_CONFLICT_DETECTION = "enable_conflict_detection"
    const val ENABLE_WEEKLY_REVIEW = "enable_weekly_review"

    // Phase 8d
    const val ENABLE_EVALUATION_DASHBOARD = "enable_evaluation_dashboard"

    /**
     * Default values for all feature flags.
     * All flags default to false until validated.
     */
    val DEFAULTS = mapOf(
        ENABLE_SOURCE_SEGMENTS to false,
        ENABLE_MEMORY_DATABASE to false,
        ENABLE_MEMORY_EXTRACTION to false,
        ENABLE_QUERY_PLANNER to false,
        ENABLE_GROUNDED_MEMORY_CHAT to false,
        ENABLE_DECISION_TIMELINE to false,
        ENABLE_COMMITMENT_DASHBOARD to false,
        ENABLE_CHANGE_ANALYSIS to false,
        ENABLE_CONFLICT_DETECTION to false,
        ENABLE_WEEKLY_REVIEW to false,
        ENABLE_EVALUATION_DASHBOARD to false
    )
}
