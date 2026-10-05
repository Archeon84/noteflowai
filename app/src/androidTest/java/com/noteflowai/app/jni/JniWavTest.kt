package com.noteflowai.app.jni

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.noteflowai.app.whisper.WhisperBridge
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class JniWavTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()
    private val bridge = WhisperBridge()

    @Test
    fun testValidWavHeader() {
        val wavFile = createValidWav()
        try {
            val result = bridge.diagnoseWav(wavFile.absolutePath)
            Assert.assertTrue("Should detect RIFF and WAVE", result.contains("RIFF: yes"))
            Assert.assertTrue("Should detect WAVE", result.contains("WAVE: yes"))
        } finally {
            wavFile.delete()
        }
    }

    @Test
    fun testTruncatedWavHeader() {
        val wavFile = File(context.cacheDir, "truncated.wav")
        try {
            wavFile.writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46)) // Only "RIFF"
            val result = bridge.diagnoseWav(wavFile.absolutePath)
            Assert.assertTrue("Should detect RIFF", result.contains("RIFF: yes"))
            Assert.assertTrue("Should detect NO WAVE", result.contains("WAVE: NO"))
        } finally {
            wavFile.delete()
        }
    }

    @Test
    fun testNonRiffFile() {
        val wavFile = File(context.cacheDir, "not_riff.wav")
        try {
            wavFile.writeBytes("NOT_A_RIFF_FILE".toByteArray())
            val result = bridge.diagnoseWav(wavFile.absolutePath)
            Assert.assertTrue("Should detect NO RIFF", result.contains("RIFF: NO"))
        } finally {
            wavFile.delete()
        }
    }

    @Test
    fun testEmptyFile() {
        val wavFile = File(context.cacheDir, "empty.wav")
        try {
            wavFile.createNewFile()
            val result = bridge.diagnoseWav(wavFile.absolutePath)
            Assert.assertTrue("Should error on too small", result.contains("ERROR"))
        } finally {
            wavFile.delete()
        }
    }

    @Test
    fun testWhisperInitializeWithInvalidModel() {
        val result = bridge.initialize("/non/existent/path/model.bin")
        Assert.assertFalse("Should fail with invalid model path", result)
    }

    private fun createValidWav(): File {
        val file = File(context.cacheDir, "test_valid.wav")
        val fos = FileOutputStream(file)

        // RIFF header
        fos.write("RIFF".toByteArray())
        val dataSize = 32000 // 1 second of 16kHz mono 16-bit
        val fileSize = 36 + dataSize
        fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileSize).array())
        fos.write("WAVE".toByteArray())

        // fmt chunk
        fos.write("fmt ".toByteArray())
        fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array()) // chunk size
        fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1).array()) // PCM
        fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1).array()) // mono
        fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16000).array()) // sample rate
        fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(32000).array()) // byte rate
        fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(2).array()) // block align
        fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(16).array()) // bits per sample

        // data chunk
        fos.write("data".toByteArray())
        fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dataSize).array())
        val silence = ByteArray(dataSize)
        fos.write(silence)

        fos.close()
        return file
    }
}