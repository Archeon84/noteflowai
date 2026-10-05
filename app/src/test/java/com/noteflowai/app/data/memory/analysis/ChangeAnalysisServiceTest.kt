package com.noteflowai.app.data.memory.analysis

import com.noteflowai.app.data.chat.AiChatRepository
import com.noteflowai.app.data.chat.ChatMessage
import com.noteflowai.app.data.memory.dao.DecisionDao
import com.noteflowai.app.data.memory.dao.MemoryObjectDao
import com.noteflowai.app.data.memory.model.Decision
import com.noteflowai.app.data.memory.model.DecisionStatus
import com.noteflowai.app.data.memory.model.MemoryObject
import com.noteflowai.app.data.memory.model.MemoryObjectStatus
import com.noteflowai.app.data.memory.model.MemoryType
import com.noteflowai.app.data.settings.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ChangeAnalysisService (personal memory layer, Phase 7).
 *
 * Mocks MemoryObjectDao, DecisionDao, AiChatRepository, and SettingsManager.
 * Robolectric stubs android.util.Log for the error paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangeAnalysisServiceTest {

    private lateinit var memoryObjectDao: MemoryObjectDao
    private lateinit var decisionDao: DecisionDao
    private lateinit var aiChatRepository: AiChatRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var service: ChangeAnalysisService

    private val now = 1_000_000_000_000L

    private fun memoryObject(
        id: String,
        statement: String = "we should migrate to postgres",
        status: MemoryObjectStatus = MemoryObjectStatus.CONFIRMED
    ) = MemoryObject(
        id = id,
        type = MemoryType.DECISION,
        statement = statement,
        normalizedStatement = statement,
        status = status,
        sourceSegmentId = "seg_$id",
        sourceId = "src_$id",
        extractedAt = now - 1000
    )

    private fun decision(
        id: String,
        statement: String = "migrate to postgres",
        status: DecisionStatus = DecisionStatus.CONFIRMED
    ) = Decision(
        id = id,
        memoryObjectId = "mo_$id",
        statement = statement,
        status = status,
        sourceSegmentId = "seg_$id",
        decidedAt = now - 1000,
        createdAt = now - 1000,
        updatedAt = now - 1000
    )

    @Before
    fun setUp() {
        memoryObjectDao = mockk()
        decisionDao = mockk()
        aiChatRepository = mockk()
        settingsManager = mockk()
        // Default settings: AI configured via Ollama
        every { settingsManager.aiBaseUrl } returns flowOf("http://localhost:11434/")
        every { settingsManager.aiApiKey } returns flowOf("dummy-key")
        every { settingsManager.aiModelName } returns flowOf("llama3.2")
        every { settingsManager.aiProvider } returns flowOf("Ollama")
        every { settingsManager.isLocalOnlyMode } returns flowOf(false)
        service = ChangeAnalysisService(
            memoryObjectDao,
            decisionDao,
            aiChatRepository,
            settingsManager
        )
    }

    @Test
    fun `blank base url returns not-configured result without touching daos`() = runTest {
        every { settingsManager.aiBaseUrl } returns flowOf("")

        val result = service.analyze(topic = "migration")

        assertTrue(result.timeline.isEmpty())
        assertTrue(result.currentInterpretation.contains("not configured"))
        coVerify(exactly = 0) { memoryObjectDao.getByDateRange(any(), any()) }
        coVerify(exactly = 0) { decisionDao.getTimeline() }
    }

    @Test
    fun `no entries found returns empty timeline without calling ai`() = runTest {
        coEvery { memoryObjectDao.getByDateRange(any(), any()) } returns emptyList()
        coEvery { decisionDao.getTimeline() } returns emptyList()

        val result = service.analyze(topic = "migration")

        assertTrue(result.timeline.isEmpty())
        assertTrue(result.currentInterpretation.contains("No relevant entries"))
        coVerify(exactly = 0) { aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `successful ai response is parsed into timeline`() = runTest {
        coEvery { memoryObjectDao.getByDateRange(any(), any()) } returns listOf(
            memoryObject("mo_1", "we should migrate to postgres")
        )
        coEvery { decisionDao.getTimeline() } returns emptyList()

        val json = """
            {
              "topic": "migration",
              "timeline": [
                {
                  "date": "2026-08-01",
                  "statement": "we should migrate to postgres",
                  "changeType": "NEW_DECISION",
                  "sourceSegmentIds": ["seg_mo_1"],
                  "memoryObjectIds": ["mo_1"],
                  "confidence": 0.8
                }
              ],
              "currentInterpretation": "The migration is proceeding",
              "confidence": 0.9,
              "needsUserConfirmation": true
            }
        """.trimIndent()
        coEvery {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatMessage(role = "assistant", content = json)

        val result = service.analyze(topic = "migration")

        assertEquals(1, result.timeline.size)
        val entry = result.timeline[0]
        assertEquals("NEW_DECISION", entry.changeType)
        assertEquals("we should migrate to postgres", entry.statement)
        assertEquals(listOf("mo_1"), entry.memoryObjectIds)
        assertTrue(result.currentInterpretation.contains("proceeding"))
        assertTrue(result.needsUserConfirmation)
        coVerify(exactly = 1) {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `markdown-wrapped json response is parsed`() = runTest {
        coEvery { memoryObjectDao.getByDateRange(any(), any()) } returns listOf(
            memoryObject("mo_1")
        )
        coEvery { decisionDao.getTimeline() } returns emptyList()

        val json = """
            ```json
            {
              "topic": "migration",
              "timeline": [],
              "currentInterpretation": "Wrapped in code fence",
              "confidence": 0.5,
              "needsUserConfirmation": true
            }
            ```
        """.trimIndent()
        coEvery {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatMessage(role = "assistant", content = json)

        val result = service.analyze(topic = "migration")

        assertTrue(result.currentInterpretation.contains("code fence"))
        assertTrue(result.timeline.isEmpty())
    }

    @Test
    fun `blank ai response reports empty response`() = runTest {
        coEvery { memoryObjectDao.getByDateRange(any(), any()) } returns listOf(
            memoryObject("mo_1")
        )
        coEvery { decisionDao.getTimeline() } returns emptyList()
        coEvery {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatMessage(role = "assistant", content = "")

        val result = service.analyze(topic = "migration")

        assertTrue(result.timeline.isEmpty())
        assertTrue(result.currentInterpretation.contains("Empty response"))
        assertTrue(result.needsUserConfirmation)
    }

    @Test
    fun `ai exception produces failure result`() = runTest {
        coEvery { memoryObjectDao.getByDateRange(any(), any()) } returns listOf(
            memoryObject("mo_1")
        )
        coEvery { decisionDao.getTimeline() } returns emptyList()
        coEvery {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("connection refused")

        val result = service.analyze(topic = "migration")

        assertTrue(result.timeline.isEmpty())
        assertTrue(result.currentInterpretation.contains("Analysis failed"))
        assertTrue(result.needsUserConfirmation)
    }

    @Test
    fun `unparseable ai response falls back to failure interpretation`() = runTest {
        coEvery { memoryObjectDao.getByDateRange(any(), any()) } returns listOf(
            memoryObject("mo_1")
        )
        coEvery { decisionDao.getTimeline() } returns emptyList()
        coEvery {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatMessage(role = "assistant", content = "not json at all")

        val result = service.analyze(topic = "migration")

        assertTrue(result.timeline.isEmpty())
        assertTrue(result.currentInterpretation.contains("parse"))
    }

    @Test
    fun `date range is forwarded to memory object dao`() = runTest {
        coEvery { memoryObjectDao.getByDateRange(1_000, 2_000) } returns listOf(
            memoryObject("mo_1")
        )
        coEvery { decisionDao.getByDateRange(1_000, 2_000) } returns emptyList()
        coEvery {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatMessage(role = "assistant", content = "{}")

        service.analyze(topic = "migration", dateRange = 1_000L to 2_000L)

        coVerify(exactly = 1) { memoryObjectDao.getByDateRange(1_000, 2_000) }
        coVerify(exactly = 1) { decisionDao.getByDateRange(1_000, 2_000) }
    }

    @Test
    fun `unknown entity filter reports no entries without calling ai`() = runTest {
        val entityDao: com.noteflowai.app.data.memory.dao.EntityDao = mockk()
        val mentionDao: com.noteflowai.app.data.memory.dao.EntityMentionDao = mockk()
        coEvery { entityDao.getByNormalizedName("nosuch") } returns null
        val entityService = ChangeAnalysisService(
            memoryObjectDao,
            decisionDao,
            aiChatRepository,
            settingsManager,
            entityDao,
            mentionDao
        )

        val result = entityService.analyze(entity = "NoSuch")

        assertTrue(result.timeline.isEmpty())
        assertTrue(result.currentInterpretation.contains("No entries mention"))
        coVerify(exactly = 0) {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `entity filter narrows entries to mentioning segments`() = runTest {
        val entityDao: com.noteflowai.app.data.memory.dao.EntityDao = mockk()
        val mentionDao: com.noteflowai.app.data.memory.dao.EntityMentionDao = mockk()
        coEvery { entityDao.getByNormalizedName("alice") } returns com.noteflowai.app.data.memory.model.Entity(
            id = "ent_alice",
            type = com.noteflowai.app.data.memory.model.EntityType.PERSON,
            canonicalName = "Alice",
            normalizedName = "alice"
        )
        coEvery { mentionDao.getByEntityId("ent_alice") } returns listOf(
            com.noteflowai.app.data.memory.model.EntityMention(
                entityId = "ent_alice",
                sourceSegmentId = "seg_mo_1",
                mentionText = "Alice",
                confidence = 0.9f
            )
        )
        coEvery { memoryObjectDao.getByDateRange(any(), any()) } returns listOf(
            memoryObject("mo_1"),
            memoryObject("mo_2").copy(sourceSegmentId = "seg_other")
        )
        coEvery { decisionDao.getTimeline() } returns emptyList()
        coEvery {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatMessage(role = "assistant", content = "{}")
        val entityService = ChangeAnalysisService(
            memoryObjectDao,
            decisionDao,
            aiChatRepository,
            settingsManager,
            entityDao,
            mentionDao
        )

        entityService.analyze(entity = "Alice")

        // Only seg_mo_1's entry reaches the prompt: verify via the user
        // message content carrying a single entry line.
        coVerify(exactly = 1) {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), match { msgs ->
                @Suppress("UNCHECKED_CAST")
                val content = (msgs as List<ChatMessage>).first().content
                content.contains("seg_mo_1") && !content.contains("seg_other")
            }, any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `project filter is forwarded to decision dao`() = runTest {
        coEvery { memoryObjectDao.getByDateRange(any(), any()) } returns emptyList()
        coEvery { memoryObjectDao.getByProject("proj_9") } returns emptyList()
        coEvery { decisionDao.getByProject("proj_9") } returns emptyList()
        coEvery {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatMessage(role = "assistant", content = "{}")

        val result = service.analyze(topic = "migration", project = "proj_9")

        assertTrue(result.timeline.isEmpty())
        coVerify(exactly = 1) { memoryObjectDao.getByProject("proj_9") }
        coVerify(exactly = 1) { decisionDao.getByProject("proj_9") }
    }

    @Test
    fun `local-only mode with remote endpoint skips ai without calling it`() = runTest {
        every { settingsManager.aiBaseUrl } returns flowOf("https://api.openai.com/")
        every { settingsManager.isLocalOnlyMode } returns flowOf(true)

        val result = service.analyze(topic = "migration")

        assertTrue(result.timeline.isEmpty())
        assertEquals(0f, result.confidence)
        coVerify(exactly = 0) {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `local-only mode with loopback endpoint still calls ai`() = runTest {
        every { settingsManager.isLocalOnlyMode } returns flowOf(true)
        coEvery { memoryObjectDao.getByDateRange(any(), any()) } returns listOf(
            memoryObject("mo_1", "we should migrate to postgres")
        )
        coEvery { decisionDao.getTimeline() } returns emptyList()
        coEvery {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatMessage(role = "assistant", content = "{}")

        service.analyze(topic = "migration")

        coVerify(exactly = 1) {
            aiChatRepository.getResponse(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }
}
