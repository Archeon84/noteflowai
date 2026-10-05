package com.noteflowai.app.data.memory.db

import android.content.Context
import com.noteflowai.app.data.capture.RawCaptureDao
import com.noteflowai.app.data.memory.dao.AnswerCitationDao
import com.noteflowai.app.data.memory.dao.CommitmentDao
import com.noteflowai.app.data.memory.dao.ConflictDao
import com.noteflowai.app.data.memory.dao.DecisionDao
import com.noteflowai.app.data.memory.dao.EntityDao
import com.noteflowai.app.data.memory.dao.EntityMentionDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.dao.MemoryRelationDao
import com.noteflowai.app.data.memory.dao.MemoryReviewItemDao
import com.noteflowai.app.data.memory.dao.ProcessingStatusDao
import com.noteflowai.app.data.memory.dao.SourceSegmentDao
import com.noteflowai.app.data.memory.dao.TimelineEntryDao

/**
 * Manual dependency provision for the Personal Memory Layer database.
 * Delegates to [MemoryDatabase.getInstance] for the single authoritative singleton.
 */
object MemoryDatabaseModule {

    fun getDatabase(context: Context): MemoryDatabase =
        MemoryDatabase.getInstance(context)

    fun provideSourceSegmentDao(context: Context): SourceSegmentDao =
        getDatabase(context).sourceSegmentDao()

    fun provideEntityDao(context: Context): EntityDao =
        getDatabase(context).entityDao()

    fun provideEntityMentionDao(context: Context): EntityMentionDao =
        getDatabase(context).entityMentionDao()

    fun provideMemoryObjectDao(context: Context): MemoryObjectDao =
        getDatabase(context).memoryObjectDao()

    fun provideDecisionDao(context: Context): DecisionDao =
        getDatabase(context).decisionDao()

    fun provideCommitmentDao(context: Context): CommitmentDao =
        getDatabase(context).commitmentDao()

    fun provideMemoryRelationDao(context: Context): MemoryRelationDao =
        getDatabase(context).memoryRelationDao()

    fun provideMemoryReviewItemDao(context: Context): MemoryReviewItemDao =
        getDatabase(context).memoryReviewItemDao()

    fun provideAnswerCitationDao(context: Context): AnswerCitationDao =
        getDatabase(context).answerCitationDao()

    fun provideConflictDao(context: Context): ConflictDao =
        getDatabase(context).conflictDao()

    fun provideProcessingStatusDao(context: Context): ProcessingStatusDao =
        getDatabase(context).processingStatusDao()

    fun provideRawCaptureDao(context: Context): RawCaptureDao =
        getDatabase(context).rawCaptureDao()

    fun provideTimelineEntryDao(context: Context): TimelineEntryDao =
        getDatabase(context).timelineEntryDao()
}
