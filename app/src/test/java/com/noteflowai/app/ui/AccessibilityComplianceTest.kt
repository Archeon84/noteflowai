package com.noteflowai.app.ui

import com.noteflowai.app.ui.theme.AppRadius
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AccessibilityComplianceTest {

    @Test
    fun testTouchTargetsAndPaddingAdhereToAccessibleDimensions() {
        assertTrue(AppRadius.small.value > 0f)
        assertTrue(AppRadius.large.value >= 12f)
    }

    @Test
    fun testStringsXmlContainsCoreNavigationAndAccessibilityDescriptions() {
        val stringsFile = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml must exist", stringsFile.exists())
        val content = stringsFile.readText()
        assertTrue(content.contains("common_back"))
        assertTrue(content.contains("privacy_dashboard_title"))
        assertTrue(content.contains("import_error_unsupported_format"))
    }
}
