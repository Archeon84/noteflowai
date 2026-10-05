package com.noteflowai.app.data.memory.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.DecisionStatus
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import com.noteflowai.app.data.memory.model.MemoryReviewItem
import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.model.ReviewItemType
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for cascade deletion ordering.
 *
 * Child deletes are expressed as `sourceSegmentId IN (SELECT id FROM
 * source_segments ...)` — deleting segments first turns them into silent
 * no-ops and orphans decisions/commitments/mentions/relations/citations.
 * These tests prove children disappear along with their segments.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryRepositoryDeleteTest {

    private lateinit var db: MemoryDatabase
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemoryRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedSource(sourceId: String, seed: String) {
        db.sourceSegmentDao().insert(
            SourceSegment(
                id = "seg_$seed",
                sourceId = sourceId,
                sourceType = SourceType.NOTE,
                text = "text $seed",
                normalizedText = "text $seed"
            )
        )
        db.memoryObjectDao().insert(
            MemoryObject(
                id = "mem_$seed",
                type = MemoryType.DECISION,
                statement = "statement $seed",
                normalizedStatement = "statement $seed",
                sourceSegmentId = "seg_$seed",
                sourceId = sourceId
            )
        )
        db.decisionDao().insert(
            Decision(
                id = "dec_$seed",
                memoryObjectId = "mem_$seed",
                statement = "statement $seed",
                sourceSegmentId = "seg_$seed"
            )
        )
        db.memoryReviewItemDao().insert(
            MemoryReviewItem(
                id = "rev_$seed",
                type = ReviewItemType.DECISION,
                referencedObjectId = "dec_$seed",
                reason = "reason"
            )
        )
    }

    @Test
    fun `deleteDerivedDataForSource removes children proved by segment subquery`() = runTest {
        seedSource("note_a", "a")
        seedSource("note_b", "b")

        repository.deleteDerivedDataForSource("note_a")

        // Source A's rows are gone — including the decision, whose delete runs
        // through the segment subquery and would no-op if segments went first.
        assertNull(db.decisionDao().getById("dec_a"))
        assertNull(db.memoryObjectDao().getById("mem_a"))
        assertNull(db.memoryReviewItemDao().getById("rev_a"))
        assertEquals(0, db.sourceSegmentDao().countBySourceId("note_a"))
        // Source B is untouched.
        assertEquals(DecisionStatus.DETECTED, db.decisionDao().getById("dec_b")?.status)
        assertEquals(1, db.sourceSegmentDao().countBySourceId("note_b"))
    }

    @Test
    fun `resetSourceForRebuild wipes derived rows but preserves attempts`() = runTest {
        seedSource("note_a", "a")
        db.processingStatusDao().upsert(
            com.noteflowai.app.data.memory.model.ProcessingStatus(
                sourceId = "note_a",
                sourceType = SourceType.NOTE,
                currentStage = com.noteflowai.app.data.memory.model.ProcessingStage.COMPLETE,
                status = com.noteflowai.app.data.memory.model.ProcessingState.FAILED_RETRYABLE,
                attempts = 7
            )
        )

        repository.resetSourceForRebuild("note_a", SourceType.NOTE)

        assertNull(db.decisionDao().getById("dec_a"))
        assertNull(db.memoryObjectDao().getById("mem_a"))
        val status = db.processingStatusDao().getBySourceId("note_a")
        assertEquals(7, status?.attempts)
        assertEquals(
            com.noteflowai.app.data.memory.model.ProcessingState.PENDING,
            status?.status
        )
    }

    @Test
    fun `accept on entity review confirms both confirmation columns`() = runTest {
        val reviewRepository = MemoryReviewRepository(db)
        val entityId = "ent_accept_test"
        val reviewId = "rev_accept_test"
        db.entityDao().insert(
            com.noteflowai.app.data.memory.model.Entity(
                id = entityId,
                type = com.noteflowai.app.data.memory.model.EntityType.PERSON,
                canonicalName = "Accept",
                normalizedName = "accept"
            )
        )
        db.memoryReviewItemDao().insert(
            MemoryReviewItem(
                id = reviewId,
                type = ReviewItemType.ENTITY,
                referencedObjectId = entityId,
                reason = "reason"
            )
        )

        reviewRepository.accept(reviewId)

        val entity = db.entityDao().getById(entityId)
        assertEquals(
            com.noteflowai.app.data.memory.model.ConfirmationState.CONFIRMED,
            entity?.confirmation
        )
        assertEquals(true, entity?.userConfirmed)
    }
}
