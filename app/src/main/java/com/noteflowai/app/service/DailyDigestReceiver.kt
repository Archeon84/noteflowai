package com.noteflowai.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.noteflowai.app.MainActivity
import com.noteflowai.app.R
import com.noteflowai.app.data.concept.ConceptExtractor
import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.digest.DailyDigestManager
import com.noteflowai.app.data.digest.DigestStorage
import com.noteflowai.app.data.search.NoteSearchIndex
import com.noteflowai.app.data.temporal.TemporalIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DailyDigestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DailyDigestReceiver"
        private const val CHANNEL_ID = "daily_digest"
        private const val NOTIFICATION_ID = 3
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DailyDigestScheduler.ACTION_GENERATE_DIGEST) return

        createNotificationChannel(context)

        // Use goAsync() for coroutine work
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val searchIndex = NoteSearchIndex()
                val extractor = ConceptExtractor(searchIndex)
                val temporalIndex = TemporalIndex(extractor)
                val conceptGraph = ConceptGraphRepository(extractor)

                temporalIndex.loadFromDisk(context)
                conceptGraph.loadFromDisk(context)

                val manager = DailyDigestManager(temporalIndex, conceptGraph, searchIndex)
                val digest = manager.generateDigest()

                // Save to disk
                DigestStorage.save(context, digest)

                // Build notification
                val activityCount = digest.recentActivity.size
                val questionCount = digest.openQuestions.size
                val staleCount = digest.staleConcepts.size
                val connectionCount = digest.hiddenConnections.size

                val title = "Your Daily Digest"
                val detail = buildString {
                    if (activityCount > 0) append("$activityCount recent topics")
                    if (questionCount > 0) {
                        if (isNotEmpty()) append(", ")
                        append("$questionCount open questions")
                    }
                    if (staleCount > 0) {
                        if (isNotEmpty()) append(", ")
                        append("$staleCount stale concepts")
                    }
                    if (connectionCount > 0) {
                        if (isNotEmpty()) append(", ")
                        append("$connectionCount hidden connections")
                    }
                    if (isEmpty()) append("All caught up!")
                }

                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("navigate_to", "DIGEST")
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
                            .setContentTitle("Daily Digest")
                            .setContentText("Your daily brief is ready")
                            .build()
                    )
                    .setAutoCancel(true)
                    .build()

                val managerNotif = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                managerNotif.notify(NOTIFICATION_ID, notification)

                Log.i(TAG, "Daily digest generated: $detail")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate digest: ${e.message}", e)
            } finally {
                // Always re-schedule for tomorrow since setExactAndAllowWhileIdle fires once
                DailyDigestScheduler(context).schedule()
                pendingResult.finish()
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily Digest",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Daily summary of your notes, concepts, and open questions"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
