package com.noteflowai.app

import android.app.Application
import android.os.Build
import android.util.Log
import com.noteflowai.app.data.capture.CaptureJobScheduler
import com.noteflowai.app.data.memory.repository.ProcessingStatusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoteFlowAIApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Phase 1: resume any interrupted capture-processing work across process death.
        // Captures recorded before a force-close are re-enqueued as unique WorkManager jobs.
        CaptureJobScheduler.recoverPending(applicationContext)

        // One-shot: reset notes stranded in FAILED_PERMANENT by the old pipeline
        // (pre-self-healing, they failed with "No source segments found").
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val count = ProcessingStatusRepository(applicationContext)
                    .resetPermanentFailuresByReason("No source segments found")
                if (count > 0) Log.i("NoteFlowAI", "Reset $count FAILED_PERMANENT notes → PENDING")
            } catch (e: Throwable) {
                Log.w("NoteFlowAI", "Failed to reset FAILED_PERMANENT notes", e)
            }
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(filesDir, "crashlogs").also { it.mkdirs() }
                val file = File(dir, "crash_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt")
                PrintWriter(file).use { pw ->
                    pw.println("Thread: ${thread.name} (${thread.id})")
                    pw.println("OS: ${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}")
                    pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    pw.println()
                    throwable.printStackTrace(pw)
                }
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}