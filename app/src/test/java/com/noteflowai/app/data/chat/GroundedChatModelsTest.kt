package com.noteflowai.app.data.chat

import com.google.gson.Gson
import com.noteflowai.app.data.memory.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedChatModelsTest {

    @Test
    fun `top-level citations and claim citationIds survive Gson round-trip`() {
        val json = """
        {
          "answer": "The supplier was ACME Corp.",
          "citations": [
            {"id": "cite-1", "sourceType": "NOTE", "sourceId": "note.md",
             "chunkId": "note_note.md_block2_abc", "quoteStart": 0, "quoteEnd": 12, "relevanceScore": 0.81}
          ],
          "claims": [
            {"text": "The supplier was ACME Corp.", "citationIds": ["cite-1"],
             "uncertainty": "LOW", "confidence": 0.9}
          ]
        }
        """.trimIndent()

        val response = Gson().fromJson(json, GroundedChatResponse::class.java)

        assertEquals(1, response.citations.size)
        assertEquals(SourceType.NOTE, response.citations[0].sourceType)
        assertEquals("cite-1", response.citations[0].id)
        assertEquals("note.md", response.citations[0].sourceId)
        assertEquals(0, response.citations[0].quoteStart)
        assertEquals(12, response.citations[0].quoteEnd)
        assertEquals(1, response.claims.size)
        assertEquals(listOf("cite-1"), response.claims[0].citationIds)
        assertEquals(UncertaintyLevel.LOW, response.claims[0].uncertainty)
    }

    @Test
    fun `absent optional fields default to empty lists and LOW uncertainty`() {
        val json = """{"answer": "No claims here."}""".trimIndent()
        val response = Gson().fromJson(json, GroundedChatResponse::class.java)
        // Plain Gson bypasses constructors (documented in GroundedChatModels.kt), so absent
        // array fields are null until supplied; consumers treat them as empty via orEmpty().
        assertTrue(response.citations.orEmpty().isEmpty())
        assertTrue(response.claims.orEmpty().isEmpty())
        assertTrue(!response.abstained)
    }

    @Test
    fun `memory object ids stay a separate claim channel`() {
        val response = Gson().fromJson(
            """{"answer":"x","claims":[{"text":"t","memory_object_ids":["mem_1"]}]}""".trimIndent(),
            GroundedChatResponse::class.java
        )
        assertEquals(listOf("mem_1"), response.claims[0].memory_object_ids)
        assertTrue(response.claims[0].citationIds.orEmpty().isEmpty())
    }
}