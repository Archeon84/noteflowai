package com.noteflowai.app.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.noteflowai.app.ScheduledRecordingReceiver
import java.util.*

class ScheduledRecordingManager(private val context: Context) {

    data class ScheduleConfig(
        val hour: Int = 9,
        val minute: Int = 0,
        val enabled: Boolean = false,
        val durationMinutes: Int = 30
    )

    fun schedule(config: ScheduleConfig) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduledRecordingReceiver::class.java).apply {
            action = ScheduledRecordingReceiver.ACTION_START_SCHEDULED
            putExtra("duration_minutes", config.durationMinutes)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 1001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, config.hour)
            set(Calendar.MINUTE, config.minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
        }

        // setAlarmClock is exempt from Android 12+ background-start restrictions
        // and shows a user-visible alarm indicator. The receiver re-arms the next
        // occurrence after firing (daily recurrence is manual).
        val alarmClockInfo = AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent)
        try {
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (_: SecurityException) {
            // Fallback for devices that disallow setAlarmClock: exact alarm if permitted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }
        }
    }

    fun cancel() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduledRecordingReceiver::class.java).apply {
            action = ScheduledRecordingReceiver.ACTION_START_SCHEDULED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 1001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
