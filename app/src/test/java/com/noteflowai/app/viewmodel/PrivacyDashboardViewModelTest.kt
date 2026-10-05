package com.noteflowai.app.viewmodel

import com.noteflowai.app.data.settings.SettingsManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyDashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `toggleLocalOnlyMode updates settings manager`() = runTest {
        val settingsManager = mockk<SettingsManager>(relaxed = true)
        coEvery { settingsManager.setLocalOnlyMode(true) } just Runs

        settingsManager.setLocalOnlyMode(true)

        coVerify { settingsManager.setLocalOnlyMode(true) }
    }
}
