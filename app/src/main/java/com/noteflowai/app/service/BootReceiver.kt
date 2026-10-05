package com.noteflowai.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.noteflowai.app.data.RecallReminderManager
import com.noteflowai.app.data.ScheduledRecordingManager
import com.noteflowai.app.data.settings.SettingsManager

/**
 * Re-schedules daily alarms after device reboot.
 * AlarmManager alarms do not survive reboots; this receiver restores them
 * from persisted user preferences.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "Boot completed — re-scheduling alarms")
        val settings = SettingsManager(context)

        // Re-schedule daily digest if enabled
        if (settings.dailyDigestEnabledBlocking) {
            DailyDigestScheduler(context).schedule()
            Log.i(TAG, "Re-scheduled daily digest")
        }

        // Re-schedule recall reminders if enabled
        if (settings.recallRemindersEnabledBlocking) {
            RecallReminderManager(context).schedule(
                RecallReminderManager.ReminderConfig(enabled = true)
            )
            Log.i(TAG, "Re-scheduled recall reminders")
        }

        // Re-schedule scheduled recording if enabled (config persisted in DataStore)
        if (settings.scheduledRecordingEnabledBlocking) {
            val (hour, minute, duration) = settings.scheduledRecordingConfigBlocking
            ScheduledRecordingManager(context).schedule(
                ScheduledRecordingManager.ScheduleConfig(
                    hour = hour,
                    minute = minute,
                    enabled = true,
                    durationMinutes = duration
                )
            )
            Log.i(TAG, "Re-scheduled scheduled recording ($hour:$minute, ${duration}min)")
        }
    }
}
