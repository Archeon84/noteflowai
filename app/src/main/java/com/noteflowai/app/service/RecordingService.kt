package com.noteflowai.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.noteflowai.app.MainActivity
import com.noteflowai.app.R
import com.noteflowai.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = NotificationHelper.CHANNEL_RECORDING
        const val NOTIFICATION_ID = NotificationHelper.NOTIFICATION_ID_RECORDING
        const val ACTION_STOP = "com.noteflowai.app.STOP_RECORDING"
        const val ACTION_PAUSE = "com.noteflowai.app.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.noteflowai.app.RESUME_RECORDING"

        val stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val pauseRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val resumeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val autoStopHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scheduledRecorder: com.noteflowai.app.data.AudioRecorderHelper? = null
    private var scheduledJob: Job? = null
    private var scheduledTempFile: File? = null
    private var scheduledFileName: String? = null

    private var recordingStartTime = System.currentTimeMillis()
    private var isPaused = false
    private var isScheduled = false

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createAllNotificationChannels(this)
        recordingStartTime = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequests.tryEmit(Unit)
                stopScheduledCaptureAndFinish()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                isPaused = true
                pauseRequests.tryEmit(Unit)
                scheduledRecorder?.pauseRecording()
                val notification = buildNotification(isScheduled = isScheduled, isPaused = true)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                isPaused = false
                resumeRequests.tryEmit(Unit)
                scheduledRecorder?.resumeRecording()
                val notification = buildNotification(isScheduled = isScheduled, isPaused = false)
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification)
                return START_NOT_STICKY
            }
        }

        isScheduled = intent?.getBooleanExtra("scheduled", false) == true
        if (recordingStartTime == 0L) {
            recordingStartTime = System.currentTimeMillis()
        }

        val notification = buildNotification(isScheduled = isScheduled, isPaused = isPaused)
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
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val fileName = "scheduled_$dateStr.wav"
        val tempFile = File(cacheDir, "temp_$fileName")
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
                withContext(Dispatchers.Main) {
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

    private fun buildNotification(
        isScheduled: Boolean = false,
        statusText: String? = null,
        isPaused: Boolean = false
    ): Notification {
        // Tapping the notification brings the user straight to the live Recording screen
        val pendingIntent = NotificationHelper.createScreenPendingIntent(this, "RECORD", 100)

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = PendingIntent.getService(
            this, 2,
            Intent(this, RecordingService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isPaused) {
            "NoteFlow AI — Recording Paused"
        } else if (isScheduled) {
            "NoteFlow AI — Scheduled Recording"
        } else {
            "NoteFlow AI — Recording Audio"
        }

        val contentText = statusText ?: if (isPaused) {
            getString(R.string.notification_recording_paused)
        } else if (isScheduled) {
            "Scheduled capture active — tap to view transcript"
        } else {
            "Recording in progress — tap to view transcript"
        }

        val pauseResumeActionTitle = if (isPaused) {
            getString(R.string.notification_action_resume)
        } else {
            getString(R.string.notification_action_pause)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setColor(0xFF8B5CF6.toInt())
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(R.drawable.ic_notification_mic, pauseResumeActionTitle, pauseResumeIntent)
            .addAction(R.drawable.ic_notification_mic, getString(R.string.notification_action_stop), stopIntent)

        if (!isPaused && recordingStartTime > 0L) {
            builder.setUsesChronometer(true)
                .setWhen(recordingStartTime)
                .setShowWhen(true)
        } else {
            builder.setUsesChronometer(false)
        }

        return builder.build()
    }

    fun updateNotification(text: String) {
        val notification = buildNotification(isScheduled = isScheduled, statusText = text, isPaused = isPaused)
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
