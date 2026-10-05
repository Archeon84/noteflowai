package com.noteflowai.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SafeLoggerTest {

    @Test
    fun `redact removes openai api keys`() {
        val input = "Request to OpenAI with key sk-abcdef1234567890abcdef123456 failed"
        val result = SafeLogger.redact(input)
        assertFalse(result.contains("sk-abcdef1234567890abcdef123456"))
        assertEquals("Request to OpenAI with key [REDACTED_API_KEY] failed", result)
    }

    @Test
    fun `redact removes bearer tokens`() {
        val input = "Header Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLeakThis"
        val result = SafeLogger.redact(input)
        assertFalse(result.contains("doNotLeakThis"))
        assertEquals("Header Authorization: Bearer [REDACTED_TOKEN]", result)
    }

    @Test
    fun `redact removes deepgram 40-char hex keys`() {
        val input = "Deepgram client initialized with key 1234567890abcdef1234567890abcdef12345678"
        val result = SafeLogger.redact(input)
        assertFalse(result.contains("1234567890abcdef1234567890abcdef12345678"))
        assertEquals("Deepgram client initialized with key [REDACTED_API_KEY]", result)
    }

    @Test
    fun `redact removes password and passphrase json fields`() {
        val input = """{"username":"user1", "password":"superSecretPassword123", "passphrase":"dbPassphrase456"}"""
        val result = SafeLogger.redact(input)
        assertFalse(result.contains("superSecretPassword123"))
        assertFalse(result.contains("dbPassphrase456"))
        assertEquals("""{"username":"user1", "password":"[REDACTED_SECRET]", "passphrase":"[REDACTED_SECRET]"}""", result)
    }

    @Test
    fun `redact leaves non-sensitive messages untouched`() {
        val input = "Retrieved 15 notes for query 'project meeting'"
        val result = SafeLogger.redact(input)
        assertEquals(input, result)
    }
}
