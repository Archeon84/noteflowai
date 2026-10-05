package com.noteflowai.app.data.llm

sealed class ModelId(val id: String) {
    data object Gemma4_E2B : ModelId("gemma4_e2b")
    data object Gemma4_E4B : ModelId("gemma4_e4b")
    data object Gemma2_2B : ModelId("gemma2_2b") // legacy alias
    data object Qwen35_2B : ModelId("qwen35_2b") // legacy alias
    data object Phi4Mini_3_8B : ModelId("phi4_mini_3.8b") // legacy alias

    companion object {
        fun fromId(id: String): ModelId = when (id) {
            Gemma4_E4B.id -> Gemma4_E4B
            Gemma4_E2B.id -> Gemma4_E2B
            else -> Gemma4_E2B
        }
    }
}

data class ModelInfo(
    val id: ModelId,
    val name: String,
    val displayName: String,
    val fileName: String,
    val hfRepo: String,
    val hfFile: String,
    val isOptional: Boolean,
    val approximateSizeMb: Int
) {
    val hfUrl: String
        get() = "https://huggingface.co/$hfRepo/resolve/main/$hfFile"
}

object AvailableModels {
    val GEMMA_4_E2B = ModelInfo(
        id = ModelId.Gemma4_E2B,
        name = "Gemma 4 E2B",
        displayName = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        hfRepo = "litert-community/gemma-4-E2B-it-litert-lm",
        hfFile = "gemma-4-E2B-it.litertlm",
        isOptional = false,
        approximateSizeMb = 1200
    )

    val GEMMA_4_E4B = ModelInfo(
        id = ModelId.Gemma4_E4B,
        name = "Gemma 4 E4B",
        displayName = "Gemma 4 E4B",
        fileName = "gemma-4-E4B-it.litertlm",
        hfRepo = "litert-community/gemma-4-E4B-it-litert-lm",
        hfFile = "gemma-4-E4B-it.litertlm",
        isOptional = true,
        approximateSizeMb = 3490
    )

    val ALL = listOf(GEMMA_4_E2B, GEMMA_4_E4B)

    fun get(modelId: ModelId): ModelInfo =
        ALL.firstOrNull { it.id == modelId } ?: GEMMA_4_E2B
}
