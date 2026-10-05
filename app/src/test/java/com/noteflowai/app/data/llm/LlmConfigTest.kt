package com.noteflowai.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmConfigTest {

    @Test
    fun `LlmConfig forModel sets accurate context windows`() {
        val gemma4Config = LlmConfig.forModel(ModelId.Gemma4_E2B)
        assertEquals(ModelId.Gemma4_E2B, gemma4Config.modelId)
        assertEquals(4096, gemma4Config.contextSize)
        assertEquals(2000, gemma4Config.ragMaxContextTokens)
        assertEquals(4, gemma4Config.nThreads)
        assertEquals(1280, gemma4Config.maxNewTokens)

        val gemma4E4bConfig = LlmConfig.forModel(ModelId.Gemma4_E4B)
        assertEquals(ModelId.Gemma4_E4B, gemma4E4bConfig.modelId)
        assertEquals(4096, gemma4E4bConfig.contextSize)
        assertEquals(2000, gemma4E4bConfig.ragMaxContextTokens)
        assertEquals(4, gemma4E4bConfig.nThreads)
        assertEquals(1280, gemma4E4bConfig.maxNewTokens)
    }

    @Test
    fun `AvailableModels contains Gemma4_E2B and Gemma4_E4B with valid HuggingFace URLs`() {
        assertEquals(2, AvailableModels.ALL.size)

        val gemma4 = AvailableModels.get(ModelId.Gemma4_E2B)
        assertEquals("Gemma 4 E2B", gemma4.name)
        assertFalse(gemma4.isOptional)
        assertEquals("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", gemma4.hfUrl)

        val gemma4E4b = AvailableModels.get(ModelId.Gemma4_E4B)
        assertEquals("Gemma 4 E4B", gemma4E4b.name)
        assertTrue(gemma4E4b.isOptional)
        assertEquals("https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm", gemma4E4b.hfUrl)
    }
}
