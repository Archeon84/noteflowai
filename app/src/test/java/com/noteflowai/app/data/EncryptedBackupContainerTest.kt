package com.noteflowai.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class EncryptedBackupContainerTest {

    private val testPayload = "Hello NoteFlowAI Encrypted Container Content!".toByteArray(Charsets.UTF_8)
    private val correctPassword = "CorrectPassword123!"
    private val wrongPassword = "WrongPassword456!"

    @Test
    fun `encrypt and decrypt round trip with valid password succeeds`() {
        val encryptedOut = ByteArrayOutputStream()
        BackupManager.encryptContainer(
            inputStream = ByteArrayInputStream(testPayload),
            outputStream = encryptedOut,
            password = correctPassword
        )

        val ciphertextWithHeader = encryptedOut.toByteArray()
        assertTrue("Ciphertext must be larger than payload + header", ciphertextWithHeader.size > testPayload.size + 35)

        // Verify magic header "NFENC01"
        val magic = String(ciphertextWithHeader.copyOfRange(0, 7), Charsets.US_ASCII)
        assertEquals("NFENC01", magic)

        val decryptedOut = ByteArrayOutputStream()
        BackupManager.decryptContainer(
            inputStream = ByteArrayInputStream(ciphertextWithHeader),
            outputStream = decryptedOut,
            password = correctPassword
        )

        assertArrayEquals(testPayload, decryptedOut.toByteArray())
    }

    @Test
    fun `decrypt with wrong password throws exception`() {
        val encryptedOut = ByteArrayOutputStream()
        BackupManager.encryptContainer(
            inputStream = ByteArrayInputStream(testPayload),
            outputStream = encryptedOut,
            password = correctPassword
        )

        val ciphertextWithHeader = encryptedOut.toByteArray()
        val decryptedOut = ByteArrayOutputStream()

        assertThrows(Exception::class.java) {
            BackupManager.decryptContainer(
                inputStream = ByteArrayInputStream(ciphertextWithHeader),
                outputStream = decryptedOut,
                password = wrongPassword
            )
        }
    }

    @Test
    fun `decrypt with invalid magic header throws IllegalArgumentException`() {
        val invalidHeaderData = "NOTENCO".toByteArray(Charsets.US_ASCII) + ByteArray(50)
        val decryptedOut = ByteArrayOutputStream()

        assertThrows(IllegalArgumentException::class.java) {
            BackupManager.decryptContainer(
                inputStream = ByteArrayInputStream(invalidHeaderData),
                outputStream = decryptedOut,
                password = correctPassword
            )
        }
    }

    @Test
    fun `tampered ciphertext fails authenticated GCM tag verification`() {
        val encryptedOut = ByteArrayOutputStream()
        BackupManager.encryptContainer(
            inputStream = ByteArrayInputStream(testPayload),
            outputStream = encryptedOut,
            password = correctPassword
        )

        val tamperedData = encryptedOut.toByteArray()
        // Flip one byte in the ciphertext payload
        tamperedData[tamperedData.size - 1] = (tamperedData[tamperedData.size - 1].toInt() xor 0xFF).toByte()

        val decryptedOut = ByteArrayOutputStream()
        assertThrows(Exception::class.java) {
            BackupManager.decryptContainer(
                inputStream = ByteArrayInputStream(tamperedData),
                outputStream = decryptedOut,
                password = correctPassword
            )
        }
    }
}
