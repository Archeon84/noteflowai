package com.noteflowai.app.data.llm

import android.app.ActivityManager
import android.content.Context

sealed class ModelId(val id: String) {
    data object Qwen25_05B : ModelId("qwen25_05b")
    data object Gemma4_E2B : ModelId("gemma4_e2b")
    data object Gemma4_E4B : ModelId("gemma4_e4b")
    data object Gemma2_2B : ModelId("gemma2_2b") // legacy alias
    data object Qwen35_2B : ModelId("qwen35_2b") // legacy alias
    data object Phi4Mini_3_8B : ModelId("phi4_mini_3.8b") // legacy alias

    companion object {
        fun fromId(id: String): ModelId = when (id) {
            Qwen25_05B.id -> Qwen25_05B
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
    val QWEN_2_5_0_5B = ModelInfo(
        id = ModelId.Qwen25_05B,
        name = "Qwen 2.5 0.5B",
        displayName = "Qwen 2.5 0.5B (Fast & Low-RAM)",
        fileName = "qwen-2.5-0.5B-it.litertlm",
        hfRepo = "litert-community/qwen-2.5-0.5B-it-litert-lm",
        hfFile = "qwen-2.5-0.5B-it.litertlm",
        isOptional = false,
        approximateSizeMb = 350
    )

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

    val ALL = listOf(QWEN_2_5_0_5B, GEMMA_4_E2B, GEMMA_4_E4B)

    fun get(modelId: ModelId): ModelInfo =
        ALL.firstOrNull { it.id == modelId } ?: GEMMA_4_E2B

    /**
     * Checks if the host device has less than 6 GB of physical RAM.
     * Android devices with 4 GB physical RAM typically report ~3.6-3.8 GB totalMem.
     * Android devices with 6 GB physical RAM typically report ~5.6-5.9 GB totalMem.
     * Threshold: 5,600 MB (5.6 GB).
     */
    fun isLowRamDevice(context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val thresholdBytes = 5600L * 1024L * 1024L
            memInfo.totalMem in 1 until thresholdBytes
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns the automatic default model for the given device context.
     * If physical RAM is < 6 GB, defaults to Qwen 2.5 0.5B (~350 MB).
     * If physical RAM is >= 6 GB, defaults to Gemma 4 E2B (~1.2 GB).
     */
    fun getDefault(context: Context): ModelInfo {
        return if (isLowRamDevice(context)) QWEN_2_5_0_5B else GEMMA_4_E2B
    }
}
