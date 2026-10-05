package com.noteflowai.app.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedResponseParserTest {

    @Test
    fun `well-formed envelope parses to Success`() {
        val result = GroundedResponseParser.parse(
            """{"answer":"ok","citations":[{"id":"cite-1","sourceType":"NOTE","sourceId":"n.md","chunkId":"c1"}]}"""
        )
        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        assertEquals("ok", success.response.answer)
        assertEquals("cite-1", success.response.citations[0].id)
    }

    @Test
    fun `malformed json produces Failure not a crash`() {
        val result = GroundedResponseParser.parse("{ not json !!")
        assertTrue(result is ParseResult.Failure)
    }

    @Test
    fun `blank answer produces Failure`() {
        assertTrue(GroundedResponseParser.parse("{}") is ParseResult.Failure)
        assertTrue(GroundedResponseParser.parse("") is ParseResult.Failure)
    }

    @Test
    fun `absent answer produces Failure`() {
        assertTrue(GroundedResponseParser.parse("""{"claims":[]}""".trimIndent()) is ParseResult.Failure)
    }

    @Test
    fun `unknown sourceType string does not crash, defaults to NOTE`() {
        val result = GroundedResponseParser.parse("""{"answer":"a","citations":[{"id":"c","sourceType":"BOGUS","sourceId":"s","chunkId":"k"}]}""")
        assertTrue(result is ParseResult.Success)
        assertEquals(com.noteflowai.app.data.memory.model.SourceType.NOTE, (result as ParseResult.Success).response.citations[0].sourceType)
    }

    @Test
    fun `nil list fields normalize to empty at parser boundary`() {
        val result = GroundedResponseParser.parse("""{"answer":"ok"}""")
        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        assertEquals(0, success.response.citations.size)
        assertEquals(0, success.response.claims.size)
        assertEquals(0, success.response.suggested_actions.size)
    }

    @Test
    fun `absent answer returns the intended failure message`() {
        val result = GroundedResponseParser.parse("""{"claims":[]}""")
        assertTrue(result is ParseResult.Failure)
        assertEquals("Answer field missing or blank", (result as ParseResult.Failure).reason)
    }

    @Test
    fun `claim with text and uncertainty normalizes inner lists to empty`() {
        val result = GroundedResponseParser.parse(
            """{"answer":"a","claims":[{"text":"a claim","uncertainty":"LOW"}]}"""
        )
        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        assertEquals(1, success.response.claims.size)
        assertEquals(0, success.response.claims[0].citationIds.size)
        assertEquals(0, success.response.claims[0].memory_object_ids.size)
        assertEquals(com.noteflowai.app.data.chat.UncertaintyLevel.LOW, success.response.claims[0].uncertainty)
    }

    @Test
    fun `claim missing uncertainty degrades gracefully and still returns Success`() {
        val result = GroundedResponseParser.parse(
            """{"answer":"a","claims":[{"text":"a claim"}]}"""
        )
        assertTrue(result is ParseResult.Success)
        val success = result as ParseResult.Success
        assertEquals(1, success.response.claims.size)
        assertEquals("a claim", success.response.claims[0].text)
        assertEquals(0, success.response.claims[0].citationIds.size)
        assertEquals(0, success.response.claims[0].memory_object_ids.size)
    }
}