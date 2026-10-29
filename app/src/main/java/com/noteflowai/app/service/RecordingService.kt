package com.noteflowai.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.noteflowai.app.MainActivity
import com.noteflowai.app.R

import android.content.pm.ServiceInfo
import android.os.Build
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.noteflowai.app.STOP_RECORDING"
        private const val AUTO_STOP_WHAT = 1001
        val stopRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val autoStopHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var scheduledRecorder: com.noteflowai.app.data.AudioRecorderHelper? = null
    private var scheduledJob: kotlinx.coroutines.Job? = null
    private var scheduledTempFile: java.io.File? = null
    private var scheduledFileName: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequests.tryEmit(Unit)
            stopScheduledCaptureAndFinish()
            return START_NOT_STICKY
        }

        val isScheduled = intent?.getBooleanExtra("scheduled", false) == true
        val notification = buildNotification(isScheduled)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("RecordingService", "Cannot start foreground microphone service: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (isScheduled && scheduledRecorder == null) {
            startScheduledCapture()
        }
        scheduleAutoStop(intent?.getIntExtra("duration_minutes", 0) ?: 0)
        return START_NOT_STICKY
    }

    private fun startScheduledCapture() {
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault()).format(java.util.Date())
        val fileName = "scheduled_$dateStr.wav"
        val tempFile = java.io.File(cacheDir, "temp_$fileName")
        val recorder = com.noteflowai.app.data.AudioRecorderHelper().apply { setQuality(1) }
        scheduledFileName = fileName
        scheduledTempFile = tempFile
        scheduledRecorder = recorder
        scheduledJob = serviceScope.launch {
            try {
                recorder.startRecording(tempFile)
            } catch (e: Exception) {
                android.util.Log.e("RecordingService", "Scheduled recording failed: ${e.message}", e)
            }
        }
    }

    private fun stopScheduledCaptureAndFinish() {
        val recorder = scheduledRecorder
        val job = scheduledJob
        val tempFile = scheduledTempFile
        val fileName = scheduledFileName
        scheduledRecorder = null
        scheduledJob = null
        scheduledTempFile = null
        scheduledFileName = null

        if (recorder != null && tempFile != null && fileName != null) {
            recorder.stopRecording()
            serviceScope.launch {
                runCatching { job?.join() }
                if (tempFile.exists() && tempFile.length() > 44L) {
                    runCatching {
                        com.noteflowai.app.data.RecordingRepository.getInstance(applicationContext)
                            .saveRecording(tempFile, fileName)
                    }
                }
                runCatching { tempFile.delete() }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } else {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Scheduled recordings carry duration_minutes; auto-stop after that window
     * so a background recording cannot run forever. Manual recordings pass 0
     * (no limit) and keep the existing stop-action behavior.
     */
    private fun scheduleAutoStop(durationMinutes: Int) {
        autoStopRunnable?.let { autoStopHandler.removeCallbacks(it) }
        if (durationMinutes <= 0) return
        val runnable = Runnable {
            stopRequests.tryEmit(Unit)
            stopScheduledCaptureAndFinish()
        }
        autoStopRunnable = runnable
        autoStopHandler.postDelayed(runnable, durationMinutes * 60_000L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NoteFlowAI::RecordingWakeLock"
            ).apply {
                // Bounded 4-hour safety timeout so battery cannot drain forever on crash
                acquire(4 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when audio is being recorded"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(isScheduled: Boolean = false, statusText: String? = null): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = statusText ?: if (isScheduled) {
            "Scheduled recording active — tap to open"
        } else {
            "Recording in progress — tap to open"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NoteFlow AI — Recording")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_notification_mic, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    fun updateNotification(text: String) {
        val notification = buildNotification(statusText = text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoStopRunnable?.let { autoStopHandler.removeCallbacks(it) }
        autoStopRunnable = null
        scheduledRecorder?.stopRecording()
        serviceScope.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
