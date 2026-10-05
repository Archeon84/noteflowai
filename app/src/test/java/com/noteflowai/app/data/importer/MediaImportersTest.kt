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
}
