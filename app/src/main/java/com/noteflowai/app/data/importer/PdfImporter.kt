package com.noteflowai.app.data.importer

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

class PdfImporter(private val context: Context) : ContentImporter {

    init {
        try {
            PDFBoxResourceLoader.init(context)
        } catch (_: Exception) {}
    }

    private fun openStream(input: ImportInput): InputStream? {
        return when {
            input.file != null -> input.file.inputStream()
            input.uri != null -> context.contentResolver.openInputStream(input.uri)
            else -> null
        }
    }

    override suspend fun inspect(input: ImportInput): ImportInspection = withContext(Dispatchers.IO) {
        val stream = openStream(input) ?: return@withContext ImportInspection(isSupported = false, formatLabel = "PDF")
        try {
            val doc = if (input.password.isNullOrBlank()) PDDocument.load(stream) else PDDocument.load(stream, input.password)
            val pages = doc.numberOfPages
            val isEncrypted = doc.isEncrypted
            doc.close()
            ImportInspection(
                isSupported = true,
                isPasswordProtected = isEncrypted,
                formatLabel = "PDF",
                pageCount = pages,
                estimatedBytes = input.file?.length() ?: 0L
            )
        } catch (e: InvalidPasswordException) {
            ImportInspection(
                isSupported = true,
                isPasswordProtected = true,
                formatLabel = "PDF"
            )
        } catch (e: Exception) {
            ImportInspection(
                isSupported = false,
                formatLabel = "PDF"
            )
        }
    }

    override suspend fun import(input: ImportInput): ImportResult = withContext(Dispatchers.IO) {
        val stream = openStream(input)
            ?: return@withContext ImportResult.Failure(ImportError.MalformedContent, "Cannot open PDF stream")

        try {
            val doc = if (input.password.isNullOrBlank()) PDDocument.load(stream) else PDDocument.load(stream, input.password)
            val stripper = PDFTextStripper()
            val text = stripper.getText(doc)
            val pageCount = doc.numberOfPages
            doc.close()

            if (text.isBlank()) {
                return@withContext ImportResult.Failure(
                    error = ImportError.EmptyContent,
                    rawMessage = "PDF contains no extractable text. It may contain scanned images.",
                    fallbackAction = FallbackAction.RUN_OCR
                )
            }

            val title = input.title ?: input.file?.name ?: "PDF Document"
            val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size

            ImportResult.Success(
                text = text.trim(),
                title = title,
                format = "PDF",
                wordCount = wordCount,
                charCount = text.length
            )
        } catch (e: InvalidPasswordException) {
            ImportResult.Failure(
                error = ImportError.PasswordProtected,
                rawMessage = "PDF is password protected",
                fallbackAction = FallbackAction.ENTER_PASSWORD
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                error = ImportError.MalformedContent,
                rawMessage = e.message ?: "Failed to extract PDF text",
                fallbackAction = FallbackAction.RUN_OCR
            )
        }
    }
}
