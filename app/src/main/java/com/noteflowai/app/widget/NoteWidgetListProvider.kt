package com.noteflowai.app.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.noteflowai.app.R
import com.noteflowai.app.data.NoteFile
import com.noteflowai.app.data.NoteRepository
import kotlinx.coroutines.runBlocking

/**
 * RemoteViewsFactory that provides recent notes data to the widget ListView.
 * Shows up to 5 notes: 3 pinned first, then 2 most recent unpinned.
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
            setTextViewText(R.id.widgetNoteTitle, note.fileName)
            setTextViewText(R.id.widgetNotePreview, note.preview)
            setViewVisibility(R.id.widgetNotePin, if (note.pinned) android.view.View.VISIBLE else android.view.View.GONE)

            // Fill-in intent for note click
            val fillIntent = Intent().apply {
                putExtra(EXTRA_NOTE_FILE_NAME, note.fileName)
            }
            setOnClickFillInIntent(R.id.widgetNoteTitle, fillIntent)
        }
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = notes.getOrNull(position)?.fileName?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    private fun loadNotes() {
        val repo = NoteRepository(context)
        runBlocking { repo.refreshNotesList() }
        val allNotes = repo.notesFlow.value

        // 3 pinned first, then 2 most recent
        val pinned = allNotes.filter { it.pinned }.take(3)
        val unpinned = allNotes.filter { !it.pinned }.take(5 - pinned.size)
        notes = pinned + unpinned
    }

    companion object {
        const val EXTRA_NOTE_FILE_NAME = "note_file_name"
    }
}
