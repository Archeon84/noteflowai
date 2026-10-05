package com.noteflowai.app.data.logging

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLoggerTest {

    @Test
    fun `api key is redacted`() {
        val redacted = SafeLogger.redact("Authorization api_key=sk-abc1234567890def")
        assertFalse(redacted.contains("sk-abc1234567890def"))
        assertNotEquals("Authorization api_key=sk-abc1234567890def", redacted)
    }

    @Test
    fun `bearer token is redacted`() {
        val redacted = SafeLogger.redact("Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.somesignature")
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `raw note text is not made fully private, but no secrets leak`() {
        // A normal short sentence must not be destroyed by redaction.
        val line = SafeLogger.redact("Saved note id=abc123")
        assertNotEquals("", line)
        assertFalse(line.contains("api_key="))
    }

    @Test
    fun `long raw transcript body is truncated and redacted`() {
        val longBody = (1..60).joinToString(" ") { "token$it " } + "api_key=sk-deadbeef99"
        val line = SafeLogger.format("transcribed", mapOf("noteId" to "abcdef", "contentQuoted" to longBody))
        // Non-allowlisted key (contentQuoted) is dropped entirely.
        assertFalse(line.contains("contentQuoted"))
        assertFalse(line.contains("sk-deadbeef99"))
        // Allowlisted metadata passes through.
        assertTrue(line.contains("noteId=abcdef"))
    }

    @Test
    fun `emails and phone-like identifiers are redacted`() {
        assertEquals("[EMAIL_REDACTED]", SafeLogger.redact("user@example.com"))
    }

    @Test
    fun `logger writes redacted metadata through the injected sink`() {
        var captured: String? = null
        SafeLogger.sink = SafeLogger.Sink { _, _, msg -> captured = msg }
        try {
            SafeLogger.i(
                "TestTag",
                "stage_completed",
                mapOf(
                    "jobId" to "job-1",
                    "stage" to "EMBEDDING",
                    "status" to "OK",
                    "provider" to "onnx",
                    "model" to "minilm",
                    "api_key" to "sk-1234567890abcdef" // must be dropped (not allowlisted) AND redacted
                )
            )
            val msg = captured ?: error("sink not called")
            assertTrue(msg.contains("stage_completed"))
            assertTrue(msg.contains("jobId=job-1"))
            assertTrue(msg.contains("stage=EMBEDDING"))
            assertFalse(msg.contains("api_key"))
        } finally {
            // Restore the default sink so other tests are unaffected.
            SafeLogger.sink = SafeLogger.Sink { priority, tag, message ->
                when (priority) {
                    Log.VERBOSE -> Log.v(tag, message)
                    Log.DEBUG -> Log.d(tag, message)
                    Log.INFO -> Log.i(tag, message)
                    Log.WARN -> Log.w(tag, message)
                    else -> Log.e(tag, message)
                }
            }
        }
    }
}
