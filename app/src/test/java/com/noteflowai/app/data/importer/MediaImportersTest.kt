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
    fun testYoutubeImporterSuccess() {
        runBlocking {
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
    }

    @Test
    fun testYoutubeImporterTranscriptUnavailable() {
        runBlocking {
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

    @Test
    fun testAudioImporterTranscriptionSuccess() {
        runBlocking {
            val tempAudio = java.io.File.createTempFile("lecture", ".wav")
            tempAudio.writeBytes(ByteArray(128) { 1 })

            val importer = AudioImporter(
                context = mockk(relaxed = true),
                transcribeOverride = { "Quarterly product roadmap and engineering milestones." }
            )
            val input = ImportInput(file = tempAudio, mimeType = "audio/wav", title = "Lecture.wav")
            val result = importer.import(input)

            assertTrue(result is ImportResult.Success)
            val success = result as ImportResult.Success
            assertEquals("Lecture.wav", success.title)
            assertEquals("Audio", success.format)
            assertEquals(6, success.wordCount)
            assertTrue(success.text.contains("Quarterly product roadmap"))

            tempAudio.delete()
        }
    }

    @Test
    fun testAudioImporterMissingOrEmptyFileFailure() {
        runBlocking {
            val emptyAudio = java.io.File.createTempFile("empty", ".mp3")
            val importer = AudioImporter(context = mockk(relaxed = true))
            val result = importer.import(ImportInput(file = emptyAudio, mimeType = "audio/mpeg"))

            assertTrue(result is ImportResult.Failure)
            val failure = result as ImportResult.Failure
            assertEquals(ImportError.EmptyContent, failure.error)
            assertEquals(FallbackAction.CHOOSE_ANOTHER_FILE, failure.fallbackAction)

            emptyAudio.delete()
        }
    }
}
