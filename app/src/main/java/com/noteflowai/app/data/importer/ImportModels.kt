package com.noteflowai.app.data.importer

import android.net.Uri
import java.io.File

data class ImportInput(
    val uri: Uri? = null,
    val file: File? = null,
    val url: String? = null,
    val mimeType: String? = null,
    val password: String? = null,
    val title: String? = null
)

data class ImportInspection(
    val isSupported: Boolean,
    val isPasswordProtected: Boolean = false,
    val formatLabel: String,
    val estimatedBytes: Long = 0L,
    val pageCount: Int? = null,
    val durationMs: Long? = null,
    val requiresNetwork: Boolean = false
)

enum class FallbackAction {
    RUN_OCR,
    IMPORT_AUDIO,
    TRANSCRIBE_LOCALLY,
    RETRY_LATER,
    CHOOSE_ANOTHER_FILE,
    ENTER_PASSWORD
}

sealed class ImportError(val code: String) {
    data object UnsupportedFormat : ImportError("unsupported_format")
    data object PasswordProtected : ImportError("password_protected")
    data object EmptyContent : ImportError("empty_content")
    data object TranscriptUnavailable : ImportError("transcript_unavailable")
    data object PrivateContent : ImportError("private_content")
    data object NetworkUnavailable : ImportError("network_unavailable")
    data object RateLimited : ImportError("rate_limited")
    data object ProviderUnavailable : ImportError("provider_unavailable")
    data object MalformedContent : ImportError("malformed_content")
}

sealed class ImportResult {
    data class Success(
        val text: String,
        val title: String,
        val format: String,
        val wordCount: Int,
        val charCount: Int,
        val sourceSegmentIds: List<String> = emptyList(),
        val rawCaptureId: String? = null
    ) : ImportResult()

    data class Failure(
        val error: ImportError,
        val rawMessage: String,
        val retryable: Boolean = false,
        val fallbackAction: FallbackAction? = null
    ) : ImportResult()
}
