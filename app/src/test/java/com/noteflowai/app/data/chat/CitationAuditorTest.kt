package com.noteflowai.app.data.chat

import com.noteflowai.app.data.memory.dao.AnswerCitationDao
import com.noteflowai.app.data.memory.model.AnswerCitation
import com.noteflowai.app.data.memory.model.SourceType
import com.noteflowai.app.data.search.OnDeviceEmbedder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CitationAuditorTest {

    private lateinit var citationDao: AnswerCitationDao
    private lateinit var embedder: OnDeviceEmbedder
    private lateinit var auditor: CitationAuditor

    @Before
    fun setUp() {
        citationDao = mockk(relaxed = true)
        embedder = mockk(relaxed = true)
        coEvery { embedder.isReady() } returns false
        auditor = CitationAuditor(citationDao, embedder)
    }

    @Test
    fun `auditAndPersistRagResponse validates supported citation tags`() = runTest {
        val ragSources = listOf(
            RagSource(
                noteFileName = "meeting_notes.md",
                noteTitle = "Project Meeting",
                relevanceScore = 0.95f,
                excerpt = "The quarterly team sprint begins next Monday at 9am in room 402.",
                source = "bm25"
            ),
            RagSource(
                noteFileName = "budget.md",
                noteTitle = "Annual Budget",
                relevanceScore = 0.85f,
                excerpt = "The total server budget allocated for this year is 50,000 USD.",
                source = "bm25"
            )
        )

        val responseText = "The quarterly sprint begins next Monday [1]. We have a total server budget of 50,000 USD [2]."

        val citationsSlot = slot<List<AnswerCitation>>()
        coEvery { citationDao.insertAll(capture(citationsSlot)) } returns Unit

        val result = auditor.auditAndPersistRagResponse("ans-123", responseText, ragSources)

        assertEquals(2, result.size)
        assertEquals("VALIDATED", result[0].supportStatus)
        assertEquals("meeting_notes.md", result[0].sourceSegmentId)
        assertEquals(1, result[0].claimIndex)

        assertEquals("VALIDATED", result[1].supportStatus)
        assertEquals("budget.md", result[1].sourceSegmentId)
        assertEquals(2, result[1].claimIndex)

        coVerify(exactly = 1) { citationDao.insertAll(any()) }
    }

    @Test
    fun `auditAndPersistRagResponse flags out of bounds citations as INVALID`() = runTest {
        val ragSources = listOf(
            RagSource(
                noteFileName = "note1.md",
                noteTitle = "Title",
                relevanceScore = 0.9f,
                excerpt = "Apples are red fruits.",
                source = "bm25"
            )
        )

        val responseText = "Apples are red [1], but spaceships travel to Mars [99]."

        val result = auditor.auditAndPersistRagResponse("ans-123", responseText, ragSources)

        assertEquals(2, result.size)
        assertEquals("VALIDATED", result[0].supportStatus)
        assertEquals("INVALID", result[1].supportStatus)
        assertTrue(result[1].sourceSegmentId.startsWith("unknown_source"))
    }

    @Test
    fun `auditAndPersistRagResponse flags unsupported claims as INVALID`() = runTest {
        val ragSources = listOf(
            RagSource(
                noteFileName = "note1.md",
                noteTitle = "Groceries",
                relevanceScore = 0.9f,
                excerpt = "Buy milk, eggs, and bread from the grocery store.",
                source = "bm25"
            )
        )

        val responseText = "The quantum computer reached absolute zero temperatures [1]."

        val result = auditor.auditAndPersistRagResponse("ans-123", responseText, ragSources)

        assertEquals(1, result.size)
        assertEquals("INVALID", result[0].supportStatus)
    }

    @Test
    fun `auditAndPersistRagResponse deduplicates multiple mentions of the same citation tag`() = runTest {
        val ragSources = listOf(
            RagSource(
                noteFileName = "rag_guide.md",
                noteTitle = "RAG Guide",
                relevanceScore = 0.95f,
                excerpt = "Better document processing improves retrieval quality by removing duplicate documents.",
                source = "bm25"
            ),
            RagSource(
                noteFileName = "agent_system.md",
                noteTitle = "AI Agent System",
                relevanceScore = 0.85f,
                excerpt = "Hybrid RAG systems incorporate memory layers within agent architectures.",
                source = "bm25"
            )
        )

        val responseText = """
            - Hybrid RAG systems incorporate memory layers [2].
            - Better document processing is required [1].
            - Before retrieval, source data quality should be improved [1].
            - This involves removing duplicate documents [1].
        """.trimIndent()

        val result = auditor.auditAndPersistRagResponse("ans-dedup-1", responseText, ragSources)

        assertEquals(2, result.size)
        assertEquals(1, result[0].claimIndex)
        assertEquals("rag_guide.md", result[0].sourceSegmentId)
        assertEquals("RAG Guide", result[0].locationType)
        assertEquals("VALIDATED", result[0].supportStatus)

        assertEquals(2, result[1].claimIndex)
        assertEquals("agent_system.md", result[1].sourceSegmentId)
        assertEquals("AI Agent System", result[1].locationType)
        assertEquals("VALIDATED", result[1].supportStatus)
    }

    @Test
    fun `auditAndPersistGroundedResponse maps ValidatedResponse to AnswerCitations`() = runTest {
        val response = GroundedChatResponse(
            answer = "The supplier is ACME Corp.",
            citations = listOf(
                Citation(
                    id = "c1",
                    sourceType = SourceType.NOTE,
                    sourceId = "vendor.md",
                    chunkId = "chunk_101"
                ),
                Citation(
                    id = "c2",
                    sourceType = SourceType.NOTE,
                    sourceId = "vendor.md",
                    chunkId = "chunk_102"
                )
            ),
            claims = listOf(
                Claim(
                    text = "The supplier is ACME Corp.",
                    citationIds = listOf("c1")
                )
            )
        )

        val validatedClaims = listOf(
            ValidatedClaim(
                claim = response.claims[0],
                isValid = true,
                validCitationIds = listOf("c1"),
                invalidCitationIds = emptyList(),
                hasValidQuoteRange = true
            )
        )

        val validatedResponse = ValidatedResponse(
            response = response,
            validatedClaims = validatedClaims,
            totalClaims = 1,
            validClaims = 1,
            unsupportedClaims = 0
        )

        val result = auditor.auditAndPersistGroundedResponse("ans-grounded-1", validatedResponse)

        assertEquals(2, result.size)
        val c1 = result.find { it.sourceSegmentId == "chunk_101" }!!
        val c2 = result.find { it.sourceSegmentId == "chunk_102" }!!

        assertEquals("VALIDATED", c1.supportStatus)
        assertEquals("VALIDATED", c2.supportStatus) // fallback since validClaims > 0
        coVerify(exactly = 1) { citationDao.insertAll(any()) }
    }
}
