package com.noteflowai.app.data

import androidx.compose.runtime.Immutable

@Immutable
data class AudioRecording(
    val fileName: String,
    val filePath: String,
    val lastModified: String,
    val fileSize: Long = 0,
    val durationSeconds: Int = 0,
    val lastModifiedEpoch: Long = 0L
)