package com.noteflowai.app.data.memory.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.noteflowai.app.data.memory.dao.EntityMentionDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.dao.MemoryRelationDao
import com.noteflowai.app.data.memory.model.EntityMention
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.memory.model.RelationType
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.memory.repository.SourceSegmentRepository
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for RelationDetectionService (§7 RELATION_DETECTION stage).
 *
 * Verifies the conservative, deterministic links: MENTIONS from a memory
 * object to entities in the same segment, and RELATED_TO between objects that
 * co-occur in a segment. Re-processing is idempotent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelationDetectionServiceTest {

    private lateinit var context: Context
    private lateinit var sourceSegmentRepository: SourceSegmentRepository
    private lateinit var memoryObjectDao: MemoryObjectDao
    private lateinit var entityMentionDao: EntityMentionDao
    private lateinit var memoryRelationDao: MemoryRelationDao
    private lateinit var service: RelationDetectionService

    private val sourceId = "src_1"
    private val segmentId = "seg_1"

    private fun segment(id: String = segmentId) = SourceSegment(
        id = id,
        sourceId = sourceId,
        sourceType = SourceType.NOTE,
        text = "postgres is our choice",
        normalizedText = "postgres is our choice"
    )

    private fun memoryObject(id: String, type: MemoryType = MemoryType.DECISION) = MemoryObject(
        id = id,
        type = type,
        statement = "we should migrate to postgres",
        normalizedStatement = "we should migrate to postgres",
        status = MemoryObjectStatus.DETECTED,
        sourceSegmentId = segmentId,
        sourceId = sourceId,
        extractedAt = 1_000L
    )

    private fun mention(entityId: String) = EntityMention(
        entityId = entityId,
        sourceSegmentId = segmentId,
        mentionText = "postgres",
        confidence = 0.9f
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sourceSegmentRepository = mockk()
        memoryObjectDao = mockk()
        entityMentionDao = mockk()
        memoryRelationDao = mockk()
        service = RelationDetectionService(
            context,
            sourceSegmentRepository,
            memoryObjectDao,
            entityMentionDao,
            memoryRelationDao
        )
    }

    @Test
    fun `creates mentions from memory objects to entities in the same segment`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { memoryObjectDao.getBySourceId(sourceId) } returns listOf(memoryObject("mem_1"))
        coEvery { entityMentionDao.getActiveBySegmentIds(listOf(segmentId)) } returns listOf(mention("ent_1"))
        val inserted = CapturingSlot<List<com.noteflowai.app.data.memory.model.MemoryRelation>>()
        coEvery { memoryRelationDao.deleteBySourceId(sourceId) } returns Unit
        coEvery { memoryRelationDao.insertAll(capture(inserted)) } returns Unit

        val count = service.detectForSource(sourceId)

        assertEquals(1, count)
        val relations = inserted.captured
        assertEquals(1, relations.size)
        val rel = relations[0]
        assertEquals("DECISION", rel.fromType)
        assertEquals("mem_1", rel.fromId)
        assertEquals("ENTITY", rel.toType)
        assertEquals("ent_1", rel.toId)
        assertEquals(RelationType.MENTIONS, rel.relationType)
        assertEquals(segmentId, rel.sourceSegmentId)
        assertEquals(0.5f, rel.confidence, 0.001f)
    }

    @Test
    fun `creates related_to for co-occurring objects in a segment`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery {
            memoryObjectDao.getBySourceId(sourceId)
        } returns listOf(memoryObject("mem_1"), memoryObject("mem_2"))
        coEvery { entityMentionDao.getActiveBySegmentIds(listOf(segmentId)) } returns emptyList()
        val inserted = CapturingSlot<List<com.noteflowai.app.data.memory.model.MemoryRelation>>()
        coEvery { memoryRelationDao.deleteBySourceId(sourceId) } returns Unit
        coEvery { memoryRelationDao.insertAll(capture(inserted)) } returns Unit

        val count = service.detectForSource(sourceId)

        // Two objects co-occurring yield exactly one RELATED_TO edge.
        assertEquals(1, count)
        val rel = inserted.captured[0]
        assertEquals(RelationType.RELATED_TO, rel.relationType)
        assertEquals(0.4f, rel.confidence, 0.001f)
    }

    @Test
    fun `three co-occurring objects yield three related pairs`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery {
            memoryObjectDao.getBySourceId(sourceId)
        } returns listOf(memoryObject("mem_1"), memoryObject("mem_2"), memoryObject("mem_3"))
        coEvery { entityMentionDao.getActiveBySegmentIds(listOf(segmentId)) } returns emptyList()
        val inserted = CapturingSlot<List<com.noteflowai.app.data.memory.model.MemoryRelation>>()
        coEvery { memoryRelationDao.deleteBySourceId(sourceId) } returns Unit
        coEvery { memoryRelationDao.insertAll(capture(inserted)) } returns Unit

        val count = service.detectForSource(sourceId)

        assertEquals(3, count)
        assertTrue(inserted.captured.all { it.relationType == RelationType.RELATED_TO })
    }

    @Test
    fun `reprocessing deletes existing relations first for idempotency`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { memoryObjectDao.getBySourceId(sourceId) } returns emptyList()
        coEvery { entityMentionDao.getActiveBySegmentIds(listOf(segmentId)) } returns emptyList()

        service.detectForSource(sourceId)

        coVerify(exactly = 1) { memoryRelationDao.deleteBySourceId(sourceId) }
        coVerify(exactly = 0) { memoryRelationDao.insertAll(any()) }
    }

    @Test
    fun `returns zero when the source has no segments`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns emptyList()

        val count = service.detectForSource(sourceId)

        assertEquals(0, count)
        coVerify(exactly = 0) { memoryRelationDao.insertAll(any()) }
    }

    @Test
    fun `swallows dao exceptions and returns zero`() = runTest {
        coEvery { sourceSegmentRepository.getBySourceId(sourceId) } returns listOf(segment())
        coEvery { memoryObjectDao.getBySourceId(sourceId) } throws RuntimeException("db locked")

        val count = service.detectForSource(sourceId)

        assertEquals(0, count)
    }
}
