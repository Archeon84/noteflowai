package com.noteflowai.app.util

import android.content.Context
import java.io.File

/**
 * Utility for calculating and clearing cached audio, temporary files, and TTS artifacts.
 */
object PrivacyCacheUtils {

    fun getTtsCacheSize(context: Context): Long {
        var total = 0L
        val ttsCacheDir = File(context.cacheDir, "tts_cache")
        if (ttsCacheDir.exists()) {
            ttsCacheDir.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
        }
        val ttsFilesDir = File(context.filesDir, "tts")
        if (ttsFilesDir.exists()) {
            ttsFilesDir.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
        }
        return total
    }

    fun clearTtsCache(context: Context): Long {
        var deletedBytes = 0L
        val ttsCacheDir = File(context.cacheDir, "tts_cache")
        if (ttsCacheDir.exists()) {
            ttsCacheDir.walkBottomUp().forEach {
                if (it.isFile) deletedBytes += it.length()
                it.delete()
            }
        }
        val ttsFilesDir = File(context.filesDir, "tts")
        if (ttsFilesDir.exists()) {
            ttsFilesDir.walkBottomUp().forEach {
                if (it.isFile) deletedBytes += it.length()
                it.delete()
            }
        }
        return deletedBytes
    }

    fun getTempFilesSize(context: Context): Long {
        var total = 0L
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("temp_") || file.name.endsWith(".tmp") || file.name.endsWith(".wav") || file.name.endsWith(".zip")) {
                if (file.isFile) total += file.length()
                else if (file.isDirectory) {
                    file.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
                }
            }
        }
        return total
    }

    fun clearTempFiles(context: Context): Long {
        var deletedBytes = 0L
        context.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("temp_") || file.name.endsWith(".tmp") || file.name.endsWith(".wav") || file.name.endsWith(".zip")) {
                if (file.isFile) {
                    deletedBytes += file.length()
                    file.delete()
                } else if (file.isDirectory) {
                    file.walkBottomUp().forEach {
                        if (it.isFile) deletedBytes += it.length()
                        it.delete()
                    }
                }
            }
        }
        return deletedBytes
    }
}
