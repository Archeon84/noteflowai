package com.noteflowai.app.data.memory.adapter

import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType
import java.util.UUID

/**
 * Adapts YouTube transcript/caption text into SourceSegments.
 *
 * YouTube captions may contain timestamp markers that can be parsed
 * into per-utterance segments. The adapter handles both timestamped
 * and plain-text caption formats.
 */
object YouTubeSegmentAdapter {

    private val timestampPattern = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]""")

    /**
     * Create SourceSegments from a YouTube caption result.
     *
     * @param sourceId The YouTube video identifier or URL.
     * @param text The full caption/transcript text.
     * @param videoUrl The YouTube video URL.
     * @param language The caption language code.
     * @param videoTitle The video title (for metadata).
     * @return List of SourceSegments. If timestamps are present, creates per-utterance segments.
     */
    fun adapt(
        sourceId: String,
        text: String,
        videoUrl: String? = null,
        language: String? = null,
        videoTitle: String? = null
    ): List<SourceSegment> {
        if (text.isBlank()) return emptyList()

        // Try to parse timestamped captions
        val timestampedSegments = parseTimestampedCaptions(sourceId, text, videoUrl, language, videoTitle)
        if (timestampedSegments.isNotEmpty()) {
            return timestampedSegments
        }

        // No timestamps found — create a single segment
        return listOf(
            SourceSegment(
                id = "youtube_${sourceId}_${UUID.randomUUID()}",
                sourceId = sourceId,
                sourceType = SourceType.YOUTUBE,
                text = text.trim(),
                normalizedText = text.trim().lowercase(),
                url = videoUrl,
                language = language,
                isOriginalContent = true,
                metadataJson = buildMetadataJson(videoTitle, videoUrl)
            )
        )
    }

    /**
     * Parse timestamped caption text into per-utterance SourceSegments.
     * Supports formats like "[01:23] Hello world" and "[1:23.456] Hello world".
     */
    private fun parseTimestampedCaptions(
        sourceId: String,
        text: String,
        videoUrl: String?,
        language: String?,
        videoTitle: String?
    ): List<SourceSegment> {
        val lines = text.lines()
        val segments = mutableListOf<SourceSegment>()
        var currentStartMs: Long? = null
        val currentText = StringBuilder()

        for (line in lines) {
            val match = timestampPattern.find(line)
            if (match != null) {
                // Save previous segment if exists
                if (currentStartMs != null && currentText.isNotEmpty()) {
                    segments.add(createTimestampedSegment(
                        sourceId, currentStartMs, null, currentText.toString().trim(),
                        videoUrl, language, videoTitle
                    ))
                }
                // Parse new timestamp. The fractional group is decimal
                // seconds, not millis: ".45" is 450ms, so right-pad to 3
                // digits instead of adding the raw value (+45ms was wrong).
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fraction = match.groupValues[3]
                val millis = fraction.takeIf { it.isNotEmpty() }
                    ?.padEnd(3, '0')?.toLongOrNull() ?: 0L
                currentStartMs = minutes * 60_000 + seconds * 1000 + millis
                currentText.clear()
                // Append text after the timestamp
                val textAfterTimestamp = line.substring(match.range.last + 1).trim()
                if (textAfterTimestamp.isNotEmpty()) {
                    currentText.append(textAfterTimestamp)
                }
            } else {
                if (currentText.isNotEmpty()) currentText.append(" ")
                currentText.append(line.trim())
            }
        }

        // Don't forget the last segment
        if (currentStartMs != null && currentText.isNotEmpty()) {
            segments.add(createTimestampedSegment(
                sourceId, currentStartMs, null, currentText.toString().trim(),
                videoUrl, language, videoTitle
            ))
        }

        // Backfill endMs from the next segment's start: ranges (DAY_RANGE
        // precision downstream) can never form while endMs stays null.
        return segments.mapIndexed { index, segment ->
            val nextStart = segments.getOrNull(index + 1)?.startMs
            if (segment.endMs == null && nextStart != null && nextStart > (segment.startMs ?: 0L)) {
                segment.copy(endMs = nextStart)
            } else {
                segment
            }
        }
    }

    private fun createTimestampedSegment(
        sourceId: String,
        startMs: Long,
        endMs: Long?,
        text: String,
        videoUrl: String?,
        language: String?,
        videoTitle: String?
    ): SourceSegment {
        return SourceSegment(
            id = "youtube_${sourceId}_t${startMs}_${UUID.randomUUID()}",
            sourceId = sourceId,
            sourceType = SourceType.YOUTUBE,
            text = text,
            normalizedText = text.lowercase(),
            startMs = startMs,
            endMs = endMs,
            url = videoUrl,
            language = language,
            isOriginalContent = true,
            metadataJson = buildMetadataJson(videoTitle, videoUrl)
        )
    }

    private fun buildMetadataJson(videoTitle: String?, videoUrl: String?): String {
        // Gson escaping: the old hand-rolled interpolation broke on quotes,
        // backslashes, and newlines in titles.
        return com.google.gson.Gson().toJson(mapOf(
            "videoTitle" to (videoTitle ?: "unknown"),
            "videoUrl" to (videoUrl ?: "")
        ))
    }
}
