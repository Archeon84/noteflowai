package com.noteflowai.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.noteflowai.app.MainActivity
import com.noteflowai.app.R

class NoteFlowWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE ||
            intent.action == ACTION_NOTES_CHANGED) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, NoteFlowWidget::class.java))
            for (id in ids) {
                updateWidget(context, mgr, id)
            }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widgetNotesList)
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)

        // Set static header title
        views.setTextViewText(R.id.widgetTitleText, context.getString(R.string.app_name))

        // Wire the ListView to the RemoteViewsService
        val serviceIntent = Intent(context, NoteWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widgetNotesList, serviceIntent)
        views.setEmptyView(R.id.widgetNotesList, R.id.widgetEmptyState)

        // Pending intent template for note list item clicks
        val noteClickIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_WIDGET_OPEN_NOTE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val noteClickPending = PendingIntent.getActivity(
            context, 10, noteClickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.widgetNotesList, noteClickPending)

        // Tap header title -> Open app at Home
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetTitle, openPending)

        // Search icon action
        val searchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_WIDGET_ACTION, MainActivity.ACTION_WIDGET_SEARCH)
        }
        val searchPending = PendingIntent.getActivity(
            context, 11, searchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetSearchBtn, searchPending)

        // Quick add (+) note icon action
        val quickAddIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_WIDGET_ACTION, MainActivity.ACTION_WIDGET_NOTE)
        }
        val quickAddPending = PendingIntent.getActivity(
            context, 12, quickAddIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetQuickAddBtn, quickAddPending)
        views.setOnClickPendingIntent(R.id.widgetEmptyState, quickAddPending)

        // Bottom Action 1: Record (Instant Voice Capture)
        val recordIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_WIDGET_ACTION, MainActivity.ACTION_WIDGET_RECORD)
        }
        val recordPending = PendingIntent.getActivity(
            context, 1, recordIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRecordBtn, recordPending)

        // Bottom Action 2: New Note
        val noteIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_WIDGET_ACTION, MainActivity.ACTION_WIDGET_NOTE)
        }
        val notePending = PendingIntent.getActivity(
            context, 2, noteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetNoteBtn, notePending)

        // Bottom Action 3: Ask AI (On-Device RAG Chat)
        val chatIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_WIDGET_ACTION, MainActivity.ACTION_WIDGET_CHAT)
        }
        val chatPending = PendingIntent.getActivity(
            context, 3, chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetChatBtn, chatPending)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    companion object {
        const val ACTION_NOTES_CHANGED = "com.noteflowai.app.NOTES_CHANGED"
        const val ACTION_WIDGET_OPEN_NOTE = "com.noteflowai.app.WIDGET_OPEN_NOTE"

        /**
         * Notify all widget instances that notes have changed.
         * Call this after saving or deleting notes.
         */
        fun notifyNotesChanged(context: Context) {
            val intent = Intent(context, NoteFlowWidget::class.java).apply {
                action = ACTION_NOTES_CHANGED
            }
            context.sendBroadcast(intent)
        }
    }
}
