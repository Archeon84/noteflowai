package com.noteflowai.app.data.importer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class HtmlImporter(private val context: Context? = null) : ContentImporter {

    override suspend fun inspect(input: ImportInput): ImportInspection = withContext(Dispatchers.IO) {
        val size = input.file?.length() ?: 0L
        ImportInspection(
            isSupported = true,
            isPasswordProtected = false,
            formatLabel = "HTML",
            estimatedBytes = size
        )
    }

    override suspend fun import(input: ImportInput): ImportResult = withContext(Dispatchers.IO) {
        try {
            val html = when {
                input.file != null -> input.file.readText()
                input.uri != null && context != null -> {
                    context.contentResolver.openInputStream(input.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: return@withContext ImportResult.Failure(ImportError.MalformedContent, "Cannot open HTML stream")
                }
                else -> return@withContext ImportResult.Failure(ImportError.MalformedContent, "No HTML file or URI provided")
            }

            val doc = Jsoup.parse(html)
            doc.select("script, style, nav, footer, header, noscript").remove()
            val text = doc.body()?.text() ?: ""

            if (text.isBlank()) {
                return@withContext ImportResult.Failure(
                    error = ImportError.EmptyContent,
                    rawMessage = "HTML document contains no readable text content",
                    fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
                )
            }

            val title = input.title ?: doc.title().ifBlank { input.file?.name ?: "HTML Document" }
            val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size

            ImportResult.Success(
                text = text.trim(),
                title = title,
                format = "HTML",
                wordCount = wordCount,
                charCount = text.length
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                error = ImportError.MalformedContent,
                rawMessage = e.message ?: "Failed to parse HTML document",
                fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
            )
        }
    }
}
