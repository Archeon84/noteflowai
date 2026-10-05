package com.noteflowai.app.data.search

import com.noteflowai.app.data.concept.ConceptExtractor
import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.concept.ConceptNode
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MultiHopReasonerTest {

    @Test
    fun testMultiHopReasonerFindsConnections() {
        val conceptGraph = mockk<ConceptGraphRepository>(relaxed = true)
        val searchIndex = mockk<NoteSearchIndex>(relaxed = true)

        val startResult = NoteSearchIndex.SearchResult(
            fileName = "note_a.json",
            title = "Note A",
            score = 0.9f,
            excerpt = "Authentication details"
        )

        val concept = ConceptNode(
            canonicalForm = "oauth",
            displayForm = "OAuth 2.0",
            type = ConceptExtractor.ConceptType.ENTITY,
            noteCount = 3,
            importance = 0.8f,
            sampleNotes = listOf("note_a.json"),
            firstSeen = 0L,
            lastSeen = 0L
        )

        every { conceptGraph.getConcepts("note a") } returns listOf(concept)
        every { conceptGraph.getRelatedConcepts("oauth") } returns listOf("jwt" to 0.75f)
        every { conceptGraph.getNotesForConcept("oauth") } returns listOf("note_b.json")
        every { conceptGraph.getConcepts("note b") } returns listOf(
            ConceptNode("jwt", "JWT", ConceptExtractor.ConceptType.ENTITY, 2, 0.75f, listOf("note_b.json"), 0L, 0L)
        )

        val reasoner = MultiHopReasoner(conceptGraph, searchIndex)
        val result = reasoner.findConnections("auth query", listOf(startResult))

        assertNotNull(result)
        assertEquals(2, result?.hops?.size)
        assertEquals("note_a.json", result?.hops?.get(0)?.noteFileName)
        assertEquals("note_b.json", result?.hops?.get(1)?.noteFileName)
    }
}
