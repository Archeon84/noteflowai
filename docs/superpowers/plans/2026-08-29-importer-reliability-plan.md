# Phase 8: Importer Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Phase 8 Importer Reliability for NoteFlowAI, establishing unified and recoverable ingestion across documents (PDF, DOCX, TXT, HTML), images (OCR), audio files, and YouTube videos with inspection, typed `ImportError` categorization, plain-language recovery actions, and local fallbacks.

**Architecture:** A polymorphic `ContentImporter` strategy architecture coordinated by a `ContentImporterRegistry`. Each importer implements two stages: `inspect(input): ImportInspection` (metadata and requirement detection) and `import(input): ImportResult` (text/segment extraction, returning typed `ImportError` codes and actionable `FallbackAction` on failure).

**Tech Stack:** Kotlin, PDFBox Android, Apache POI, Jsoup, Google ML Kit Text Recognition, OkHttp, DataStore, Coroutines/Flow, JUnit4, MockK.

## Global Constraints

- Importer interface must match guide lines 1198-1203:
  ```kotlin
  interface ContentImporter {
      suspend fun inspect(input: ImportInput): ImportInspection
      suspend fun import(input: ImportInput): ImportResult
  }
  ```
- Typed errors must inherit from `sealed class ImportError(val code: String)` with exact codes from guide lines 1222-1233: `unsupported_format`, `password_protected`, `empty_content`, `transcript_unavailable`, `private_content`, `network_unavailable`, `rate_limited`, `provider_unavailable`, `malformed_content`.
- Concrete importers must include: `PdfImporter`, `DocxImporter`, `TextImporter`, `HtmlImporter`, `ImageOcrImporter`, `YoutubeTranscriptImporter`, `AudioImporter`.
- Every failure must provide a plain-language explanation, recovery next action (`FallbackAction`), and local fallback without infinite retry loops.

---

### Task 1: Core Importer Models, Typed Errors & Interface

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/importer/ImportModels.kt`
- Create: `app/src/main/java/com/noteflowai/app/data/importer/ContentImporter.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/importer/ImportModelsTest.kt`

**Interfaces:**
- Consumes: Core URI/File/MIME inputs.
- Produces: `ImportInput`, `ImportInspection`, `ImportResult`, `ImportError`, `FallbackAction`, and `ContentImporter` interface.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/importer/ImportModelsTest.kt`:

```kotlin
package com.noteflowai.app.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportModelsTest {

    @Test
    fun `import errors have exact guide-specified error codes`() {
        assertEquals("unsupported_format", ImportError.UnsupportedFormat.code)
        assertEquals("password_protected", ImportError.PasswordProtected.code)
        assertEquals("empty_content", ImportError.EmptyContent.code)
        assertEquals("transcript_unavailable", ImportError.TranscriptUnavailable.code)
        assertEquals("private_content", ImportError.PrivateContent.code)
        assertEquals("network_unavailable", ImportError.NetworkUnavailable.code)
        assertEquals("rate_limited", ImportError.RateLimited.code)
        assertEquals("provider_unavailable", ImportError.ProviderUnavailable.code)
        assertEquals("malformed_content", ImportError.MalformedContent.code)
    }

    @Test
    fun `import result success stores metadata and extracted text`() {
        val success = ImportResult.Success(
            text = "Extracted content from document",
            title = "Document.pdf",
            format = "PDF",
            wordCount = 4,
            charCount = 31
        )
        assertEquals("Document.pdf", success.title)
        assertEquals(4, success.wordCount)
        assertTrue(success.text.contains("Extracted content"))
    }

    @Test
    fun `import result failure stores typed error and fallback action`() {
        val failure = ImportResult.Failure(
            error = ImportError.TranscriptUnavailable,
            rawMessage = "No captions found for video",
            retryable = false,
            fallbackAction = FallbackAction.TRANSCRIBE_LOCALLY
        )
        assertEquals(ImportError.TranscriptUnavailable, failure.error)
        assertEquals(FallbackAction.TRANSCRIBE_LOCALLY, failure.fallbackAction)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.importer.ImportModelsTest"`
Expected: FAIL with Unresolved reference: com.noteflowai.app.data.importer

- [ ] **Step 3: Implement ImportModels and ContentImporter**

Create `app/src/main/java/com/noteflowai/app/data/importer/ImportModels.kt`:

