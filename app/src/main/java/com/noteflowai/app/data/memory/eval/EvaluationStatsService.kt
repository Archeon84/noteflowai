package com.noteflowai.app.data.memory.eval

import android.content.Context
import com.noteflowai.app.data.memory.dao.AnswerCitationDao
import com.noteflowai.app.data.memory.dao.ConflictDao
import com.noteflowai.app.data.memory.dao.EntityDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.dao.MemoryRelationDao
import com.noteflowai.app.data.memory.dao.MemoryReviewItemDao
import com.noteflowai.app.data.memory.dao.ProcessingStatusDao
import com.noteflowai.app.data.memory.dao.SourceSegmentDao
import com.noteflowai.app.data.memory.db.MemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 8d: Aggregates persisted personal-memory-layer data into honest,
 * labeled evaluation metrics for the Evaluation dashboard.
 *
 * Pure data aggregation -- no LLM calls, no Android framework calls beyond
 * the [Context] secondary constructor that wires DAOs from [MemoryDatabase].
 * DAOs are injected via the primary constructor for testability, matching
 * the [com.noteflowai.app.data.memory.analysis.WeeklyReviewService] pattern.
 *
 * Metrics that require an external annotated dataset and an LLM judge
 * (answer faithfulness, relevance, abstention correctness, source-navigation
 * accuracy, false-extraction rates) are intentionally NOT computed here;
 * the screen surfaces them as a static limitations card instead.
 */
