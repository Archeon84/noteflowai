package com.noteflowai.app.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts MP3/OGG/AAC/FLAC audio files to 16kHz mono 16-bit PCM WAV
 * using Android's built-in MediaCodec (no FFmpeg needed).
 */
object AudioConverter {

    /**
     * Convert any audio file to 16kHz mono WAV suitable for whisper.cpp.
     * Returns the output WAV file, or null on failure.
     */
    fun toWav(inputFile: File, outputFile: File): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(inputFile.absolutePath)

            if (extractor.trackCount == 0) return false

            // Find the audio track
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) return false

            extractor.selectTrack(audioTrackIndex)

            // Configure decoder to output PCM
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val pcmData = mutableListOf<ByteArray>()

            // Decode loop
            var inputDone = false
            var outputDone = false
            var postEosTimeouts = 0

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Read output
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex >= 0 -> {
                        postEosTimeouts = 0
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }

                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            pcmData.add(data)
                        }

                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> {
                        postEosTimeouts++
                        if (postEosTimeouts > 200) {
                            outputDone = true
                        }
                    }
                }
            }

            if (pcmData.isEmpty() || channels <= 0 || sampleRate <= 0) return false

            // Resample to 16kHz mono if needed
            val targetRate = 16000
            val targetChannels = 1

            // Combine all PCM chunks
            val totalSize = pcmData.sumOf { it.size }
            val allPcm = ByteArray(totalSize)
            var offset = 0
            for (chunk in pcmData) {
                System.arraycopy(chunk, 0, allPcm, offset, chunk.size)
                offset += chunk.size
            }

            // Convert multi-channel -> mono if needed (16-bit signed LE per channel)
            val monoPcm = if (channels >= 2) {
                val frameBytes = channels * 2
                val frames = totalSize / frameBytes
                val mono = ByteArray(frames * 2)
                val inBuf = ByteBuffer.wrap(allPcm).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until frames) {
                    var sum = 0
                    val baseByte = i * frameBytes
                    for (c in 0 until channels) {
                        sum += inBuf.getShort(baseByte + c * 2).toInt()
                    }
                    val avg = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    mono[i * 2] = (avg.toInt() and 0xFF).toByte()
                    mono[i * 2 + 1] = (avg.toInt() shr 8).toByte()
                }
                mono
            } else {
                allPcm
            }

            // Resample if needed (linear interpolation)
            val resampledPcm = if (sampleRate != targetRate) {
                val inputSamples = monoPcm.size / 2 // 16-bit samples
                if (inputSamples == 0) return false
                val ratio = sampleRate.toDouble() / targetRate
                val outputSamples = (inputSamples / ratio).toInt()
                val resampled = ByteArray(outputSamples * 2)
                val buf = ByteBuffer.wrap(monoPcm).order(ByteOrder.LITTLE_ENDIAN)

                for (i in 0 until outputSamples) {
                    val srcPos = (i * ratio).toInt().coerceIn(0, inputSamples - 1)
                    val sample = buf.getShort(srcPos * 2)
                    resampled[i * 2] = (sample.toInt() and 0xFF).toByte()
                    resampled[i * 2 + 1] = (sample.toInt() shr 8).toByte()
                }
                resampled
            } else {
                monoPcm
            }

            // Write WAV file
            writeWav(outputFile, resampledPcm, targetRate, targetChannels)
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun writeWav(file: File, pcmData: ByteArray, sampleRate: Int, channels: Int) {
        val totalDataLen = pcmData.size
        val byteRate = sampleRate * channels * 2 // 16-bit = 2 bytes
        val bitsPerSample = 16

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte())
            header.putInt(totalDataLen + 36)
            header.put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte())
            header.put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte())
            header.putInt(16)
            header.putShort(1) // PCM format
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort((channels * bitsPerSample / 8).toShort())
            header.putShort(bitsPerSample.toShort())
            header.put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte())
            header.putInt(totalDataLen)

            fos.write(header.array())
            fos.write(pcmData)
        }
    }
}
