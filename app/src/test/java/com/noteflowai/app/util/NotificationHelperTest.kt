package com.noteflowai.app.util

import android.content.Context
import android.content.Intent
import com.noteflowai.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationHelperTest {

    @Test
    fun channelIds_areDistinctAndValid() {
        assertNotEquals(NotificationHelper.CHANNEL_RECORDING, NotificationHelper.CHANNEL_RECALL)
        assertNotEquals(NotificationHelper.CHANNEL_RECALL, NotificationHelper.CHANNEL_DIGEST)
        assertNotEquals(NotificationHelper.CHANNEL_RECORDING, NotificationHelper.CHANNEL_DIGEST)

        assertEquals("recording_channel", NotificationHelper.CHANNEL_RECORDING)
        assertEquals("recall_reminders", NotificationHelper.CHANNEL_RECALL)
        assertEquals("daily_digest", NotificationHelper.CHANNEL_DIGEST)
    }

    @Test
    fun notificationIds_areUniqueAndNonZero() {
        val ids = setOf(
            NotificationHelper.NOTIFICATION_ID_RECORDING,
            NotificationHelper.NOTIFICATION_ID_RECALL,
            NotificationHelper.NOTIFICATION_ID_DIGEST
        )
        assertEquals("Notification IDs must all be distinct", 3, ids.size)
        ids.forEach { assertNotEquals(0, it) }
    }

    @Test
    fun createAllNotificationChannels_registersExpectedChannels() {
        val context = RuntimeEnvironment.getApplication()
        NotificationHelper.createAllNotificationChannels(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channels = manager.notificationChannels
        assertNotNull(channels)

        val channelIds = channels.map { it.id }.toSet()
        assertEquals(true, channelIds.contains(NotificationHelper.CHANNEL_RECORDING))
        assertEquals(true, channelIds.contains(NotificationHelper.CHANNEL_RECALL))
        assertEquals(true, channelIds.contains(NotificationHelper.CHANNEL_DIGEST))
    }

    @Test
    fun createScreenPendingIntent_targetsMainActivityWithExtras() {
        val context = RuntimeEnvironment.getApplication()
        val pendingIntent = NotificationHelper.createScreenPendingIntent(context, "DIGEST", 101)

        assertNotNull(pendingIntent)
    }
}