class EvaluationStatsService(
    private val processingStatusDao: ProcessingStatusDao,
    private val answerCitationDao: AnswerCitationDao,
    private val reviewItemDao: MemoryReviewItemDao,
    private val memoryObjectDao: MemoryObjectDao,
    private val conflictDao: ConflictDao,
    private val entityDao: EntityDao,
    private val relationDao: MemoryRelationDao,
    private val sourceSegmentDao: SourceSegmentDao
) {

    // One singleton lookup, not eight synchronized map hits per screen open.
    private constructor(daos: Daos) : this(
        daos.processingStatusDao,
        daos.answerCitationDao,
        daos.reviewItemDao,
        daos.memoryObjectDao,
        daos.conflictDao,
        daos.entityDao,
        daos.relationDao,
        daos.sourceSegmentDao
    )

    constructor(context: Context) : this(Daos.fromDb(MemoryDatabase.getInstance(context)))

    private data class Daos(
        val processingStatusDao: ProcessingStatusDao,
        val answerCitationDao: AnswerCitationDao,
        val reviewItemDao: MemoryReviewItemDao,
        val memoryObjectDao: MemoryObjectDao,
        val conflictDao: ConflictDao,
        val entityDao: EntityDao,
        val relationDao: MemoryRelationDao,
        val sourceSegmentDao: SourceSegmentDao
    ) {
        companion object {
            fun fromDb(db: MemoryDatabase) = Daos(
                db.processingStatusDao(),
                db.answerCitationDao(),
                db.memoryReviewItemDao(),
                db.memoryObjectDao(),
                db.conflictDao(),
                db.entityDao(),
                db.memoryRelationDao(),
                db.sourceSegmentDao()
            )
        }
    }

    /** Stuck-source cutoff: PENDING/RUNNING/FAILED_RETRYABLE untouched for this long. */
    private val stuckCutoffMs = 24L * 60 * 60 * 1000

    data class EvaluationSnapshot(
        // Pipeline health
        val pipelineTotalSources: Int,
        val pipelineCompleted: Int,
        val pipelineFailedPermanent: Int,
        val pipelineFailedRetryable: Int,
        val pipelinePendingRunning: Int,
        val pipelineCancelled: Int,
        val pipelineAvgAttempts: Double,
        val pipelineRetriedCount: Int,
        val pipelineStuckCount: Int,
        val pipelineCompletionRate: Float,
        // Citation validity
        val citationTotal: Int,
        val citationValidated: Int,
        val citationInvalid: Int,
        val citationValidityRate: Float,
        val citationDistinctAnswers: Int,
        val citationByLocationType: Map<String, Int>,
        // Extraction precision proxy
        val reviewPending: Int,
        val reviewAccepted: Int,
        val reviewEdited: Int,
        val reviewIgnored: Int,
        val reviewConfirmationRate: Float,
        val memoryObjectDetected: Int,
        val memoryObjectConfirmedActive: Int,
        val memoryObjectTotal: Int,
        val pendingConflicts: Int,
        // System volume
        val entityCount: Int,
        val relationCount: Int,
        val segmentCount: Int
    ) {
        /** True when nothing has been recorded yet -- show the empty state. */
        val isEmpty: Boolean
            get() = pipelineTotalSources == 0 && citationTotal == 0 && memoryObjectTotal == 0 &&
                entityCount == 0 && segmentCount == 0
    }

    suspend fun buildSnapshot(): EvaluationSnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // -- Pipeline health --
        val pipelineCounts = processingStatusDao.countByStatus().associate { it.status to it.cnt }
        val pipelineTotal = processingStatusDao.totalCount()
        val completed = pipelineCounts["COMPLETED"] ?: 0
        val failedPermanent = pipelineCounts["FAILED_PERMANENT"] ?: 0
        val failedRetryable = pipelineCounts["FAILED_RETRYABLE"] ?: 0
        // SKIPPED rows are parked but non-terminal (recovery resumes them),
        // so they count as pending work rather than vanishing from the board.
        val pendingRunning = (pipelineCounts["PENDING"] ?: 0) +
            (pipelineCounts["RUNNING"] ?: 0) +
            (pipelineCounts["SKIPPED"] ?: 0)
        val cancelled = pipelineCounts["CANCELLED"] ?: 0

        // -- Citation validity --
        val supportCounts = answerCitationDao.countBySupportStatus().associate { it.status to it.cnt }
        val citationTotal = answerCitationDao.totalCount()
        val validated = supportCounts["VALIDATED"] ?: 0
        val invalid = supportCounts["INVALID"] ?: 0
        val citationByLocationType = answerCitationDao.countByLocationType()
            .associate { it.status to it.cnt }

        // -- Extraction precision proxy --
        val reviewCounts = reviewItemDao.countByStatus().associate { it.status to it.cnt }
        val reviewPending = reviewCounts["PENDING"] ?: 0
        val reviewAccepted = reviewCounts["ACCEPTED"] ?: 0
        val reviewEdited = reviewCounts["EDITED"] ?: 0
        val reviewIgnored = reviewCounts["IGNORED"] ?: 0
        val reviewResolved = reviewAccepted + reviewEdited + reviewIgnored

        val memoryCounts = memoryObjectDao.countByStatus().associate { it.status to it.cnt }
        val memoryObjectTotal = memoryObjectDao.totalCount()
        val memoryDetected = memoryCounts["DETECTED"] ?: 0
        // Only genuinely live states count — the old total-minus-DETECTED
        // mislabeled CANCELLED/SUPERSEDED/COMPLETED rows as confirmed.
        val memoryConfirmedActive = (memoryCounts["CONFIRMED"] ?: 0) +
            (memoryCounts["ACTIVE"] ?: 0)

        EvaluationSnapshot(
            pipelineTotalSources = pipelineTotal,
            pipelineCompleted = completed,
            pipelineFailedPermanent = failedPermanent,
            pipelineFailedRetryable = failedRetryable,
            pipelinePendingRunning = pendingRunning,
            pipelineCancelled = cancelled,
            pipelineAvgAttempts = processingStatusDao.avgAttempts() ?: 0.0,
            pipelineRetriedCount = processingStatusDao.countRetried(),
            pipelineStuckCount = processingStatusDao.countStuck(now - stuckCutoffMs),
            pipelineCompletionRate = if (pipelineTotal > 0) completed.toFloat() / pipelineTotal else 0f,

            citationTotal = citationTotal,
            citationValidated = validated,
            citationInvalid = invalid,
            // Adjudicated-only rate: unreviewed PENDING citations are not
            // evidence of invalidity, so they stay out of the denominator.
            citationValidityRate = if (validated + invalid > 0) {
                validated.toFloat() / (validated + invalid)
            } else {
                0f
            },
            citationDistinctAnswers = answerCitationDao.distinctAnswerCount(),
            citationByLocationType = citationByLocationType,

            reviewPending = reviewPending,
            reviewAccepted = reviewAccepted,
            reviewEdited = reviewEdited,
            reviewIgnored = reviewIgnored,
            // Overall rate across all review items: excluding PENDING from the
            // denominator overstated confirmation while work sat unreviewed.
            reviewConfirmationRate = if (reviewPending + reviewResolved > 0) {
                (reviewAccepted + reviewEdited).toFloat() / (reviewPending + reviewResolved)
            } else {
                0f
            },
            memoryObjectDetected = memoryDetected,
            memoryObjectConfirmedActive = memoryConfirmedActive,
            memoryObjectTotal = memoryObjectTotal,
            pendingConflicts = conflictDao.pendingCount(),

            entityCount = entityDao.totalCount(),
            relationCount = relationDao.totalCount(),
            segmentCount = sourceSegmentDao.totalCount()
        )
    }
}
