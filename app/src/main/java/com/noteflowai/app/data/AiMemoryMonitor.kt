package com.noteflowai.app.data

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * Monitors memory pressure and recommends model unloading when memory is low.
 * Priority order (highest to lowest): Whisper > Qwen3 > NLLB
 * Deepgram is cloud-only, never unloaded.
 */
class AiMemoryMonitor(context: Context) {

    companion object {
        private const val TAG = "AiMemoryMonitor"
        private const val LOW_MEMORY_THRESHOLD_MB = 200
        private const val CRITICAL_MEMORY_THRESHOLD_MB = 100
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    data class MemoryState(
        val availableMB: Long,
        val totalMB: Long,
        val isLow: Boolean,
        val isCritical: Boolean
    )

    fun getMemoryState(): MemoryState {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val availableMB = memInfo.availMem / (1024 * 1024)
        val totalMB = memInfo.totalMem / (1024 * 1024)

        return MemoryState(
            availableMB = availableMB,
            totalMB = totalMB,
            isLow = availableMB < LOW_MEMORY_THRESHOLD_MB,
            isCritical = availableMB < CRITICAL_MEMORY_THRESHOLD_MB
        )
    }

    /**
     * Returns the name of the lowest-priority model that should be unloaded,
     * or null if no unloading is needed.
     */
    fun getRecommendedUnload(
        isWhisperLoaded: Boolean,
        isQwen3Loaded: Boolean,
        isNllbLoaded: Boolean
    ): String? {
        val state = getMemoryState()

        if (!state.isLow) return null

        Log.w(TAG, "Memory pressure: ${state.availableMB}MB available (low=${state.isLow}, critical=${state.isCritical})")

        // Unload in reverse priority order: NLLB first, then Qwen3, then Whisper
        return when {
            state.isCritical && isNllbLoaded -> {
                Log.w(TAG, "Critical memory: recommending NLLB unload")
                "nllb"
            }
            state.isCritical && isQwen3Loaded -> {
                Log.w(TAG, "Critical memory: recommending Qwen3 unload")
                "qwen3"
            }
            state.isLow && isNllbLoaded -> {
                Log.w(TAG, "Low memory: recommending NLLB unload")
                "nllb"
            }
            state.isLow && isQwen3Loaded -> {
                Log.w(TAG, "Low memory: recommending Qwen3 unload")
                "qwen3"
            }
            else -> null
        }
    }

    fun logMemoryStatus() {
        val state = getMemoryState()
        Log.i(TAG, "Memory: ${state.availableMB}MB / ${state.totalMB}MB available (low=${state.isLow}, critical=${state.isCritical})")
    }
}
