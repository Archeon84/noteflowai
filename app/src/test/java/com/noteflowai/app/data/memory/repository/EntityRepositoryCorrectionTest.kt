package com.noteflowai.app.data.memory.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.Entity
import com.noteflowai.app.data.memory.model.EntityMention
import com.noteflowai.app.data.memory.model.EntityType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Entity correction behavior for guide §Phase 4, tested against an in-memory Room database:
 *
 *  - `reject` durably marks the entity REJECTED and clears `userConfirmed`;
 *  - `confirm` marks CONFIRMED and `userConfirmed=true`;
 *  - `removeLink` marks only that mention REJECTED (row survives), others untouched;
 *  - `insertWithMention` never re-inserts over a REJECTED mention and never attaches a new
 *    mention to a REJECTED entity (the rebuild preserve+skip-rejected contract).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class EntityRepositoryCorrectionTest {

    private lateinit var db: MemoryDatabase
    private lateinit var repo: EntityRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = EntityRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(id: String = "e1", name: String = "Alice") = Entity(
        id = id,
        type = EntityType.PERSON,
        canonicalName = name,
        aliasesJson = null,
        normalizedName = name.lowercase()
    )

    private fun mention(
        entityId: String = "e1",
        segmentId: String = "seg_1",
        text: String = "Alice"
    ) = EntityMention(
        entityId = entityId,
        sourceSegmentId = segmentId,
        mentionText = text
    )

    @Test
    fun `confirm sets confirmation confirmed and userConfirmed`() = runTest {
        repo.insert(entity())
        repo.confirm("e1")

        val updated = repo.getById("e1")
        assertEquals(ConfirmationState.CONFIRMED, updated?.confirmation)
        assertTrue(updated?.userConfirmed == true)
    }

    @Test
    fun `reject sets confirmation rejected and clears userConfirmed`() = runTest {
        repo.insert(entity())
        repo.confirm("e1")
        repo.reject("e1")

        val updated = repo.getById("e1")
        assertEquals(ConfirmationState.REJECTED, updated?.confirmation)
        assertTrue(updated?.userConfirmed == false)
    }

    @Test
    fun `removeLink marks only that mention rejected and keeps the row`() = runTest {
        repo.insert(entity())
        repo.insertWithMention(entity(), mention(segmentId = "seg_1"))
        repo.insertWithMention(entity(), mention(segmentId = "seg_2"))

        repo.removeLink("e1", "seg_1")

        val mentions = repo.getMentions("e1")
        assertEquals(2, mentions.size)
        assertEquals(
            ConfirmationState.REJECTED,
            mentions.first { it.sourceSegmentId == "seg_1" }.confirmation
        )
        assertEquals(
            ConfirmationState.SUGGESTED,
            mentions.first { it.sourceSegmentId == "seg_2" }.confirmation
        )
    }

    @Test
    fun `insertWithMention does not re-insert over a rejected mention`() = runTest {
        repo.insert(entity())
        repo.insertWithMention(entity(), mention(segmentId = "seg_1"))
        repo.removeLink("e1", "seg_1")

        // A rebuild re-extracts the same mention: the seat stays REJECTED.
        repo.insertWithMention(entity(), mention(segmentId = "seg_1"))

        val mentions = repo.getMentions("e1")
        assertEquals(1, mentions.size)
        assertEquals(ConfirmationState.REJECTED, mentions[0].confirmation)
    }

    @Test
    fun `insertWithMention does not attach a new mention to a rejected entity`() = runTest {
        repo.insert(entity())
        repo.reject("e1")

        // A rebuild re-extracts this entity's mention: rejected entities never re-attract.
        repo.insertWithMention(entity(), mention(segmentId = "seg_new"))

        val mentions = repo.getMentions("e1")
        assertTrue(mentions.isEmpty())
    }

    @Test
    fun `insertWithMention links to existing entity and stores the mention`() = runTest {
        repo.insert(entity())
        repo.insertWithMention(entity(name = "Alice"), mention(segmentId = "seg_1"))

        val mentions = repo.getMentions("e1")
        assertEquals(1, mentions.size)
        assertEquals("e1", mentions[0].entityId)
        assertNotNull(repo.getById("e1"))
    }

    @Test
    fun `active mentions exclude rejected ones but evidence keeps them`() = runTest {
        repo.insert(entity())
        repo.insertWithMention(entity(), mention(segmentId = "seg_1"))
        repo.insertWithMention(entity(), mention(segmentId = "seg_2"))
        repo.removeLink("e1", "seg_1")

        val active = repo.getActiveMentions("e1")
        assertEquals(listOf("seg_2"), active.map { it.sourceSegmentId })
        assertEquals(2, repo.getMentions("e1").size) // evidence still shows both rows
    }

    @Test
    fun `searchActive excludes rejected entities`() = runTest {
        repo.insert(entity())
        repo.insert(entity(id = "e2", name = "Bob"))
        repo.reject("e2")

        val results = repo.searchActive("")
        assertTrue(results.all { it.confirmation != ConfirmationState.REJECTED })
        assertEquals(listOf("e1"), results.map { it.id })
    }

    @Test
    fun `searchActive matches by aliasesJson`() = runTest {
        val entityWithAlias = Entity(
            id = "e1",
            type = EntityType.ORGANIZATION,
            canonicalName = "NoteFlowAI",
            aliasesJson = """["NoteFlow", "NFAI"]""",
            normalizedName = "noteflowai"
        )
        repo.insert(entityWithAlias)

        val results = repo.searchActive("noteflow")
        assertEquals(1, results.size)
        assertEquals("e1", results[0].id)

        val aliasOnlyResults = repo.searchActive("NFAI")
        assertEquals(1, aliasOnlyResults.size)
        assertEquals("e1", aliasOnlyResults[0].id)
    }
}