```kotlin
package com.noteflowai.app.data.importer

import android.net.Uri
import java.io.File

data class ImportInput(
    val uri: Uri? = null,
    val file: File? = null,
    val url: String? = null,
    val mimeType: String? = null,
    val password: String? = null,
    val title: String? = null
)

data class ImportInspection(
    val isSupported: Boolean,
    val isPasswordProtected: Boolean = false,
    val formatLabel: String,
    val estimatedBytes: Long = 0L,
    val pageCount: Int? = null,
    val durationMs: Long? = null,
    val requiresNetwork: Boolean = false
)

enum class FallbackAction {
    RUN_OCR,
    IMPORT_AUDIO,
    TRANSCRIBE_LOCALLY,
    RETRY_LATER,
    CHOOSE_ANOTHER_FILE,
    ENTER_PASSWORD
}

sealed class ImportError(val code: String) {
    data object UnsupportedFormat : ImportError("unsupported_format")
    data object PasswordProtected : ImportError("password_protected")
    data object EmptyContent : ImportError("empty_content")
    data object TranscriptUnavailable : ImportError("transcript_unavailable")
    data object PrivateContent : ImportError("private_content")
    data object NetworkUnavailable : ImportError("network_unavailable")
    data object RateLimited : ImportError("rate_limited")
    data object ProviderUnavailable : ImportError("provider_unavailable")
    data object MalformedContent : ImportError("malformed_content")
}

sealed class ImportResult {
    data class Success(
        val text: String,
        val title: String,
        val format: String,
        val wordCount: Int,
        val charCount: Int,
        val sourceSegmentIds: List<String> = emptyList(),
        val rawCaptureId: String? = null
    ) : ImportResult()

    data class Failure(
        val error: ImportError,
        val rawMessage: String,
        val retryable: Boolean = false,
        val fallbackAction: FallbackAction? = null
    ) : ImportResult()
}
```

Create `app/src/main/java/com/noteflowai/app/data/importer/ContentImporter.kt`:

```kotlin
package com.noteflowai.app.data.importer

/**
 * Common abstraction for all content ingestion strategies (PDF, DOCX, TXT, HTML, OCR, YouTube, Audio).
 */
interface ContentImporter {
    suspend fun inspect(input: ImportInput): ImportInspection
    suspend fun import(input: ImportInput): ImportResult
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.importer.ImportModelsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/importer/ImportModels.kt app/src/main/java/com/noteflowai/app/data/importer/ContentImporter.kt app/src/test/java/com/noteflowai/app/data/importer/ImportModelsTest.kt
git commit -m "feat(importer): add core ContentImporter interface, typed ImportError, and models"
```

---

### Task 2: Document Importers (`PdfImporter`, `DocxImporter`, `TextImporter`, `HtmlImporter`)

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/importer/PdfImporter.kt`
- Create: `app/src/main/java/com/noteflowai/app/data/importer/DocxImporter.kt`
- Create: `app/src/main/java/com/noteflowai/app/data/importer/TextImporter.kt`
- Create: `app/src/main/java/com/noteflowai/app/data/importer/HtmlImporter.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/importer/DocumentImportersTest.kt`

**Interfaces:**
- Consumes: Android `Context`, PDFBox Android, Apache POI XWPF, Jsoup HTML parser.
- Produces: Robust `PdfImporter`, `DocxImporter`, `TextImporter`, `HtmlImporter` handling password-protection, malformed content, empty text, and extracting structured words/characters.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/importer/DocumentImportersTest.kt`:

```kotlin
package com.noteflowai.app.data.importer

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DocumentImportersTest {

    @Test
    fun `text importer extracts plain text and computes counts`() = runBlocking {
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

    @Test
    fun `text importer returns EmptyContent error on blank file`() = runBlocking {
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

    @Test
    fun `html importer strips scripts and extracts main body text`() = runBlocking {
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.importer.DocumentImportersTest"`
Expected: FAIL with Unresolved reference: TextImporter / HtmlImporter

- [ ] **Step 3: Implement TextImporter, HtmlImporter, PdfImporter, DocxImporter**

Create `app/src/main/java/com/noteflowai/app/data/importer/TextImporter.kt`:

