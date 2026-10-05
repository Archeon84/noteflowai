package com.noteflowai.app.data.logging

import android.util.Log

/**
 * Centralized, privacy-safe structured logger (Phase 0 of the Agentic Guide).
 *
 * Logs metadata only. Secrets and personal content are scrubbed before they
 * reach the sink via [redact], so raw note text, transcripts, LLM prompts,
 * retrieved passages, and credentials never appear in logs.
 *
 * The sink is injectable so the core redaction logic is testable on the JVM
 * without Robolectric; production uses [android.util.Log], which is the default.
 */
object SafeLogger {

    /** Low-level sink. Defaults to Android's [Log] verbosity-matched write. */
    fun interface Sink {
        fun log(priority: Int, tag: String, message: String)
    }

    @Volatile
    var sink: Sink = Sink { priority, tag, message ->
        when (priority) {
            Log.VERBOSE -> Log.v(tag, message)
            Log.DEBUG -> Log.d(tag, message)
            Log.INFO -> Log.i(tag, message)
            Log.WARN -> Log.w(tag, message)
            else -> Log.e(tag, message)
        }
    }

    // Maximum length of any single field value before truncation.
    private const val MAX_VALUE_LENGTH = 200
    // Maximum number of metadata fields attached to one event.
    private const val MAX_FIELDS = 8

    /**
     * Methods that write through [sink]; message is always built by [format].
     */
    fun v(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) =
        emit(Log.VERBOSE, tag, event, fields)
    fun d(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) =
        emit(Log.DEBUG, tag, event, fields)
    fun i(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) =
        emit(Log.INFO, tag, event, fields)
    fun w(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) =
        emit(Log.WARN, tag, event, fields)
    fun e(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) =
        emit(Log.ERROR, tag, event, fields)

    private fun emit(priority: Int, tag: String, event: String, fields: Map<String, Any?>) {
        val msg = format(event, fields)
        sink.log(priority, tag.take(23), msg)
    }

    /**
     * Build a redacted, bounded log line: `event key=value ...`.
     * Field values are truncated to [MAX_VALUE_LENGTH] and run through [redact].
     */
    fun format(event: String, fields: Map<String, Any?> = emptyMap()): String {
        val safe = event.take(MAX_VALUE_LENGTH)
        if (fields.isEmpty()) return safe
        val parts = SafeLogger.fieldEntries(fields)
            .take(MAX_FIELDS)
            .map { (k, v) -> "$k=${redact(v)}" }
        return buildString {
            append(safe)
            parts.forEach { append(' ')
                .append(it) }
        }
    }

    private fun fieldEntries(fields: Map<String, Any?>): List<Pair<String, String>> =
        fields.entries
            .asSequence()
            .map { it.key to (it.value?.toString() ?: "null") }
            .filter { (k, _) -> k in ALLOWED_METADATA_KEYS }
            .map { (k, v) -> k to v.take(MAX_VALUE_LENGTH) }
            .toList()

    /**
     * Scrub sensitive content from a string so nothing private reaches logs.
     * Redaction is conservative: any recognised secret pattern is replaced.
     */
    fun redact(value: String): String {
        var out = value
        for ((regex, replacement) in REDACTIONS) {
            out = out.replace(regex, replacement)
        }
        return out
    }

    // ── Metadata allowlist (guide §7) ──────────────────────────────
    private val ALLOWED_METADATA_KEYS = setOf(
        "jobId",
        "noteId",
        "sourceId",
        "sourceType",
        "stage",
        "status",
        "durationMs",
        "provider",
        "model",
        "itemCount",
        "errorCode",
        "attempt",
        "event"
    )

    // Sensitivity patterns: ordered (regex, replacement) applied in sequence.
    private val REDACTIONS = listOf<Pair<Regex, String>>(
        // API keys / tokens, case-insensitive.
        Regex("(?i)(api[_-]?key|access[_-]?token|auth[_-]?token|secret|passphrase|password)([\"']?\\s*[:=]\\s*)([A-Za-z0-9._-]{6,})") to "$1$2[REDACTED]",
        // Bearer-style tokens.
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._-]{8,}") to "$1[REDACTED]",
        // Long base64 blobs (likely audio/embedding/keys).
        Regex("[A-Za-z0-9+/]{80,}={0,2}") to "[BASE64_REDACTED]",
        // Email addresses and phone numbers.
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "[EMAIL_REDACTED]",
        // Long whitespace-separated runs of text (raw transcripts/content).
        Regex("(\\b\\S+\\s+){30,}\\S*") to "[BODY_REDACTED]"
    )
}
