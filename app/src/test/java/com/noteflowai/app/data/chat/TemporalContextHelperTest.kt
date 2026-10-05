package com.noteflowai.app.data.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TemporalContextHelperTest {

    @Test
    fun `getSystemTemporalContext includes full date, iso date, and instructions`() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.AUGUST)
            set(Calendar.DAY_OF_MONTH, 30)
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
        }
        val context = TemporalContextHelper.getSystemTemporalContext(calendar.time)

        assertTrue(context.contains("[TEMPORAL CONTEXT]"))
        assertTrue(context.contains("2026-08-30"))
        assertTrue(context.contains("Current Reference Date & Time:"))
        assertTrue(context.contains("relative date queries"))
        assertTrue(context.contains("timestamps indicate when information was recorded"))
    }
}
