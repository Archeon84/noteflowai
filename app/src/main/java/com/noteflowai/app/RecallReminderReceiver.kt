package com.noteflowai.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

import com.noteflowai.app.data.concept.ConceptExtractor
import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.search.NoteSearchIndex
import com.noteflowai.app.data.search.RecallPredictor
import com.noteflowai.app.data.temporal.TemporalIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that fires daily to show a recall reminder notification.
 * Loads indexes from disk, runs RecallPredictor, and surfaces stale concepts.
 */
class RecallReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SHOW_REMINDER = "com.noteflowai.app.SHOW_RECALL_REMINDER"
        private const val CHANNEL_ID = "recall_reminders"
        private const val NOTIFICATION_ID = 2
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SHOW_REMINDER) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                createNotificationChannel(context)

                // Load indexes from disk (same pattern as MainViewModel init)
                val searchIndex = NoteSearchIndex()
                val extractor = ConceptExtractor(searchIndex)
                val temporalIndex = TemporalIndex(extractor)
                val conceptGraph = ConceptGraphRepository(extractor)

                temporalIndex.loadFromDisk(context)
                conceptGraph.loadFromDisk(context)

                // Find stale concepts
                val predictor = RecallPredictor(temporalIndex, conceptGraph)
                val suggestions = predictor.findRecallSuggestions()

                if (suggestions.isEmpty()) return@launch  // nothing to remind about

                // Build notification with top suggestions
                val topSuggestions = suggestions.take(3)
                val title = if (topSuggestions.any { it.type == RecallPredictor.RecallType.CONFLICT_DETECTED }) {
                    "Conflicting notes found"
                } else {
                    "Revisit: ${topSuggestions.first().concept}"
                }
                val detail = if (topSuggestions.size == 1) {
                    topSuggestions.first().detail
                } else {
                    "${topSuggestions.first().detail} +${topSuggestions.size - 1} more"
                }

                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_mic)
                    .setContentTitle(title)
                    .setContentText(detail)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(
                        NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification_mic)
                            .setContentTitle("Recall reminder")
                            .setContentText("You have ideas to revisit")
                            .build()
                    )
                    .setAutoCancel(true)
                    .build()

                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.w("RecallReminderReceiver", "Failed: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recall Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Daily reminders to revisit stale concepts and notes"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
