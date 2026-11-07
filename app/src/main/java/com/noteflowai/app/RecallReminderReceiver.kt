package com.noteflowai.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.noteflowai.app.data.concept.ConceptExtractor
import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.search.NoteSearchIndex
import com.noteflowai.app.data.search.RecallPredictor
import com.noteflowai.app.data.temporal.TemporalIndex
import com.noteflowai.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that fires daily to surface proactive spaced-repetition
 * and conflict alerts through standard Android notifications.
 */
class RecallReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SHOW_REMINDER = "com.noteflowai.app.SHOW_RECALL_REMINDER"
        const val CHANNEL_ID = NotificationHelper.CHANNEL_RECALL
        const val NOTIFICATION_ID = NotificationHelper.NOTIFICATION_ID_RECALL
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SHOW_REMINDER) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!NotificationHelper.hasNotificationPermission(context)) {
                    return@launch
                }

                kotlinx.coroutines.withTimeout(8_000L) {
                    NotificationHelper.createAllNotificationChannels(context)

                    // Load indexes from disk
                    val searchIndex = NoteSearchIndex()
                    val extractor = ConceptExtractor(searchIndex)
                    val temporalIndex = TemporalIndex(extractor)
                    val conceptGraph = ConceptGraphRepository(extractor)

                    temporalIndex.loadFromDisk(context)
                    conceptGraph.loadFromDisk(context)

                    // Find stale concepts / contradictions
                    val predictor = RecallPredictor(temporalIndex, conceptGraph)
                    val suggestions = predictor.findRecallSuggestions()

                    if (suggestions.isEmpty()) return@withTimeout // nothing to remind about

                    val topSuggestions = suggestions.take(4)
                    val hasConflict = topSuggestions.any { it.type == RecallPredictor.RecallType.CONFLICT_DETECTED }
                    val targetScreen = if (hasConflict) "CONFLICT_DETECTION" else "MEMORY"

                    val title = if (hasConflict) {
                        "Conflicting Notes Detected"
                    } else {
                        "Revisit: ${topSuggestions.first().concept}"
                    }

                    val primaryDetail = topSuggestions.first().detail

                    // Build deep-link pending intent straight to the target Screen
                    val contentPendingIntent = NotificationHelper.createScreenPendingIntent(
                        context,
                        targetScreen,
                        200
                    )

                    // Action buttons
                    val reviewActionPendingIntent = NotificationHelper.createScreenPendingIntent(
                        context,
                        targetScreen,
                        201
                    )
                    val notesActionPendingIntent = NotificationHelper.createScreenPendingIntent(
                        context,
                        "NOTES",
                        202
                    )

                    val inboxStyle = NotificationCompat.InboxStyle()
                        .setBigContentTitle(title)
                        .setSummaryText("${suggestions.size} ideas to review")

                    topSuggestions.forEach { suggestion ->
                        val prefix = if (suggestion.type == RecallPredictor.RecallType.CONFLICT_DETECTED) "⚡ " else "• "
                        inboxStyle.addLine("$prefix${suggestion.concept}: ${suggestion.detail}")
                    }

                    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification_recall)
                        .setColor(0xFF8B5CF6.toInt())
                        .setContentTitle(title)
                        .setContentText(primaryDetail)
                        .setStyle(inboxStyle)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setContentIntent(contentPendingIntent)
                        .addAction(
                            R.drawable.ic_notification_recall,
                            context.getString(R.string.notification_action_review),
                            reviewActionPendingIntent
                        )
                        .addAction(
                            R.drawable.ic_widget_note,
                            "View Notes",
                            notesActionPendingIntent
                        )
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setPublicVersion(
                            NotificationCompat.Builder(context, CHANNEL_ID)
                                .setSmallIcon(R.drawable.ic_notification_recall)
                                .setContentTitle("Recall Reminder")
                                .setContentText("You have knowledge items to review")
                                .build()
                        )
                        .setAutoCancel(true)
                        .build()

                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.w("RecallReminderReceiver", "Failed to present recall reminder: ${e.message}")
            } finally {
                runCatching {
                    val settings = com.noteflowai.app.data.settings.SettingsManager.getInstance(context.applicationContext)
                    if (settings.recallRemindersEnabledBlocking) {
                        com.noteflowai.app.data.RecallReminderManager(context.applicationContext).schedule(
                            com.noteflowai.app.data.RecallReminderManager.ReminderConfig(enabled = true)
                        )
                    }
                }
                pendingResult.finish()
            }
        }
    }
}
