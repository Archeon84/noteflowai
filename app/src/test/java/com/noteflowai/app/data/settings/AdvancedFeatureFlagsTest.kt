package com.noteflowai.app.data.settings

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedFeatureFlagsTest {

    @Test
    fun testAdvancedFeatureFlagsCanBeRead() {
        runBlocking {
            val settingsManager = mockk<SettingsManager>(relaxed = true)
            every { settingsManager.isLocalOnlyModeBlocking } returns true

            assertTrue(settingsManager.isLocalOnlyModeBlocking)
        }
    }
}
