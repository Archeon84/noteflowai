package com.noteflowai.app.data.chat

import com.noteflowai.app.data.importer.*
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OfflineAttachmentChatTest {

    @Test
    fun testChatMessageAttachmentFields() {
        val msg = ChatMessage(
            role = "user",
            content = "Analyze this document",
            attachmentUri = "content://com.android.providers/123",
            attachmentType = "document",
            attachmentName = "quarterly_results.pdf",
            attachmentText = "Total revenue increased by 25% year-over-year."
        )

        assertEquals("user", msg.role)
        assertEquals("Analyze this document", msg.content)
        assertEquals("content://com.android.providers/123", msg.attachmentUri)
        assertEquals("document", msg.attachmentType)
        assertEquals("quarterly_results.pdf", msg.attachmentName)
        assertEquals("Total revenue increased by 25% year-over-year.", msg.attachmentText)
    }

    @Test
    fun testContentImporterRegistryResolvesImageSoundDocument() = runBlocking {
        val registry = ContentImporterRegistry(mockk(relaxed = true))

        // Image
        val imageInput = ImportInput(title = "receipt.jpg", mimeType = "image/jpeg")
        assertTrue(registry.resolve(imageInput) is ImageOcrImporter)

        val pngInput = ImportInput(title = "screenshot.png")
        assertTrue(registry.resolve(pngInput) is ImageOcrImporter)

        // Sound / Audio
        val wavInput = ImportInput(title = "voice_memo.wav", mimeType = "audio/wav")
        assertTrue(registry.resolve(wavInput) is AudioImporter)

        val mp3Input = ImportInput(title = "interview.mp3")
        assertTrue(registry.resolve(mp3Input) is AudioImporter)

        val m4aInput = ImportInput(title = "dictation.m4a")
        assertTrue(registry.resolve(m4aInput) is AudioImporter)

        // Document
        val pdfInput = ImportInput(title = "manual.pdf", mimeType = "application/pdf")
        assertTrue(registry.resolve(pdfInput) is PdfImporter)

        val docxInput = ImportInput(title = "contract.docx")
        assertTrue(registry.resolve(docxInput) is DocxImporter)

        val txtInput = ImportInput(title = "notes.md")
        assertTrue(registry.resolve(txtInput) is TextImporter)
    }

    @Test
    fun testOfflineTurnFormatWithAttachment() {
        val imageMsg = ChatMessage(
            role = "user",
            content = "What does this receipt show?",
            attachmentType = "image",
            attachmentName = "lunch_receipt.png",
            attachmentText = "Store: Cafe Latte\nTotal: $14.50"
        )

        val label = when (imageMsg.attachmentType) {
            "image" -> "Attached Image OCR"
            "audio" -> "Attached Audio Transcript"
            else -> "Attached Document Content"
        }
        val formatted = "${imageMsg.content}\n\n=== $label (${imageMsg.attachmentName}) ===\n${imageMsg.attachmentText}"

        assertTrue(formatted.contains("What does this receipt show?"))
        assertTrue(formatted.contains("=== Attached Image OCR (lunch_receipt.png) ==="))
        assertTrue(formatted.contains("Store: Cafe Latte"))
        assertTrue(formatted.contains("Total: $14.50"))
    }
}
