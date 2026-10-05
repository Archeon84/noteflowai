package com.noteflowai.app.data.memory.timeline

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.extraction.ExtractedTimelineEntry
import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.model.TemporalPrecision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Timeline persistence for guide §Phase 4, tested against an in-memory Room database:
 *
 *  - re-extraction dedups by (title, segment, startMs) so a confirmed/rejected entry is not
 *    duplicated or reset back to SUGGESTED;
 *  - source-wide replace/delete is used by the rebuild to swap fresh derived entries;
 *  - domain edits (confirm / reject / edit) recompute precision from the edited range.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineRepositoryTest {

    private lateinit var db: MemoryDatabase
    private lateinit var repo: TimelineRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TimelineRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun segment(id: String = "seg_1", sourceId: String = "note_1") = SourceSegment(
        id = id,
        sourceId = sourceId,
        sourceType = SourceType.NOTE,
        text = "we decided to launch the platform in march",
        normalizedText = "we decided to launch the platform in march"
    )

    private fun entry(
        title: String = "Launched the platform",
        startDate: String? = "2026-03-01",
        endDate: String? = null,
        precision: String = "EXACT",
        confidence: Float = 0.9f,
        segmentId: String = "seg_1"
    ) = ExtractedTimelineEntry(
        title = title,
        start_date = startDate,
        end_date = endDate,
        precision = precision,
        confidence = confidence,
        source_segment_id = segmentId
    )

    @Test
    fun `insertFromExtraction stores a row with derived fields`() = runTest {
        val row = repo.insertFromExtraction(entry(), segment())

        assertEquals("Launched the platform", row?.title)
        assertEquals(TemporalPrecision.EXACT, row?.precision)
        assertEquals("note_1", row?.sourceNoteId)
        assertEquals(ConfirmationState.SUGGESTED, row?.confirmation)
        assertTrue(row?.startMs != null)
    }

    @Test
    fun `re-extraction dedups identical title segment and date`() = runTest {
        repo.insertFromExtraction(entry(), segment())
        repo.insertFromExtraction(entry(), segment())

        assertEquals(1, repo.getAll().size)
    }

    @Test
    fun `re-extraction does not reset a confirmed or rejected entry`() = runTest {
        val row = repo.insertFromExtraction(entry(), segment())!!
        repo.confirm(row.id)

        // A rebuild re-extracts the same title/date/segment: the row is skipped, state survives.
        repo.insertFromExtraction(entry(), segment())

        assertEquals(1, repo.getAll().size)
        assertEquals(ConfirmationState.CONFIRMED, repo.getById(row.id)?.confirmation)
    }

    @Test
    fun `confirm and reject update only confirmation`() = runTest {
        val row = repo.insertFromExtraction(entry(), segment())!!
        repo.confirm(row.id)
        assertEquals(ConfirmationState.CONFIRMED, repo.getById(row.id)?.confirmation)

        repo.reject(row.id)
        assertEquals(ConfirmationState.REJECTED, repo.getById(row.id)?.confirmation)
    }

    @Test
    fun `edit recomputes precision and confirms the entry`() = runTest {
        val row = repo.insertFromExtraction(entry(), segment())!!
        repo.edit(row.id, "Renamed event", 1_000_000L, 2_000_000L)

        val edited = repo.getById(row.id)!!
        assertEquals("Renamed event", edited.title)
        assertEquals(TemporalPrecision.DAY_RANGE, edited.precision)
        assertEquals(ConfirmationState.CONFIRMED, edited.confirmation)
    }

    @Test
    fun `edit with equal dates yields exact precision`() = runTest {
        val row = repo.insertFromExtraction(entry(), segment())!!
        repo.edit(row.id, "Single date event", 5_000_000L, 5_000_000L)

        assertEquals(TemporalPrecision.EXACT, repo.getById(row.id)?.precision)
    }

    @Test
    fun `replaceSource swaps a source rows and deleteBySourceId clears them`() = runTest {
        repo.insertFromExtraction(entry(title = "Event A"), segment())
        repo.insertFromExtraction(entry(title = "Event B", segmentId = "seg_2"), segment(id = "seg_2"))

        repo.replaceSource("note_1", listOf(
            repo.insertFromExtraction(entry(title = "Event C"), segment())!!
        ))

        assertEquals(listOf("Event C"), repo.getAll().map { it.title })

        repo.deleteBySourceId("note_1")
        assertTrue(repo.getAll().isEmpty())
    }

    @Test
    fun `observeAll emits the stored entries`() = runTest {
        repo.insertFromExtraction(entry(), segment())
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test
    fun `bad precision string falls back to unknown`() = runTest {
        val row = repo.insertFromExtraction(entry(precision = "BOGUS"), segment())!!
        assertEquals(TemporalPrecision.UNKNOWN, row.precision)
    }

    @Test
    fun `unknown non existent entry edits are no-ops`() = runTest {
        repo.edit("nope", "title", 1L, 2L)
        assertNull(repo.getById("nope"))
    }
}