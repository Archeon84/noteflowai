package com.noteflowai.app.data.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Provides live temporal reference anchors for system prompts across both online and offline LLMs.
 */
object TemporalContextHelper {

    /**
     * Generate a structured temporal context block with the current date, time, day of week, and timezone.
     */
    fun getSystemTemporalContext(referenceDate: Date = Date()): String {
        val fullFormat = SimpleDateFormat("EEEE, MMMM dd, yyyy HH:mm:ss (z)", Locale.getDefault())
        val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return """
[TEMPORAL CONTEXT]
Current Reference Date & Time: ${fullFormat.format(referenceDate)}
Current Date (ISO): ${isoFormat.format(referenceDate)}
Use this temporal reference to accurately resolve relative date queries (e.g. 'today', 'yesterday', 'this week', 'last month', 'next Tuesday'). Note creation and modification timestamps indicate when information was recorded.
""".trimIndent()
    }
}
