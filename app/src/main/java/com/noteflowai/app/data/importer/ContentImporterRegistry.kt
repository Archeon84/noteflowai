package com.noteflowai.app.data.importer

import android.content.Context
import com.noteflowai.app.data.youtube.YouTubeRepository

class ContentImporterRegistry(private val context: Context) {

    private val pdfImporter by lazy { PdfImporter(context) }
    private val docxImporter by lazy { DocxImporter(context) }
    private val textImporter by lazy { TextImporter(context) }
    private val htmlImporter by lazy { HtmlImporter(context) }
    private val ocrImporter by lazy { ImageOcrImporter(context) }
    private val youtubeImporter by lazy { YoutubeTranscriptImporter(YouTubeRepository()) }
    private val audioImporter by lazy { AudioImporter(context) }

    fun resolve(input: ImportInput): ContentImporter {
        val url = input.url.orEmpty()
        if (url.contains("youtube.com") || url.contains("youtu.be")) {
            return youtubeImporter
        }

        val mime = input.mimeType.orEmpty().lowercase()
        val extension = (input.file?.extension ?: input.uri?.path?.substringAfterLast('.', "")).orEmpty().lowercase()

        return when {
            mime == "application/pdf" || extension == "pdf" -> pdfImporter
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" || extension == "docx" -> docxImporter
            mime == "text/html" || extension == "html" || extension == "htm" -> htmlImporter
            mime.startsWith("image/") || extension in setOf("jpg", "jpeg", "png", "webp", "heic") -> ocrImporter
            mime.startsWith("audio/") || extension in setOf("wav", "mp3", "m4a", "ogg", "aac") -> audioImporter
            mime.startsWith("text/") || extension in setOf("txt", "md", "csv", "json") -> textImporter
            else -> textImporter
        }
    }

    suspend fun inspect(input: ImportInput): ImportInspection {
        return resolve(input).inspect(input)
    }

    suspend fun import(input: ImportInput): ImportResult {
        return resolve(input).import(input)
    }
}
