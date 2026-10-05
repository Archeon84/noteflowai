package com.noteflowai.app.data.importer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioImporter(private val context: Context) : ContentImporter {

    override suspend fun inspect(input: ImportInput): ImportInspection = withContext(Dispatchers.IO) {
        ImportInspection(
            isSupported = true,
            formatLabel = "Audio",
            estimatedBytes = input.file?.length() ?: 0L
        )
    }

    override suspend fun import(input: ImportInput): ImportResult = withContext(Dispatchers.IO) {
        val file = input.file
        if (file == null || !file.exists()) {
            return@withContext ImportResult.Failure(ImportError.MalformedContent, "Audio file not found")
        }

        ImportResult.Success(
            text = "Audio file ready for transcription: ${file.name}",
            title = input.title ?: file.name,
            format = "Audio",
            wordCount = 0,
            charCount = 0
        )
    }
}
