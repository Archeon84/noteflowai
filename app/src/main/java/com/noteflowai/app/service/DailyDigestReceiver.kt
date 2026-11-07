package com.noteflowai.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.noteflowai.app.R
import com.noteflowai.app.data.concept.ConceptExtractor
import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.digest.DailyDigestManager
import com.noteflowai.app.data.digest.DigestStorage
import com.noteflowai.app.data.search.NoteSearchIndex
import com.noteflowai.app.data.temporal.TemporalIndex
import com.noteflowai.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DailyDigestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DailyDigestReceiver"
        const val CHANNEL_ID = NotificationHelper.CHANNEL_DIGEST
        const val NOTIFICATION_ID = NotificationHelper.NOTIFICATION_ID_DIGEST
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DailyDigestScheduler.ACTION_GENERATE_DIGEST) return

        NotificationHelper.createAllNotificationChannels(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                kotlinx.coroutines.withTimeout(8_000L) {
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

                    if (!NotificationHelper.hasNotificationPermission(context)) {
                        return@withTimeout
                    }

                    val activityCount = digest.recentActivity.size
                    val questionCount = digest.openQuestions.size
                    val staleCount = digest.staleConcepts.size
                    val connectionCount = digest.hiddenConnections.size
                    val totalInsights = activityCount + questionCount + staleCount + connectionCount

                    val title = "Your Daily Knowledge Digest"
                    val primaryDetail = buildString {
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
                        if (isEmpty()) append("All caught up! Check today's synthesis.")
                    }

                    // Deep-link intent directly to the DIGEST screen
                    val contentPendingIntent = NotificationHelper.createScreenPendingIntent(
                        context,
                        "DIGEST",
                        300
                    )

                    val readActionPendingIntent = NotificationHelper.createScreenPendingIntent(
                        context,
                        "DIGEST",
                        301
                    )

                    val inboxStyle = NotificationCompat.InboxStyle()
                        .setBigContentTitle(title)
                        .setSummaryText("$totalInsights items synthesized")

                    if (activityCount > 0) inboxStyle.addLine("• $activityCount recent topics analyzed")
                    if (questionCount > 0) inboxStyle.addLine("• $questionCount open questions to explore")
                    if (connectionCount > 0) inboxStyle.addLine("• $connectionCount hidden connections discovered")
                    if (staleCount > 0) inboxStyle.addLine("• $staleCount concepts ready for recall")
                    if (totalInsights == 0) inboxStyle.addLine("• All caught up — tap to review knowledge graph")

                    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification_digest)
                        .setColor(0xFF8B5CF6.toInt())
                        .setContentTitle(title)
                        .setContentText(primaryDetail)
                        .setStyle(inboxStyle)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setContentIntent(contentPendingIntent)
                        .addAction(
                            R.drawable.ic_notification_digest,
                            context.getString(R.string.notification_action_read_digest),
                            readActionPendingIntent
                        )
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setPublicVersion(
                            NotificationCompat.Builder(context, CHANNEL_ID)
                                .setSmallIcon(R.drawable.ic_notification_digest)
                                .setContentTitle("Daily Digest")
                                .setContentText("Your daily brief is ready")
                                .build()
                        )
                        .setAutoCancel(true)
                        .build()

                    val managerNotif = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    managerNotif.notify(NOTIFICATION_ID, notification)

                    Log.i(TAG, "Daily digest notification published: $primaryDetail")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate digest: ${e.message}", e)
            } finally {
                DailyDigestScheduler(context).schedule()
                pendingResult.finish()
            }
        }
    }
}
