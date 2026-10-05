package com.noteflowai.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.noteflowai.app.service.RecordingService

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            ACTION_STOP_SCHEDULED -> {
                val serviceIntent = Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_STOP
                }
                context.startService(serviceIntent)
            }
        }
    }
}
