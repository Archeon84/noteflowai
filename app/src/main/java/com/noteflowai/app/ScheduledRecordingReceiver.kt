package com.noteflowai.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.noteflowai.app.service.RecordingService
import kotlinx.coroutines.launch

class ScheduledRecordingReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_START_SCHEDULED = "com.noteflowai.app.START_SCHEDULED_RECORDING"
        const val ACTION_STOP_SCHEDULED = "com.noteflowai.app.STOP_SCHEDULED_RECORDING"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_START_SCHEDULED -> {
                val durationMinutes = intent.getIntExtra("duration_minutes", 30)
                val serviceIntent = Intent(context, RecordingService::class.java).apply {
                    putExtra("duration_minutes", durationMinutes)
                    putExtra("scheduled", true)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ScheduledRecording", "Failed to start recording service from background: ${e.message}", e)
                }
                // Re-arm tomorrow's alarm off the main thread
                val pendingResult = goAsync()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val settings = com.noteflowai.app.data.settings.SettingsManager.getInstance(context.applicationContext)
                        if (settings.scheduledRecordingEnabledBlocking) {
                            val (hour, minute, duration) = settings.scheduledRecordingConfigBlocking
                            com.noteflowai.app.data.ScheduledRecordingManager(context.applicationContext).schedule(
                                com.noteflowai.app.data.ScheduledRecordingManager.ScheduleConfig(
                                    hour = hour,
                                    minute = minute,
                                    enabled = true,
                                    durationMinutes = duration
                                )
                            )
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ScheduledRecording", "Failed to re-arm scheduled recording: ${e.message}")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ACTION_STOP_SCHEDULED -> {
                RecordingService.stopRequests.tryEmit(Unit)
                val serviceIntent = Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_STOP
                }
                runCatching { context.stopService(serviceIntent) }
            }
        }
    }
}
