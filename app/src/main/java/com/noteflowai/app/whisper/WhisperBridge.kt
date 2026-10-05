package com.noteflowai.app.whisper

/**
 * JNI bridge to the native Whisper C++ library.
 * All heavy AI processing runs on-device via this interface.
 */
class WhisperBridge {

    private var nativePtr: Long = 0

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }

    /**
     * Initialize the Whisper engine with a model file.
     * @param modelFilePath Absolute path to the GGML model file (e.g., .bin)
     * @param nThreads Number of CPU threads to use (0 = auto-detect)
     * @return true if initialization succeeded
     */
    external fun initialize(modelFilePath: String, nThreads: Int): Boolean

    /**
     * Transcribe or translate an audio file.
     * @param audioFilePath Path to WAV file (16kHz, mono, 16-bit PCM)
     * @param language Source language (e.g., "en", "zh", "ja", "ko", "id")
     * @param transcribe True for transcription, false for translation to English
     * @param initialPrompt Optional initial prompt to guide transcription style and domain
     * @return The transcribed/translated text
     */
    external fun transcribe(
        audioFilePath: String,
        language: String,
        transcribe: Boolean,
        initialPrompt: String = ""
    ): String

    /**
     * Release native resources.
     */
    external fun release()

    /**
     * Diagnose a WAV file and return detailed info about its format.
     */
    external fun diagnoseWav(audioFilePath: String): String
}