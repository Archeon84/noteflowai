package com.noteflowai.app.data.importer

import com.noteflowai.app.data.document.DocumentRepository
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImporterIntegrationTest {

    @Test
    fun testDocumentRepositoryDelegatesToImporterRegistry() {
        runBlocking {
            val tempFile = File.createTempFile("note", ".txt")
            tempFile.writeText("Imported text content")

            val repo = DocumentRepository(mockk(relaxed = true))
            val result = repo.importContent(ImportInput(file = tempFile, mimeType = "text/plain"))

            assertTrue(result is ImportResult.Success)
            tempFile.delete()
        }
    }
}
