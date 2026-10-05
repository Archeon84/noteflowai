package com.noteflowai.app.data.memory.model

/**
 * User-facing processing status vocabulary (Phase 3 of the Agentic Guide).
 *
 * This is the presentation-layer translation of the durable [ProcessingStatus] row into the
 * language a person sees in the UI ("Transcribing", "Building semantic index", "Needs attention").
 * The label text for each value is resolved in the UI layer from a string resource keyed by
 * [UserFacingStatus], so this model stays pure (no Android resources) and unit-testable.
 */
enum class UserFacingStatus {
    SAVED_LOCALLY,
    PROCESSING,
    TRANSCRIBING,
    EXTRACTING_ENTITIES,
    EXTRACTING_TIMELINE,
    BUILDING_SEMANTIC_INDEX,
    PREPARING_SEARCH,
    READY,
    NEEDS_ATTENTION,
    FAILED
}

/**
 * The status a note card / detail screen should render for one source.
 */
data class StatusUiModel(
    val status: UserFacingStatus,
    /** User-friendly explanation for failure / attention states (derived from the pipeline error). */
    val message: String? = null,
    /** Technical error detail, hidden behind an expandable control. */
    val detail: String? = null,
    /** Whether a Retry / Resume action makes sense for this state. */
    val canRetry: Boolean = false,
    /** Whether Cancel is safe (in-progress work the user may abort). */
    val canCancel: Boolean = false,
    /** Whether the current step runs the LLM over the network (cloud) vs on-device. */
    val isCloud: Boolean = false,
    /** A stable state with no active progress (Ready / Failed / Needs attention). */
    val isTerminal: Boolean = false,
    /**
     * The pipeline stage to resume from on retry (the persisted current stage). Only meaningful
     * when [canRetry]; drives [com.noteflowai.app.viewmodel.MainViewModel.retrySource].
     */
    val stage: ProcessingStage? = null
)

/**
 * Map a durable [ProcessingStatus] row to the user-facing [StatusUiModel].
 *
 * Pure function: same input row always yields the same model, which makes the whole mapping
 * unit-testable without Android or Room.
 */
fun ProcessingStatus.toUiModel(): StatusUiModel = when (status) {
    ProcessingState.COMPLETED -> StatusUiModel(
        status = UserFacingStatus.READY,
        isTerminal = true
    )

    ProcessingState.FAILED_PERMANENT -> StatusUiModel(
        status = UserFacingStatus.FAILED,
        message = error ?: "This item could not be processed",
        detail = "Permanent failure (attempts exhausted)",
        canRetry = true,
        isTerminal = true,
        stage = currentStage
    )

    ProcessingState.FAILED_RETRYABLE -> StatusUiModel(
        status = UserFacingStatus.NEEDS_ATTENTION,
        message = error ?: "Processing hit a problem and can be retried",
        detail = error,
        canRetry = true,
        isTerminal = true,
        stage = currentStage
    )

    ProcessingState.CANCELLED -> StatusUiModel(
        status = UserFacingStatus.NEEDS_ATTENTION,
        message = "Processing was paused",
        detail = "Paused by the user; retry to resume",
        canRetry = true,
        isTerminal = true,
        stage = currentStage
    )

    ProcessingState.PENDING -> StatusUiModel(
        status = UserFacingStatus.SAVED_LOCALLY,
        canCancel = true,
        stage = currentStage
    )

    // SKIPPED — some stages were disabled by flag when the pipeline ran. The
    // source is usable with what was processed; retry resumes from the skipped
    // stage if the flag has since been enabled.
    ProcessingState.SKIPPED -> StatusUiModel(
        status = UserFacingStatus.READY,
        isTerminal = true,
        canRetry = true,
        stage = currentStage
    )

    // RUNNING — surface the current granular stage; fall back to a generic "Processing".
    ProcessingState.RUNNING -> {
        val running = when (currentStage) {
            ProcessingStage.TRANSCRIBING -> UserFacingStatus.TRANSCRIBING
            ProcessingStage.EXTRACTING_ENTITIES -> UserFacingStatus.EXTRACTING_ENTITIES
            ProcessingStage.EXTRACTING_TIMELINE -> UserFacingStatus.EXTRACTING_TIMELINE
            ProcessingStage.BUILDING_SEMANTIC_INDEX -> UserFacingStatus.BUILDING_SEMANTIC_INDEX
            ProcessingStage.PREPARING_SEARCH -> UserFacingStatus.PREPARING_SEARCH
            else -> UserFacingStatus.PROCESSING
        }
        StatusUiModel(
            status = running,
            canCancel = true,
            isCloud = currentStage in CLOUD_STAGES,
            stage = currentStage
        )
    }
}

/** LLM-driven stages that run over the network; everything else is on-device. */
private val CLOUD_STAGES = setOf(
    ProcessingStage.EXTRACTING_ENTITIES,
    ProcessingStage.EXTRACTING_TIMELINE
)
