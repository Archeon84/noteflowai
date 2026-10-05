package com.noteflowai.app.data.importer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class TextImporter(private val context: Context? = null) : ContentImporter {

    override suspend fun inspect(input: ImportInput): ImportInspection = withContext(Dispatchers.IO) {
        val size = input.file?.length() ?: 0L
        ImportInspection(
            isSupported = true,
            isPasswordProtected = false,
            formatLabel = "TXT",
            estimatedBytes = size
        )
    }

    override suspend fun import(input: ImportInput): ImportResult = withContext(Dispatchers.IO) {
        try {
            val text = when {
                input.file != null -> input.file.readText(Charsets.UTF_8)
                input.uri != null && context != null -> {
                    context.contentResolver.openInputStream(input.uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                    } ?: return@withContext ImportResult.Failure(ImportError.MalformedContent, "Cannot open file input stream")
                }
                else -> return@withContext ImportResult.Failure(ImportError.MalformedContent, "No file or URI provided")
            }

            if (text.isBlank()) {
                return@withContext ImportResult.Failure(
                    error = ImportError.EmptyContent,
                    rawMessage = "File contains no readable text",
                    fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
                )
            }

            val title = input.title ?: input.file?.name ?: "Text Document"
            val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
            ImportResult.Success(
                text = text.trim(),
                title = title,
                format = "TXT",
                wordCount = wordCount,
                charCount = text.length
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                error = ImportError.MalformedContent,
                rawMessage = e.message ?: "Failed to read text file",
                fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
            )
        }
    }
}
