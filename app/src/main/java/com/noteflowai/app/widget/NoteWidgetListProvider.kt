package com.noteflowai.app.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.noteflowai.app.R
import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.NoteRepository
import com.noteflowai.app.data.displayTitle
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RemoteViewsFactory providing notes data to the widget ListView.
 * Displays up to 20 notes with pinned notes prioritized, featuring relative
 * timestamps, high-contrast titles, and preview snippets.
 */
class NoteWidgetListProvider(
    private val context: Context,
    private val intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private var notes: List<NoteFile> = emptyList()

    override fun onCreate() {
        loadNotes()
    }

    override fun onDataSetChanged() {
        loadNotes()
    }

    override fun onDestroy() {
        notes = emptyList()
    }

    override fun getCount(): Int = notes.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in notes.indices) return null
        val note = notes[position]

        return RemoteViews(context.packageName, R.layout.widget_list_item).apply {
            setTextViewText(R.id.widgetNoteTitle, note.displayTitle)
            val previewText = note.preview.trim().ifEmpty { "Empty note" }
            setTextViewText(R.id.widgetNotePreview, previewText)

            val timeText = formatRelativeTime(note.lastModifiedEpoch, note.lastModified)
            setTextViewText(R.id.widgetNoteTime, timeText)

            setViewVisibility(R.id.widgetNotePin, if (note.pinned) View.VISIBLE else View.GONE)

            // Fill-in intent for note tap
            val fillIntent = Intent().apply {
                putExtra(EXTRA_NOTE_FILE_NAME, note.fileName)
            }
            setOnClickFillInIntent(R.id.widgetNoteItemRoot, fillIntent)
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        notes.getOrNull(position)?.fileName?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    private fun loadNotes() {
        try {
            val repo = NoteRepository.getInstance(context)
            runBlocking { repo.refreshNotesList() }
            val allNotes = repo.notesFlow.value

            // Up to 20 notes: pinned first, then most recent unpinned
            val pinned = allNotes.filter { it.pinned }.take(5)
            val unpinned = allNotes.filter { !it.pinned }.take(20 - pinned.size)
            notes = pinned + unpinned
        } catch (_: Exception) {
            notes = emptyList()
        }
    }

    companion object {
        const val EXTRA_NOTE_FILE_NAME = com.noteflowai.app.MainActivity.EXTRA_NOTE_FILE_NAME

        fun formatRelativeTime(epochMillis: Long, fallback: String): String {
            if (epochMillis <= 0L) return fallback
            val now = System.currentTimeMillis()
            val diff = now - epochMillis
            return when {
                diff < 60_000L -> "Just now"
                diff < 3600_000L -> "${diff / 60_000L}m ago"
                diff < 86400_000L -> "${diff / 3600_000L}h ago"
                diff < 86400_000L * 7L -> "${diff / 86400_000L}d ago"
                else -> {
                    try {
                        val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
                        sdf.format(Date(epochMillis))
                    } catch (_: Exception) {
                        fallback
                    }
                }
            }
        }
    }
}
