package com.noteflowai.app.data.memory.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.noteflowai.app.data.memory.model.SourceSegment
import com.noteflowai.app.data.memory.model.SourceType

/**
 * Universal source navigator that opens content at the precise location
 * indicated by a SourceSegment's metadata.
 *
 * Every citation card in the app must call SourceNavigator to navigate
 * to the supporting evidence. If exact navigation is unavailable,
 * it falls back to opening the parent source and highlighting text.
 */
class SourceNavigator(private val context: Context) {

    companion object {
        private const val TAG = "SourceNavigator"
    }

    /**
     * Navigate to the source location indicated by a SourceSegment.
     * Returns a NavigationAction that the UI layer should handle.
     */
    fun navigateTo(segment: SourceSegment): NavigationAction {
        return when (segment.sourceType) {
            SourceType.AUDIO -> navigateToAudio(segment)
            SourceType.YOUTUBE -> navigateToYouTube(segment)
            SourceType.PDF -> navigateToPdf(segment)
            SourceType.OCR -> navigateToOcr(segment)
            SourceType.NOTE -> navigateToNote(segment)
            SourceType.DOCUMENT -> navigateToDocument(segment)
            SourceType.GENERATED_NOTE -> navigateToNote(segment)
            // Corrupt/unknown stored type: fall back to the note viewer keyed
            // by source id rather than misrouting to a media player.
            SourceType.UNKNOWN -> navigateToNote(segment)
        }
    }

    // -- Audio navigation --

    private fun navigateToAudio(segment: SourceSegment): NavigationAction {
        val startMs = segment.startMs
        return NavigationAction.OpenNote(
            fileName = segment.sourceId,
            highlightText = segment.text.take(100),
            scrollToTimestamp = startMs
        )
    }

    // -- YouTube navigation --

    private fun navigateToYouTube(segment: SourceSegment): NavigationAction {
        val videoUrl = segment.url
        val startMs = segment.startMs

        if (videoUrl != null) {
            // Open YouTube video at timestamp. Watch URLs already carry a
            // query string (?v=...), so the timestamp joins with & — using ?
            // would produce an invalid "...watch?v=abc?t=12" URL.
            val timestampSeconds = (startMs ?: 0L) / 1000
            val url = if (timestampSeconds > 0) {
                val separator = if ('?' in videoUrl) "&" else "?"
                "$videoUrl${separator}t=${timestampSeconds}"
            } else {
                videoUrl
            }
            return NavigationAction.OpenUrl(url)
        }

        // No URL — fall back to note if transcript was saved
        return NavigationAction.OpenNote(
            fileName = segment.sourceId,
            highlightText = segment.text.take(100),
            scrollToTimestamp = startMs
        )
    }

    // -- PDF navigation --

    private fun navigateToPdf(segment: SourceSegment): NavigationAction {
        val pageNumber = segment.pageNumber
        return NavigationAction.OpenNote(
            fileName = segment.sourceId,
            highlightText = segment.text.take(100),
            scrollToPage = pageNumber
        )
    }

    // -- OCR navigation --

    private fun navigateToOcr(segment: SourceSegment): NavigationAction {
        return NavigationAction.OpenNote(
            fileName = segment.sourceId,
            highlightText = segment.text.take(100)
        )
    }

    // -- Note navigation --

    private fun navigateToNote(segment: SourceSegment): NavigationAction {
        return NavigationAction.OpenNote(
            fileName = segment.sourceId,
            highlightText = segment.text.take(100),
            blockId = segment.blockId
        )
    }

    // -- Document navigation --

    private fun navigateToDocument(segment: SourceSegment): NavigationAction {
        return NavigationAction.OpenNote(
            fileName = segment.sourceId,
            highlightText = segment.text.take(100),
            scrollToPage = segment.pageNumber,
            blockId = segment.blockId
        )
    }

    /**
     * Open a URL in the system browser.
     */
    fun openExternalUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open URL: $url", e)
        }
    }
}

/**
 * Sealed class representing navigation actions the UI layer must handle.
 */
sealed class NavigationAction {
    /**
     * Navigate to a note, optionally highlighting text and scrolling to a location.
     */
    data class OpenNote(
        val fileName: String,
        val highlightText: String? = null,
        val scrollToTimestamp: Long? = null,
        val scrollToPage: Int? = null,
        val blockId: String? = null
    ) : NavigationAction()

    /**
     * Open an external URL (e.g., YouTube video).
     */
    data class OpenUrl(val url: String) : NavigationAction()
}
