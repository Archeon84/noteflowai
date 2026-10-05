package com.noteflowai.app.data.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * On-device TextToSpeech backend implementing [TtsManager].
 *
 * Wraps Android [TextToSpeech] and publishes a real [PlaybackState] via a
 * [PlaybackStateMachine]. Android's TTS engine manages its own audio focus (it is an external
 * service), so unlike the Deepgram backend this class claims no focus and cannot be ducked by the
 * app; wins/losses of focus are handled by the engine.
 *
 * Android TTS has no native resume. [pause] therefore stops the engine and reports a Paused state;
 * [resume] replays the same item from the start. This is an honest representation of what the
 * platform supports rather than a fake "position" that could never be resumed mid-utterance.
 */
class AndroidTtsManager(context: Context) : TextToSpeech.OnInitListener, TtsManager {

    companion object {
        private const val TAG = "TtsManager"
    }

    private var tts: TextToSpeech? = null

    private val machine = PlaybackStateMachine()
    override val state: StateFlow<PlaybackState> = machine.state

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<String>>(emptyList())
    val availableVoices: StateFlow<List<String>> = _availableVoices.asStateFlow()

    /** A speak request made before the engine finished initializing, flushed once ready. */
    private data class PendingSpeak(val itemId: String, val text: String)
    private var pendingSpeak: PendingSpeak? = null

    @Volatile private var currentGen = 0L
    @Volatile private var currentItemId: String? = null
    @Volatile private var currentText: String? = null
    @Volatile private var pausedItemId: String? = null

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                val result = engine.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "English not supported, falling back to default")
                }
                engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (machine.isCurrent(currentGen)) machine.playing(positionMs = 0L, durationMs = null)
                    }

                    override fun onDone(utteranceId: String?) {
                        // A pause() stops the engine; its onStop handler owns the Paused state, so a
                        // trailing onDone while paused must not force us back to Idle.
                        if (pausedItemId == null && machine.isCurrent(currentGen)) machine.idle()
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (pausedItemId != null) {
                            machine.paused(positionMs = 0L)
                        } else {
                            // stop() already cancelled the generation; keep state consistent.
                            if (machine.isCurrent(currentGen)) machine.cancel()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onError(utteranceId, -1)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS error: $errorCode")
                        if (pausedItemId == null && machine.isCurrent(currentGen)) machine.failed("tts_error_$errorCode")
                    }
                })

                val voices = engine.voices?.map { it.name } ?: emptyList()
                _availableVoices.value = voices
                _isReady.value = true
                Log.i(TAG, "TTS initialized, ${voices.size} voices available")

                // Flush a speak request that arrived while the engine was still initializing.
                val pending = pendingSpeak
                pendingSpeak = null
                pending?.let { speakNow(it.itemId, it.text) }
            }
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    override suspend fun play(itemId: String, text: String) {
        currentGen = machine.newPlay(itemId)
        currentItemId = itemId
        currentText = text
        pausedItemId = null

        if (!_isReady.value) {
            // Engine still initializing — hold the request and speak it once onInit completes.
            pendingSpeak = PendingSpeak(itemId, text)
            Log.w(TAG, "TTS not ready yet, queuing speak")
            return
        }
        speakNow(itemId, text)
    }

    override suspend fun pause() {
        // Only meaningful while something is Preparing/Playing.
        val active = state.value
        val itemId = when (active) {
            is PlaybackState.Playing -> active.itemId
            is PlaybackState.Preparing -> active.itemId
            else -> return
        }
        pausedItemId = itemId
        tts?.stop()
        machine.paused(positionMs = 0L)
    }

    override suspend fun resume() {
        val id = currentItemId ?: return
        val text = currentText ?: return
        pausedItemId = null
        currentGen = machine.newPlay(id)
        if (!_isReady.value) {
            pendingSpeak = PendingSpeak(id, text)
            return
        }
        speakNow(id, text)
    }

    override suspend fun stop() {
        pendingSpeak = null
        pausedItemId = null
        tts?.stop()
        machine.cancel()
    }

    override suspend fun release() {
        stop()
        tts?.shutdown()
        tts = null
        _isReady.value = false
    }

    /** Speak text with the current rate/pitch (defaults, as no rate/pitch settings remain). */
    private fun speakNow(itemId: String, text: String) {
        val engine = tts ?: run {
            machine.failed("engine_unavailable")
            return
        }

        // Truncate very long texts to avoid TTS buffer overflow.
        val truncated = if (text.length > 3000) {
            text.take(3000) + "... [truncated]"
        } else {
            text
        }

        engine.setSpeechRate(1.0f)
        engine.setPitch(1.0f)

        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "ai_response_${System.currentTimeMillis()}")
        }
        engine.speak(truncated, TextToSpeech.QUEUE_FLUSH, params, "ai_response")
    }
}
