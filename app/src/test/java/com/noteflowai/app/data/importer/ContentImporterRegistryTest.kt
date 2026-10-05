package com.noteflowai.app.data.importer

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContentImporterRegistryTest {

    @Test
    fun testRegistryResolvesCorrectImporter() {
        runBlocking {
            val registry = ContentImporterRegistry(mockk(relaxed = true))

            val pdfInput = ImportInput(mimeType = "application/pdf")
            assertTrue(registry.resolve(pdfInput) is PdfImporter)

            val docxInput = ImportInput(file = File("report.docx"))
            assertTrue(registry.resolve(docxInput) is DocxImporter)

            val htmlInput = ImportInput(mimeType = "text/html")
            assertTrue(registry.resolve(htmlInput) is HtmlImporter)

            val ytInput = ImportInput(url = "https://www.youtube.com/watch?v=12345678901")
            assertTrue(registry.resolve(ytInput) is YoutubeTranscriptImporter)

            val txtInput = ImportInput(file = File("notes.txt"))
            assertTrue(registry.resolve(txtInput) is TextImporter)
        }
    }
}
