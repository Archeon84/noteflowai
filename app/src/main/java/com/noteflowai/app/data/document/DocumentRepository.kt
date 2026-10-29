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
        const val MIME_AUDIO_WAV = "audio/wav"
        const val MIME_AUDIO_XWAV = "audio/x-wav"
        const val MIME_AUDIO_MP3 = "audio/mpeg"
        const val MIME_AUDIO_MP4 = "audio/mp4"
        const val MIME_AUDIO_M4A = "audio/m4a"
        const val MIME_AUDIO_XM4A = "audio/x-m4a"
        const val MIME_AUDIO_AAC = "audio/aac"
        const val MIME_AUDIO_OGG = "audio/ogg"
        const val MIME_AUDIO_FLAC = "audio/flac"

        fun getSupportedMimeTypes(): Array<String> = arrayOf(
            MIME_PDF,
            MIME_DOCX,
            MIME_TEXT,
            MIME_HTML,
            MIME_AUDIO_WAV,
            MIME_AUDIO_XWAV,
            MIME_AUDIO_MP3,
            MIME_AUDIO_MP4,
            MIME_AUDIO_M4A,
            MIME_AUDIO_XM4A,
            MIME_AUDIO_AAC,
            MIME_AUDIO_OGG,
            MIME_AUDIO_FLAC
        )

        fun isSupportedMimeType(mimeType: String): Boolean {
            return mimeType in getSupportedMimeTypes() || mimeType.startsWith("audio/")
        }

        fun getFormatLabel(mimeType: String): String = when {
            mimeType == MIME_PDF -> "PDF"
            mimeType == MIME_DOCX -> "DOCX"
            mimeType == MIME_TEXT -> "TXT"
            mimeType == MIME_HTML -> "HTML"
            mimeType.startsWith("audio/") -> "Audio"
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

    suspend fun extractText(uri: Uri, mimeType: String): ExtractResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val fileName = getFileName(uri)
        val text = when {
            mimeType == MIME_PDF -> extractPdfText(uri)
            mimeType == MIME_DOCX -> extractDocxText(uri)
            mimeType == MIME_TEXT -> extractPlainText(uri)
            mimeType == MIME_HTML -> extractHtmlText(uri)
            mimeType.startsWith("audio/") -> extractAudioText(uri, mimeType, fileName)
            else -> throw IllegalArgumentException("Unsupported format: $mimeType")
        }

        if (text.isBlank()) {
            throw IllegalArgumentException("File appears to be empty or corrupted")
        }

        val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val metadata = DocumentMetadata(
            fileName = fileName,
            format = getFormatLabel(mimeType),
            wordCount = wordCount,
            charCount = text.length
        )

        ExtractResult(text = text, metadata = metadata)
    }

    private suspend fun extractAudioText(uri: Uri, mimeType: String, fileName: String): String {
        val result = com.noteflowai.app.data.importer.AudioImporter(context).import(
            com.noteflowai.app.data.importer.ImportInput(
                uri = uri,
                mimeType = mimeType,
                title = fileName
            )
        )
        return when (result) {
            is com.noteflowai.app.data.importer.ImportResult.Success -> result.text
            is com.noteflowai.app.data.importer.ImportResult.Failure -> throw IllegalArgumentException(result.rawMessage)
        }
    }

    private fun extractPdfText(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                PDDocument.load(inputStream).use { document ->
                    val stripper = PDFTextStripper()
                    stripper.getText(document)
                }
            } ?: throw IllegalArgumentException("Cannot open PDF file")
        } catch (e: Exception) {
            Log.e(TAG, "PDF extraction failed: ${e.message}", e)
            throw IllegalArgumentException("Failed to read PDF: ${e.message}")
        }
    }

    private fun extractDocxText(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                XWPFDocument(inputStream).use { document ->
                    val sb = StringBuilder()
                    document.paragraphs?.forEach { p ->
                        if (!p.text.isNullOrBlank()) {
                            sb.append(p.text).append("\n")
                        }
                    }
                    document.tables?.forEach { table ->
                        table.rows?.forEach { row ->
                            val rowText = row.tableCells?.joinToString(" | ") { it.text.trim() }
                            if (!rowText.isNullOrBlank()) {
                                sb.append(rowText).append("\n")
                            }
                        }
                    }
                    sb.toString().trim()
                }
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
