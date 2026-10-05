package com.noteflowai.app.data

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records audio from the microphone and writes a valid WAV file.
 * Supports configurable sample rates and real-time chunk streaming.
 */
class AudioRecorderHelper {

    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    @Volatile private var isPaused = false

    /**
     * How long (ms) to keep draining the microphone after [stopRecording] is
     * requested. A fresh AudioRecord fills its buffer with warm-up zeros that
     * the first blocking read returns immediately; if the stop races with the
     * start (as it can for push-to-talk, where finger-down and finger-up are
     * close together), the loop can exit after that single zero buffer and
     * capture none of the user's actual speech. Draining for this grace period
     * after stop guarantees the blocking reads pull real audio from the end of
     * the hold before the WAV is finalized.
     */
    private var stopGraceMs = 400L

    /** Whether the current session should keep draining after stop. */
    @Volatile private var drainAfterStop = false
    
    private var currentSampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    fun setQuality(qualityIndex: Int) {
        currentSampleRate = when(qualityIndex) {
            0 -> 8000
            1 -> 16000
            2 -> 44100
            else -> 16000
        }
    }

    suspend fun startRecording(outputFile: File, onChunkRead: ((ByteArray) -> Unit)? = null) {
        withContext(Dispatchers.IO) {
            drainAfterStop = false // reset each session
            val minBufferSize = AudioRecord.getMinBufferSize(currentSampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                throw IllegalStateException("Invalid buffer size")
            }
            
            val bufferSize = minBufferSize * 2

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                currentSampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                throw IllegalStateException("AudioRecord failed to initialize")
            }

            audioRecord?.startRecording()
            isRecording = true
            isPaused = false
            var totalAudioLen = 0L

            try {
                FileOutputStream(outputFile).use { fos ->
                    fos.write(ByteArray(44)) // Header placeholder

                    val buffer = ByteArray(minBufferSize)
                    var drainDeadline = 0L
                    var drainStarted = false
                    while (true) {
                        if (!isPaused) {
                            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                            if (read > 0) {
                                val chunk = buffer.copyOf(read)
                                fos.write(chunk)
                                onChunkRead?.invoke(chunk)
                                totalAudioLen += read
                            }
                        } else {
                            audioRecord?.read(buffer, 0, buffer.size)
                            delay(100)
                        }

                        // Once stop is requested, keep draining for a fixed grace
                        // window so the blocking reads pull the last stretch of
                        // real audio (fresh mic buffers start with zeros and the
                        // first read can race ahead of actual speech).
                        if (!isRecording && !drainStarted) {
                            drainStarted = true
                            drainDeadline = System.currentTimeMillis() + stopGraceMs
                        }
                        if (!isRecording) {
                            if (!drainAfterStop || System.currentTimeMillis() >= drainDeadline) {
                                break
                            }
                        }
                    }
                }

                if (totalAudioLen > 0) {
                    updateWavHeader(outputFile, totalAudioLen)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            } finally {
                cleanup()
            }
        }
    }

    /**
     * Enable draining the mic for [stopGraceMs] after [stopRecording] before the
     * WAV is finalized. Use for short push-to-talk clips where the start/stop
     * race can otherwise capture only the mic's initial zero-filled buffer.
     */
    fun setDrainAfterStop(enabled: Boolean) { drainAfterStop = enabled }

    fun pauseRecording() { isPaused = true }
    fun resumeRecording() { isPaused = false }
    fun stopRecording() { isRecording = false }

    private fun cleanup() {
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = currentSampleRate * 2L
        val channels = 1
        val bitsPerSample = 16

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte())
        header.putInt(totalDataLen.toInt())
        header.put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte())
        header.put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte())
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(currentSampleRate)
        header.putInt(byteRate.toInt())
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte())
        header.putInt(totalAudioLen.toInt())

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header.array())
        }
    }
}
