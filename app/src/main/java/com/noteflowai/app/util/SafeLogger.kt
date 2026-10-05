package com.noteflowai.app.util

import android.util.Log

/**
 * Privacy-preserving logging utility that automatically redacts API keys,
 * bearer tokens, credentials, and passwords before logging to logcat.
 */
object SafeLogger {

    private val OPENAI_KEY_PATTERN = Regex("""sk-[A-Za-z0-9-_]{20,}""")
    private val BEARER_TOKEN_PATTERN = Regex("""Bearer\s+[A-Za-z0-9-_.]+(?=[^A-Za-z0-9-_.]|$)""")
    private val HEX_KEY_PATTERN = Regex("""(?<=\s|^)[a-f0-9]{40}(?=\s|$)""")
    private val PASSWORD_JSON_PATTERN = Regex("""("password"|"passphrase")\s*:\s*"[^"]+"""")

    fun redact(message: String?): String {
        if (message.isNullOrEmpty()) return ""
        var sanitized = message
        sanitized = OPENAI_KEY_PATTERN.replace(sanitized, "[REDACTED_API_KEY]")
        sanitized = BEARER_TOKEN_PATTERN.replace(sanitized, "Bearer [REDACTED_TOKEN]")
        sanitized = HEX_KEY_PATTERN.replace(sanitized, "[REDACTED_API_KEY]")
        sanitized = PASSWORD_JSON_PATTERN.replace(sanitized) { matchResult ->
            val key = matchResult.groupValues[1]
            """$key:"[REDACTED_SECRET]""""
        }
        return sanitized
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, redact(msg))
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, redact(msg))
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.w(tag, redact(msg), tr)
        } else {
            Log.w(tag, redact(msg))
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.e(tag, redact(msg), tr)
        } else {
            Log.e(tag, redact(msg))
        }
    }
}
