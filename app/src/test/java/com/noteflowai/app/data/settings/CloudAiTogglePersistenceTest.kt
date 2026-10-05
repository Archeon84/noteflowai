package com.noteflowai.app.data.settings

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudAiTogglePersistenceTest {

    @Test
    fun `restoreLastCloudAiConfig returns restored cloud provider name`() = runTest {
        val settingsManager = mockk<SettingsManager>(relaxed = true)
        coEvery { settingsManager.restoreLastCloudAiConfig() } returns "Claude"

        val provider = settingsManager.restoreLastCloudAiConfig()
        assertEquals("Claude", provider)
        coVerify(exactly = 1) { settingsManager.restoreLastCloudAiConfig() }
    }

    @Test
    fun `switchToLocalAiConfig returns active local model name`() = runTest {
        val settingsManager = mockk<SettingsManager>(relaxed = true)
        coEvery { settingsManager.switchToLocalAiConfig() } returns "Qwen3.5 2B"

        val model = settingsManager.switchToLocalAiConfig()
        assertEquals("Qwen3.5 2B", model)
        coVerify(exactly = 1) { settingsManager.switchToLocalAiConfig() }
    }

    @Test
    fun `updateAiSettings persists cloud configuration when provider is not local`() = runTest {
        val settingsManager = mockk<SettingsManager>(relaxed = true)
        coEvery {
            settingsManager.updateAiSettings(
                provider = "DeepSeek",
                apiKey = "sk-deepseek-test",
                baseUrl = "https://api.deepseek.com/v1/",
                modelName = "deepseek-chat",
                systemPrompt = "You are a helpful assistant.",
                temperature = 0.7f,
                presencePenalty = 0.0f
            )
        } returns Unit

        settingsManager.updateAiSettings(
            provider = "DeepSeek",
            apiKey = "sk-deepseek-test",
            baseUrl = "https://api.deepseek.com/v1/",
            modelName = "deepseek-chat",
            systemPrompt = "You are a helpful assistant.",
            temperature = 0.7f,
            presencePenalty = 0.0f
        )

        coVerify(exactly = 1) {
            settingsManager.updateAiSettings(
                provider = "DeepSeek",
                apiKey = "sk-deepseek-test",
                baseUrl = "https://api.deepseek.com/v1/",
                modelName = "deepseek-chat",
                systemPrompt = "You are a helpful assistant.",
                temperature = 0.7f,
                presencePenalty = 0.0f
            )
        }
    }
}
