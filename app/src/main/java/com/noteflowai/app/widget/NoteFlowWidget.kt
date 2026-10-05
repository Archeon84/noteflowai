package com.noteflowai.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val views = RemoteViews(context.packageName, R.layout.widget_layout)

        // Set title text color
        views.setTextViewText(R.id.widgetTitleText, context.getString(R.string.app_name))
        views.setTextColor(R.id.widgetTitleText, 0xFFFFFFFF.toInt())
        views.setTextColor(R.id.widgetRecordLabel, 0xC8FFFFFF.toInt())
        views.setTextColor(R.id.widgetNoteLabel, 0xC8FFFFFF.toInt())

        // Wire the ListView to the RemoteViewsService
        val serviceIntent = Intent(context, NoteWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widgetNotesList, serviceIntent)
        views.setEmptyView(R.id.widgetNotesList, R.id.widgetEmptyText)

        // Pending intent template for note clicks
        val noteClickIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_WIDGET_OPEN_NOTE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        val noteClickPending = android.app.PendingIntent.getActivity(
            context, 10, noteClickIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.widgetNotesList, noteClickPending)

        // Tap the title opens the app at Home
        val openIntent = Intent(context, MainActivity::class.java)
        val openPending = android.app.PendingIntent.getActivity(
            context, 0, openIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetTitle, openPending)

        // Search button opens app
        val searchIntent = Intent(context, MainActivity::class.java)
        val searchPending = android.app.PendingIntent.getActivity(
            context, 11, searchIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetSearchBtn, searchPending)

        // Record action
        val recordIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_WIDGET_ACTION, MainActivity.ACTION_WIDGET_RECORD)
        }
        val recordPending = android.app.PendingIntent.getActivity(
            context, 1, recordIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetRecordBtn, recordPending)

        // New Note action
        val noteIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_WIDGET_ACTION, MainActivity.ACTION_WIDGET_NOTE)
        }
        val notePending = android.app.PendingIntent.getActivity(
            context, 2, noteIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widgetNoteBtn, notePending)

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
