package com.noteflowai.app.data.memory.extraction

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shared handling for raw LLM output: JSON carving and lenient date parsing.
 *
 * Both [MemoryExtractionService] and ChangeAnalysisService used a greedy
 * `\{.*\}` fallback that grabbed the first `{` through the LAST `}` — any
 * trailing prose corrupted the parse and zeroed the extraction. The scanner
 * below returns the first balanced `{...}` object instead.
 */
object JsonExtraction {

    /**
     * Return the first balanced JSON object in [text], or null when none
     * exists. Markdown code fences are unwrapped first. String literals and
     * escapes are honored so braces inside quoted text do not affect depth.
     */
    fun extractJsonObject(text: String): String? {
        var candidate = text.trim()
        val fenced = Regex("```(?:json)?\\s*\\n?(.*?)\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
            .find(candidate)
        if (fenced != null) candidate = fenced.groupValues[1].trim()

        val start = candidate.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < candidate.length) {
            val c = candidate[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return candidate.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }
}

/**
 * Lenient date parsing for LLM-produced date strings. Accepts instants,
 * offset datetimes, offset-less local datetimes, plain ISO dates, epoch
 * millis/seconds strings, and common written forms ("March 15, 2026").
 * Returns null instead of throwing; callers decide whether a missing date
 * is an error or a fallback.
 */
object LenientDates {

    private val writtenForms = listOf(
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy/M/d", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("M/d/yyyy", Locale.ENGLISH)
    )

    fun parseToMillis(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        val text = dateStr.trim()
        // Epoch millis or seconds.
        if (text.matches(Regex("\\d{10,13}"))) {
            val digits = text.toLongOrNull() ?: return null
            return if (text.length <= 10) digits * 1000 else digits
        }
        try {
            return Instant.parse(text).toEpochMilli()
        } catch (_: Exception) { /* fall through */ }
        try {
            return java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: Exception) { /* fall through */ }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) { /* fall through */ }
        try {
            return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (_: Exception) { /* fall through */ }
        for (format in writtenForms) {
            try {
                return LocalDate.parse(text, format).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    fun isValid(dateStr: String?): Boolean = parseToMillis(dateStr) != null
}
