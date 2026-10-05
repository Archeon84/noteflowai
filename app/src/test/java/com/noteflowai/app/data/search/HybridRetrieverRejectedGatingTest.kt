package com.noteflowai.app.data.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.db.MemoryDatabase
import com.noteflowai.app.data.memory.model.ConfirmationState
import com.noteflowai.app.data.memory.model.Entity
import com.noteflowai.app.data.memory.model.EntityMention
import com.noteflowai.app.data.memory.model.EntityType
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.pipeline.SegmentEmbeddingService
import com.noteflowai.app.data.memory.repository.CommitmentRepository
import com.noteflowai.app.data.memory.repository.DecisionRepository
import com.noteflowai.app.data.memory.repository.EntityRepository
import com.noteflowai.app.data.memory.repository.MemoryRepository
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import com.noteflowai.app.data.memory.timeline.TimelineRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Retrieval gating for guide §Phase 4: rejected entities and rejected links must never
 * surface in [HybridRetriever] results.
 *
 * Uses the real [EntityRepository] over an in-memory database (its `searchActive` /
 * `getActiveMentions` honor the REJECTED state at the SQL layer) while mocking the
 * surrounding retrieval inputs, so the test exercises the retriever end-to-end against
 * rejected state without the network/embedding stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class HybridRetrieverRejectedGatingTest {

    private lateinit var db: MemoryDatabase
    private lateinit var entityRepository: EntityRepository
    private lateinit var sourceSegmentRepository: SourceSegmentRepository
    private lateinit var decisionRepository: DecisionRepository
    private lateinit var retriever: HybridRetriever

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        entityRepository = EntityRepository(db)
        sourceSegmentRepository = mockk()
        decisionRepository = mockk()

        retriever = HybridRetriever(
            context,
            mockk(), // noteSearchIndex
            mockk(), // embeddingIndex
            mockk(), // remoteEmbeddingClient
            mockk(), // onDeviceEmbedder
            sourceSegmentRepository,
            decisionRepository,
            mockk(), // commitmentRepository
            mockk(), // memoryRepository
            entityRepository,
            mockk(), // segmentEmbeddingService
            TimelineRepository(db)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(id: String, name: String, type: EntityType = EntityType.PERSON) = Entity(
        id = id,
        type = type,
        canonicalName = name,
        normalizedName = name.lowercase()
    )

    private fun mention(entityId: String, segmentId: String) = EntityMention(
        entityId = entityId,
        sourceSegmentId = segmentId,
        mentionText = "mention"
    )

    private fun segment(id: String, sourceId: String = "note_1") = SourceSegment(
        id = id,
        sourceId = sourceId,
        sourceType = SourceType.NOTE,
        text = "segment text $id",
        normalizedText = "segment text $id"
    )

    private fun stubSegmentLookup() {
        coEvery { sourceSegmentRepository.getByIds(any()) } answers {
            val ids = firstArg<List<String>>()
            ids.map { segment(it) }
        }
    }

    @Test
    fun `retrieveEntities never surfaces a rejected entity or rejected mention`() = runTest {
        // Alice is confirmed with an active mention; Bob was rejected and keeps a mention.
        entityRepository.insert(entity("e_ok", "Alice"))
        entityRepository.insert(entity("e_bad", "Bob"))
        entityRepository.insertWithMention(entity("e_ok", "Alice"), mention("e_ok", "seg_ok"))
        entityRepository.insertWithMention(entity("e_bad", "Bob"), mention("e_bad", "seg_bad"))
        entityRepository.reject("e_bad")
        stubSegmentLookup()

        val plan = QueryPlan(
            intent = QueryIntent.ENTITY_LOOKUP,
            queryText = "Alice Bob",
            entities = listOf("Alice", "Bob")
        )
        val results = retriever.retrieve(plan, "", false, "", "", "", "").results

        // Only the confirmed Alice mention is retrievable; Bob is nowhere.
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.metadata?.get("entityName") != "Bob" })
        assertTrue(results.all {
            it.metadata?.get("entityType") != EntityType.PERSON.name || it.sourceSegmentId == "seg_ok"
        })
    }

    @Test
    fun `a purely rejected entity yields no entity results`() = runTest {
        entityRepository.insert(entity("e_bad", "Bob"))
        entityRepository.insertWithMention(entity("e_bad", "Bob"), mention("e_bad", "seg_bad"))
        entityRepository.reject("e_bad")
        stubSegmentLookup()

        val plan = QueryPlan(
            intent = QueryIntent.ENTITY_LOOKUP,
            queryText = "Bob",
            entities = listOf("Bob")
        )
        val results = retriever.retrieve(plan, "", false, "", "", "", "").results

        // No entity mention survives rejection.
        assertTrue(results.none { it.metadata?.get("type") == "entity_mention" })
    }

    @Test
    fun `removed link on a confirmed entity is not retrievable`() = runTest {
        entityRepository.insert(entity("e_ok", "Alice"))
        entityRepository.insertWithMention(entity("e_ok", "Alice"), mention("e_ok", "seg_ok"))
        entityRepository.insertWithMention(entity("e_ok", "Alice"), mention("e_ok", "seg_removed"))
        entityRepository.removeLink("e_ok", "seg_removed")
        stubSegmentLookup()

        val plan = QueryPlan(
            intent = QueryIntent.ENTITY_LOOKUP,
            queryText = "Alice",
            entities = listOf("Alice")
        )
        val results = retriever.retrieve(plan, "", false, "", "", "", "").results

        assertTrue(results.any { it.sourceSegmentId == "seg_ok" })
        assertTrue(results.none { it.sourceSegmentId == "seg_removed" })
    }

    @Test
    fun `retrieveProjects filters project entities through active search`() = runTest {
        entityRepository.insert(entity("p_ok", "App", EntityType.PROJECT))
        entityRepository.insert(entity("p_bad", "Dead", EntityType.PROJECT))
        entityRepository.reject("p_bad")

        // Projects route through decisions/commitments, which are mocked empty here, so no
        // project segment results can be produced; what matters is the rejected project never
        // triggers a getByProject lookup either.
        coEvery { sourceSegmentRepository.getByIds(any()) } returns emptyList()
        coEvery { decisionRepository.getByProject(any()) } returns emptyList()

        val plan = QueryPlan(
            intent = QueryIntent.PROJECT_LOOKUP,
            queryText = "App",
            entities = emptyList()
        )
        retriever.retrieve(plan, "", false, "", "", "", "")

        // The rejected project never reaches a getByProject reference lookup, so it cannot
        // surface a decision/commitment segment as a project result.
        coVerify(exactly = 0) { decisionRepository.getByProject("p_bad") }
        assertEquals(ConfirmationState.REJECTED, entityRepository.getById("p_bad")?.confirmation)
    }
}