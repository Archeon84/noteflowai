package com.noteflowai.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.noteflowai.app.MainActivity

/**
 * Centralized governance for NoteFlow AI system notifications and channels.
 * Follows Android 13+ (API 33) notification permission standards and Material You
 * notification ergonomics.
 */
object NotificationHelper {

    const val CHANNEL_RECORDING = "recording_channel"
    const val CHANNEL_RECALL = "recall_reminders"
    const val CHANNEL_DIGEST = "daily_digest"

    const val NOTIFICATION_ID_RECORDING = 1
    const val NOTIFICATION_ID_RECALL = 2
    const val NOTIFICATION_ID_DIGEST = 3

    /**
     * Initializes all notification channels with appropriate user-facing names,
     * descriptions, and importance levels. Call from Application.onCreate().
     */
    fun createAllNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // 1. Ongoing Foreground Recording Channel (Low importance prevents heads-up interruption)
        val recordingChannel = NotificationChannel(
            CHANNEL_RECORDING,
            "Audio Recording Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live status and playback controls when recording audio notes"
            setShowBadge(false)
        }

        // 2. Proactive Recall & Conflict Alerts (Default importance)
        val recallChannel = NotificationChannel(
            CHANNEL_RECALL,
            "Recall & Idea Insights",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Spaced repetition reminders and contradiction alerts across notes"
            setShowBadge(true)
            enableVibration(true)
        }

        // 3. Daily Knowledge Digest (Default importance)
        val digestChannel = NotificationChannel(
            CHANNEL_DIGEST,
            "Daily Knowledge Digest",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Curated daily summaries, open questions, and emergent concept links"
            setShowBadge(true)
        }

        manager.createNotificationChannels(listOf(recordingChannel, recallChannel, digestChannel))
    }

    /**
     * Checks whether the app currently holds permission to post notifications.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Builds a singleTop navigation PendingIntent to a specific Screen in MainActivity.
     */
    fun createScreenPendingIntent(
        context: Context,
        screenName: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, screenName)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
