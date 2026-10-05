package com.noteflowai.app.data.llm

data class LlmConfig(
    val modelId: ModelId,
    val contextSize: Int = 4096, // Clamped to 4096 tokens to bound KV-cache at ~120MB on mass-market devices
    val ragMaxContextTokens: Int = 2000, // Leaves ~816 tokens for prompt directives & ~1280 tokens for output
    val nThreads: Int = 4,
    val maxNewTokens: Int = 1280,
) {
    companion object {
        fun forModel(modelId: ModelId): LlmConfig = LlmConfig(
            modelId = modelId,
            contextSize = 4096,
            ragMaxContextTokens = 2000,
            nThreads = 4,
            maxNewTokens = 1280,
        )
    }
}
