package com.noteflowai.app.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportModelsTest {

    @Test
    fun `import errors have exact guide-specified error codes`() {
        assertEquals("unsupported_format", ImportError.UnsupportedFormat.code)
        assertEquals("password_protected", ImportError.PasswordProtected.code)
        assertEquals("empty_content", ImportError.EmptyContent.code)
        assertEquals("transcript_unavailable", ImportError.TranscriptUnavailable.code)
        assertEquals("private_content", ImportError.PrivateContent.code)
        assertEquals("network_unavailable", ImportError.NetworkUnavailable.code)
        assertEquals("rate_limited", ImportError.RateLimited.code)
        assertEquals("provider_unavailable", ImportError.ProviderUnavailable.code)
        assertEquals("malformed_content", ImportError.MalformedContent.code)
    }

    @Test
    fun `import result success stores metadata and extracted text`() {
        val success = ImportResult.Success(
            text = "Extracted content from document",
            title = "Document.pdf",
            format = "PDF",
            wordCount = 4,
            charCount = 31
        )
        assertEquals("Document.pdf", success.title)
        assertEquals(4, success.wordCount)
        assertTrue(success.text.contains("Extracted content"))
    }

    @Test
    fun `import result failure stores typed error and fallback action`() {
        val failure = ImportResult.Failure(
            error = ImportError.TranscriptUnavailable,
            rawMessage = "No captions found for video",
            retryable = false,
            fallbackAction = FallbackAction.TRANSCRIBE_LOCALLY
        )
        assertEquals(ImportError.TranscriptUnavailable, failure.error)
        assertEquals(FallbackAction.TRANSCRIBE_LOCALLY, failure.fallbackAction)
    }
}
