package com.noteflowai.app.data.importer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.InputStream

class DocxImporter(private val context: Context) : ContentImporter {

    private fun openStream(input: ImportInput): InputStream? {
        return when {
            input.file != null -> input.file.inputStream()
            input.uri != null -> context.contentResolver.openInputStream(input.uri)
            else -> null
        }
    }

    override suspend fun inspect(input: ImportInput): ImportInspection = withContext(Dispatchers.IO) {
        ImportInspection(
            isSupported = true,
            isPasswordProtected = false,
            formatLabel = "DOCX",
            estimatedBytes = input.file?.length() ?: 0L
        )
    }

    override suspend fun import(input: ImportInput): ImportResult = withContext(Dispatchers.IO) {
        val stream = openStream(input)
            ?: return@withContext ImportResult.Failure(ImportError.MalformedContent, "Cannot open DOCX stream")

        try {
            val doc = XWPFDocument(stream)
            val sb = StringBuilder()
            doc.paragraphs?.forEach { p ->
                val text = p.text
                if (!text.isNullOrBlank()) {
                    sb.append(text).append("\n\n")
                }
            }
            doc.tables?.forEach { table ->
                table.rows?.forEach { row ->
                    val rowText = row.tableCells?.joinToString(" | ") { it.text.trim() }
                    if (!rowText.isNullOrBlank()) {
                        sb.append(rowText).append("\n")
                    }
                }
            }
            doc.close()

            val text = sb.toString().trim()
            if (text.isBlank()) {
                return@withContext ImportResult.Failure(
                    error = ImportError.EmptyContent,
                    rawMessage = "DOCX document contains no readable text",
                    fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
                )
            }

            val title = input.title ?: input.file?.name ?: "Word Document"
            val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size

            ImportResult.Success(
                text = text,
                title = title,
                format = "DOCX",
                wordCount = wordCount,
                charCount = text.length
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                error = ImportError.MalformedContent,
                rawMessage = e.message ?: "Failed to read DOCX file",
                fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
            )
        }
    }
}