```kotlin
package com.noteflowai.app.data.importer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class TextImporter(private val context: Context? = null) : ContentImporter {

    override suspend fun inspect(input: ImportInput): ImportInspection = withContext(Dispatchers.IO) {
        val size = input.file?.length() ?: 0L
        ImportInspection(
            isSupported = true,
            isPasswordProtected = false,
            formatLabel = "TXT",
            estimatedBytes = size
        )
    }

    override suspend fun import(input: ImportInput): ImportResult = withContext(Dispatchers.IO) {
        try {
            val text = when {
                input.file != null -> input.file.readText(Charsets.UTF_8)
                input.uri != null && context != null -> {
                    context.contentResolver.openInputStream(input.uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                    } ?: return@withContext ImportResult.Failure(ImportError.MalformedContent, "Cannot open file input stream")
                }
                else -> return@withContext ImportResult.Failure(ImportError.MalformedContent, "No file or URI provided")
            }

            if (text.isBlank()) {
                return@withContext ImportResult.Failure(
                    error = ImportError.EmptyContent,
                    rawMessage = "File contains no readable text",
                    fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
                )
            }

            val title = input.title ?: input.file?.name ?: "Text Document"
            val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
            ImportResult.Success(
                text = text.trim(),
                title = title,
                format = "TXT",
                wordCount = wordCount,
                charCount = text.length
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                error = ImportError.MalformedContent,
                rawMessage = e.message ?: "Failed to read text file",
                fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
            )
        }
    }
}
```

Create `app/src/main/java/com/noteflowai/app/data/importer/HtmlImporter.kt`:

```kotlin
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
```

Create `app/src/main/java/com/noteflowai/app/data/importer/PdfImporter.kt`:

```kotlin
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
```

Create `app/src/main/java/com/noteflowai/app/data/importer/DocxImporter.kt`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.importer.DocumentImportersTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/importer/TextImporter.kt app/src/main/java/com/noteflowai/app/data/importer/HtmlImporter.kt app/src/main/java/com/noteflowai/app/data/importer/PdfImporter.kt app/src/main/java/com/noteflowai/app/data/importer/DocxImporter.kt app/src/test/java/com/noteflowai/app/data/importer/DocumentImportersTest.kt
git commit -m "feat(importer): implement PdfImporter, DocxImporter, TextImporter, and HtmlImporter"
```

---

### Task 3: OCR and Media Importers (`ImageOcrImporter`, `AudioImporter`, `YoutubeTranscriptImporter`)

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/importer/ImageOcrImporter.kt`
- Create: `app/src/main/java/com/noteflowai/app/data/importer/AudioImporter.kt`
- Create: `app/src/main/java/com/noteflowai/app/data/importer/YoutubeTranscriptImporter.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/importer/MediaImportersTest.kt`

**Interfaces:**
- Consumes: `YouTubeRepository`, Whisper/Audio files, OCR engine.
- Produces: Importers for YouTube videos, Audio recordings, and Image OCR with granular error codes (`TranscriptUnavailable`, `PrivateContent`, `RateLimited`).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/importer/MediaImportersTest.kt`:

```kotlin
package com.noteflowai.app.data.importer

