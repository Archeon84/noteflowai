package com.noteflowai.app

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityDeepLinkTest {

    private fun parse(uriString: String): String? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
        val activity = MainActivity()
        val method = MainActivity::class.java.getDeclaredMethod("parseDeepLink", Intent::class.java)
        method.isAccessible = true
        return method.invoke(activity, intent) as? String
    }

    @Test
    fun parseDeepLink_resolvesAllStandardScreens() {
        assertEquals("HOME", parse("noteflowai://screen/home"))
        assertEquals("RECORD", parse("noteflowai://screen/record"))
        assertEquals("NOTES", parse("noteflowai://screen/notes"))
        assertEquals("CHAT", parse("noteflowai://screen/chat"))
        assertEquals("SETTINGS", parse("noteflowai://screen/settings"))
        assertEquals("DIGEST", parse("noteflowai://screen/digest"))
        assertEquals("MEMORY", parse("noteflowai://screen/memory"))
        assertEquals("GRAPH", parse("noteflowai://screen/graph"))
        assertEquals("CONFLICT_DETECTION", parse("noteflowai://screen/conflict"))
        assertEquals("WEEKLY_REVIEW", parse("noteflowai://screen/review"))
    }

    @Test
    fun parseDeepLink_rejectsInvalidSchemeOrHost() {
        assertNull(parse("https://screen/home"))
        assertNull(parse("noteflowai://other/home"))
        assertNull(parse("noteflowai://screen/unknown_screen_xyz"))
    }
}
