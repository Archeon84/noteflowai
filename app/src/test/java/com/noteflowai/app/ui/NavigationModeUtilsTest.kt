package com.noteflowai.app.ui

import com.noteflowai.app.ui.theme.NavigationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationModeUtilsTest {

    @Test
    fun testGesturalModeProperties() {
        val mode = NavigationMode.GESTURAL
        assertTrue("GESTURAL should report isGestural = true", mode.isGestural)
        assertFalse("GESTURAL should report isButtonNav = false", mode.isButtonNav)
        assertEquals("Gesture Navigation", mode.displayName)
    }

    @Test
    fun testThreeButtonModeProperties() {
        val mode = NavigationMode.THREE_BUTTON
        assertFalse("THREE_BUTTON should report isGestural = false", mode.isGestural)
        assertTrue("THREE_BUTTON should report isButtonNav = true", mode.isButtonNav)
        assertEquals("3-Button Navigation", mode.displayName)
    }

    @Test
    fun testTwoButtonModeProperties() {
        val mode = NavigationMode.TWO_BUTTON
        assertFalse("TWO_BUTTON should report isGestural = false", mode.isGestural)
        assertTrue("TWO_BUTTON should report isButtonNav = true", mode.isButtonNav)
        assertEquals("2-Button Navigation", mode.displayName)
    }
}
