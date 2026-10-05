package com.noteflowai.app.data.importer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DocumentImportersTest {

    @Test
    fun testTextImporterSuccess() {
        runBlocking {
            val tempFile = File.createTempFile("test_note", ".txt")
            tempFile.writeText("This is a sample note for NoteFlowAI.")

            val importer = TextImporter()
            val input = ImportInput(file = tempFile, mimeType = "text/plain", title = "test_note.txt")

            val inspection = importer.inspect(input)
            assertTrue(inspection.isSupported)
            assertEquals("TXT", inspection.formatLabel)

            val result = importer.import(input)
            assertTrue(result is ImportResult.Success)
            val success = result as ImportResult.Success
            assertEquals(7, success.wordCount)
            assertEquals("This is a sample note for NoteFlowAI.", success.text.trim())

            tempFile.delete()
        }
    }

    @Test
    fun testTextImporterEmptyContent() {
        runBlocking {
            val tempFile = File.createTempFile("empty_note", ".txt")
            tempFile.writeText("   \n\t  ")

            val importer = TextImporter()
            val input = ImportInput(file = tempFile, mimeType = "text/plain")

            val result = importer.import(input)
            assertTrue(result is ImportResult.Failure)
            val failure = result as ImportResult.Failure
            assertEquals(ImportError.EmptyContent, failure.error)
            assertEquals(FallbackAction.CHOOSE_ANOTHER_FILE, failure.fallbackAction)

            tempFile.delete()
        }
    }

    @Test
    fun testHtmlImporterSuccess() {
        runBlocking {
            val html = """
                <!DOCTYPE html>
                <html>
                <head><title>Project Meeting</title></head>
                <body>
                    <script>alert('bad');</script>
                    <h1>Project Alpha</h1>
                    <p>Discussed sprint milestones and deliverable timeline.</p>
                </body>
                </html>
            """.trimIndent()

            val importer = HtmlImporter()
            val tempFile = File.createTempFile("page", ".html")
            tempFile.writeText(html)

            val input = ImportInput(file = tempFile, mimeType = "text/html", title = "Meeting")
            val result = importer.import(input)
            assertTrue(result is ImportResult.Success)
            val success = result as ImportResult.Success
            assertFalse(success.text.contains("alert"))
            assertTrue(success.text.contains("Project Alpha"))
            assertTrue(success.text.contains("Discussed sprint milestones"))

            tempFile.delete()
        }
    }
}