import com.noteflowai.app.data.youtube.YouTubeCaptionResult
import com.noteflowai.app.data.youtube.YouTubeRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaImportersTest {

    @Test
    fun `youtube importer returns Success when transcript is available`() = runBlocking {
        val ytRepo = mockk<YouTubeRepository>()
        every { ytRepo.extractVideoId("https://youtu.be/dQw4w9WgXcQ") } returns "dQw4w9WgXcQ"
        coEvery { ytRepo.fetchVideoTitle("dQw4w9WgXcQ") } returns "Never Gonna Give You Up"
        coEvery { ytRepo.fetchCaptions("dQw4w9WgXcQ") } returns YouTubeCaptionResult(
            success = true,
            transcript = "We're no strangers to love...",
            language = "en",
            source = "innertube"
        )

        val importer = YoutubeTranscriptImporter(ytRepo)
        val input = ImportInput(url = "https://youtu.be/dQw4w9WgXcQ")

        val result = importer.import(input)
        assertTrue(result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals("Never Gonna Give You Up", success.title)
        assertTrue(success.text.contains("strangers to love"))
    }

    @Test
    fun `youtube importer returns TranscriptUnavailable when captions disabled`() = runBlocking {
        val ytRepo = mockk<YouTubeRepository>()
        every { ytRepo.extractVideoId(any()) } returns "abc12345678"
        coEvery { ytRepo.fetchVideoTitle(any()) } returns "Music Video"
        coEvery { ytRepo.fetchCaptions(any()) } returns YouTubeCaptionResult(
            success = false,
            transcript = "",
            error = "Captions disabled"
        )

        val importer = YoutubeTranscriptImporter(ytRepo)
        val input = ImportInput(url = "https://www.youtube.com/watch?v=abc12345678")

        val result = importer.import(input)
        assertTrue(result is ImportResult.Failure)
        val failure = result as ImportResult.Failure
        assertEquals(ImportError.TranscriptUnavailable, failure.error)
        assertEquals(FallbackAction.TRANSCRIBE_LOCALLY, failure.fallbackAction)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.importer.MediaImportersTest"`
Expected: FAIL with Unresolved reference: YoutubeTranscriptImporter

- [ ] **Step 3: Implement YoutubeTranscriptImporter, AudioImporter, ImageOcrImporter**

Create `app/src/main/java/com/noteflowai/app/data/importer/YoutubeTranscriptImporter.kt`:

```kotlin
package com.noteflowai.app.data.importer

import com.noteflowai.app.data.youtube.YouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class YoutubeTranscriptImporter(
    private val youTubeRepository: YouTubeRepository
) : ContentImporter {

    override suspend fun inspect(input: ImportInput): ImportInspection = withContext(Dispatchers.IO) {
        val url = input.url ?: return@withContext ImportInspection(isSupported = false, formatLabel = "YouTube")
        val videoId = youTubeRepository.extractVideoId(url)
        ImportInspection(
            isSupported = videoId != null,
            formatLabel = "YouTube",
            requiresNetwork = true
        )
    }

    override suspend fun import(input: ImportInput): ImportResult = withContext(Dispatchers.IO) {
        val url = input.url
            ?: return@withContext ImportResult.Failure(ImportError.MalformedContent, "No YouTube URL provided")

        val videoId = youTubeRepository.extractVideoId(url)
            ?: return@withContext ImportResult.Failure(ImportError.UnsupportedFormat, "Invalid YouTube video URL")

        try {
            val title = youTubeRepository.fetchVideoTitle(videoId)
            val captionResult = youTubeRepository.fetchCaptions(videoId)

            if (!captionResult.success || captionResult.transcript.isBlank()) {
                val errMessage = captionResult.error ?: "Transcript unavailable"
                val importError = when {
                    errMessage.contains("private", ignoreCase = true) || errMessage.contains("unavailable", ignoreCase = true) ->
                        ImportError.PrivateContent
                    errMessage.contains("rate", ignoreCase = true) || errMessage.contains("429") ->
                        ImportError.RateLimited
                    else -> ImportError.TranscriptUnavailable
                }

                val fallback = when (importError) {
                    ImportError.RateLimited -> FallbackAction.RETRY_LATER
                    ImportError.PrivateContent -> FallbackAction.IMPORT_AUDIO
                    else -> FallbackAction.TRANSCRIBE_LOCALLY
                }

                return@withContext ImportResult.Failure(
                    error = importError,
                    rawMessage = errMessage,
                    retryable = importError == ImportError.RateLimited,
                    fallbackAction = fallback
                )
            }

            val text = captionResult.transcript.trim()
            val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size

            ImportResult.Success(
                text = text,
                title = title,
                format = "YouTube",
                wordCount = wordCount,
                charCount = text.length
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                error = ImportError.NetworkUnavailable,
                rawMessage = e.message ?: "Failed to connect to YouTube",
                retryable = true,
                fallbackAction = FallbackAction.RETRY_LATER
            )
        }
    }
}
```

Create `app/src/main/java/com/noteflowai/app/data/importer/ImageOcrImporter.kt`:

```kotlin
package com.noteflowai.app.data.importer

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ImageOcrImporter(private val context: Context) : ContentImporter {

    override suspend fun inspect(input: ImportInput): ImportInspection = withContext(Dispatchers.IO) {
        ImportInspection(
            isSupported = true,
            formatLabel = "OCR",
            estimatedBytes = input.file?.length() ?: 0L
        )
    }

    override suspend fun import(input: ImportInput): ImportResult = withContext(Dispatchers.IO) {
        try {
            val bitmap = when {
                input.file != null -> BitmapFactory.decodeFile(input.file.absolutePath)
                input.uri != null -> context.contentResolver.openInputStream(input.uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
                else -> null
            } ?: return@withContext ImportResult.Failure(ImportError.MalformedContent, "Cannot decode image file")

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(image).await()
            val text = visionText.text.trim()

            if (text.isBlank()) {
                return@withContext ImportResult.Failure(
                    error = ImportError.EmptyContent,
                    rawMessage = "No text detected in image",
                    fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
                )
            }

            val title = input.title ?: input.file?.name ?: "Scanned Image Note"
            val wordCount = text.split(Regex("\\s+")).filter { it.isNotBlank() }.size

            ImportResult.Success(
                text = text,
                title = title,
                format = "OCR",
                wordCount = wordCount,
                charCount = text.length
            )
        } catch (e: Exception) {
            ImportResult.Failure(
                error = ImportError.MalformedContent,
                rawMessage = e.message ?: "OCR recognition failed",
                fallbackAction = FallbackAction.CHOOSE_ANOTHER_FILE
            )
        }
    }
}
```

Create `app/src/main/java/com/noteflowai/app/data/importer/AudioImporter.kt`:

```kotlin
package com.noteflowai.app.data.importer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioImporter(private val context: Context) : ContentImporter {

    override suspend fun inspect(input: ImportInput): ImportInspection = withContext(Dispatchers.IO) {
        ImportInspection(
            isSupported = true,
            formatLabel = "Audio",
            estimatedBytes = input.file?.length() ?: 0L
        )
    }

    override suspend fun import(input: ImportInput): ImportResult = withContext(Dispatchers.IO) {
        // Audio files route to the transcription queue / RecordingRepository
        val file = input.file
        if (file == null || !file.exists()) {
            return@withContext ImportResult.Failure(ImportError.MalformedContent, "Audio file not found")
        }

        ImportResult.Success(
            text = "Audio file ready for transcription: ${file.name}",
            title = input.title ?: file.name,
            format = "Audio",
            wordCount = 0,
            charCount = 0
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.importer.MediaImportersTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/importer/YoutubeTranscriptImporter.kt app/src/main/java/com/noteflowai/app/data/importer/ImageOcrImporter.kt app/src/main/java/com/noteflowai/app/data/importer/AudioImporter.kt app/src/test/java/com/noteflowai/app/data/importer/MediaImportersTest.kt
git commit -m "feat(importer): implement YoutubeTranscriptImporter, ImageOcrImporter, and AudioImporter"
```

---

### Task 4: ContentImporterRegistry & Resolution Router

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/importer/ContentImporterRegistry.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/importer/ContentImporterRegistryTest.kt`

**Interfaces:**
- Consumes: Context, all concrete `ContentImporter` instances.
- Produces: `ContentImporterRegistry.resolve(input): ContentImporter` and `inspect(input)` / `import(input)` entry points.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/importer/ContentImporterRegistryTest.kt`:

```kotlin
package com.noteflowai.app.data.importer

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContentImporterRegistryTest {

    @Test
    fun `registry resolves correct importer by extension or mime`() = runBlocking {
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.importer.ContentImporterRegistryTest"`
Expected: FAIL with Unresolved reference: ContentImporterRegistry

- [ ] **Step 3: Implement ContentImporterRegistry**

Create `app/src/main/java/com/noteflowai/app/data/importer/ContentImporterRegistry.kt`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.importer.ContentImporterRegistryTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/importer/ContentImporterRegistry.kt app/src/test/java/com/noteflowai/app/data/importer/ContentImporterRegistryTest.kt
git commit -m "feat(importer): add ContentImporterRegistry resolving importers by mime, url, and extension"
```

---

### Task 5: DocumentRepository & ViewModels Integration

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/document/DocumentRepository.kt`
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/DocumentViewModel.kt`
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/YouTubeViewModel.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/importer/ImporterIntegrationTest.kt`

**Interfaces:**
- Consumes: `ContentImporterRegistry`, `DocumentRepository`, ViewModels.
- Produces: Clean integration of typed importer results and error fallback recommendations in `DocumentViewModel` and `YouTubeViewModel`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/importer/ImporterIntegrationTest.kt`:

```kotlin
package com.noteflowai.app.data.importer

import com.noteflowai.app.data.document.DocumentRepository
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImporterIntegrationTest {

    @Test
    fun `document repository delegates to importer registry`() = runBlocking {
        val tempFile = File.createTempFile("note", ".txt")
        tempFile.writeText("Imported text content")

        val repo = DocumentRepository(mockk(relaxed = true))
        val result = repo.importContent(ImportInput(file = tempFile, mimeType = "text/plain"))

        assertTrue(result is ImportResult.Success)
        tempFile.delete()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.importer.ImporterIntegrationTest"`
Expected: FAIL with Unresolved reference: importContent in DocumentRepository

- [ ] **Step 3: Update DocumentRepository and ViewModels**

In `app/src/main/java/com/noteflowai/app/data/document/DocumentRepository.kt`, add:
```kotlin
    private val importerRegistry by lazy { ContentImporterRegistry(context) }

    suspend fun importContent(input: ImportInput): ImportResult {
        return importerRegistry.import(input)
    }

    suspend fun inspectContent(input: ImportInput): ImportInspection {
        return importerRegistry.inspect(input)
    }
```

In `app/src/main/java/com/noteflowai/app/viewmodel/DocumentViewModel.kt` and `YouTubeViewModel.kt`:
Update state flows to expose `ImportResult.Failure` details (`error`, `fallbackAction`) so the UI displays the recommended action.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.importer.ImporterIntegrationTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/document/DocumentRepository.kt app/src/main/java/com/noteflowai/app/viewmodel/DocumentViewModel.kt app/src/main/java/com/noteflowai/app/viewmodel/YouTubeViewModel.kt app/src/test/java/com/noteflowai/app/data/importer/ImporterIntegrationTest.kt
git commit -m "feat(importer): integrate ContentImporterRegistry into DocumentRepository and ViewModels"
```

---

### Task 6: Strings, UI Fallback Actions, Regression & Build Verification

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/DocumentScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/YouTubeScreen.kt`
- Test: Complete test suite regression

**Interfaces:**
- Consumes: `strings.xml` error templates, `DocumentScreen`, `YouTubeScreen`.
- Produces: Actionable UI error cards with retry/fallback buttons, complete passing test suite, and assembled debug APK.

- [ ] **Step 1: Add string resources for importer errors and fallbacks**

In `app/src/main/res/values/strings.xml`:
```xml
    <!-- Importer Reliability (Phase 8) -->
    <string name="import_error_unsupported_format">This file type is not supported. Please select a supported document (PDF, DOCX, TXT, HTML) or audio file.</string>
    <string name="import_error_password_protected">This document is password-protected. Please enter the password to open it.</string>
    <string name="import_error_empty_content">No readable text was found in this file. Try running OCR or selecting another file.</string>
    <string name="import_error_transcript_unavailable">No transcript was provided for this video. You can transcribe local audio instead.</string>
    <string name="import_error_private_content">This content is private or restricted and cannot be accessed.</string>
    <string name="import_error_network_unavailable">Network is unavailable. Please check your internet connection and try again.</string>
    <string name="import_error_rate_limited">The service is temporarily limiting requests. Please try again in a few moments.</string>
    <string name="import_error_provider_unavailable">The extraction provider is currently unavailable. Please try again later.</string>
    <string name="import_error_malformed_content">The file appears to be damaged or corrupted.</string>

    <string name="import_action_run_ocr">Scan with OCR</string>
    <string name="import_action_transcribe_local">Transcribe Local Audio</string>
    <string name="import_action_enter_password">Enter Password</string>
    <string name="import_action_choose_another">Choose Another File</string>
```

- [ ] **Step 2: Update UI screens to render action buttons on import error**

Update `DocumentScreen.kt` and `YouTubeScreen.kt` error states to display the plain-language message and actionable button corresponding to `FallbackAction`.

- [ ] **Step 3: Run full unit test suite regression**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL with 100% passing tests.

- [ ] **Step 4: Assemble debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/java/com/noteflowai/app/ui/screens/DocumentScreen.kt app/src/main/java/com/noteflowai/app/ui/screens/YouTubeScreen.kt
git commit -m "feat(importer): add importer error strings and interactive fallback actions to UI"
```

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-29-importer-reliability-plan.md`.
Use `superpowers:subagent-driven-development` to execute the 6 tasks.
