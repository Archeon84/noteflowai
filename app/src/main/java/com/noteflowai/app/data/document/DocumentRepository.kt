package com.noteflowai.app.data.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.InputStreamReader

data class ExtractResult(
    val text: String,
    val metadata: DocumentMetadata
)

data class DocumentMetadata(
    val fileName: String,
    val format: String,
    val wordCount: Int,
    val charCount: Int
)

class DocumentRepository(private val context: Context) {

    companion object {
        private const val TAG = "DocumentRepo"

        const val MIME_PDF = "application/pdf"
        const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val MIME_TEXT = "text/plain"
        const val MIME_HTML = "text/html"

        fun getSupportedMimeTypes(): Array<String> = arrayOf(
            MIME_PDF,
            MIME_DOCX,
            MIME_TEXT,
            MIME_HTML
        )

        fun getFormatLabel(mimeType: String): String = when (mimeType) {
            MIME_PDF -> "PDF"
            MIME_DOCX -> "DOCX"
            MIME_TEXT -> "TXT"
            MIME_HTML -> "HTML"
            else -> "Unknown"
        }
    }

    init {
        try {
            PDFBoxResourceLoader.init(context)
        } catch (_: Exception) {}
    }

    private val importerRegistry by lazy { com.noteflowai.app.data.importer.ContentImporterRegistry(context) }

    suspend fun importContent(input: com.noteflowai.app.data.importer.ImportInput): com.noteflowai.app.data.importer.ImportResult {
        return importerRegistry.import(input)
    }

    suspend fun inspectContent(input: com.noteflowai.app.data.importer.ImportInput): com.noteflowai.app.data.importer.ImportInspection {
        return importerRegistry.inspect(input)
    }

    suspend fun extractText(uri: Uri, mimeType: String): ExtractResult {
        val text = when (mimeType) {
            MIME_PDF -> extractPdfText(uri)
            MIME_DOCX -> extractDocxText(uri)
            MIME_TEXT -> extractPlainText(uri)
            MIME_HTML -> extractHtmlText(uri)
            else -> throw IllegalArgumentException("Unsupported format: $mimeType")
        }

        if (text.isBlank()) {
            throw IllegalArgumentException("File appears to be empty or corrupted")
        }

        val fileName = getFileName(uri)
        val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val metadata = DocumentMetadata(
            fileName = fileName,
            format = getFormatLabel(mimeType),
            wordCount = wordCount,
            charCount = text.length
        )

        return ExtractResult(text = text, metadata = metadata)
    }

    private fun extractPdfText(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val document = PDDocument.load(inputStream)
                val stripper = PDFTextStripper()
                val text = stripper.getText(document)
                document.close()
                text
            } ?: throw IllegalArgumentException("Cannot open PDF file")
        } catch (e: Exception) {
            Log.e(TAG, "PDF extraction failed: ${e.message}", e)
            throw IllegalArgumentException("Failed to read PDF: ${e.message}")
        }
    }

    private fun extractDocxText(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val document = XWPFDocument(inputStream)
                val text = document.paragraphs.joinToString("\n") { it.text }
                document.close()
                text
            } ?: throw IllegalArgumentException("Cannot open DOCX file")
        } catch (e: Exception) {
            Log.e(TAG, "DOCX extraction failed: ${e.message}", e)
            throw IllegalArgumentException("Failed to read DOCX: ${e.message}")
        }
    }

    private fun extractPlainText(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            } ?: throw IllegalArgumentException("Cannot open text file")
        } catch (e: Exception) {
            Log.e(TAG, "Text extraction failed: ${e.message}", e)
            throw IllegalArgumentException("Failed to read text file: ${e.message}")
        }
    }

    private fun extractHtmlText(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val html = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
                val doc = Jsoup.parse(html)
                doc.text()
            } ?: throw IllegalArgumentException("Cannot open HTML file")
        } catch (e: Exception) {
            Log.e(TAG, "HTML extraction failed: ${e.message}", e)
            throw IllegalArgumentException("Failed to read HTML file: ${e.message}")
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "document"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}
