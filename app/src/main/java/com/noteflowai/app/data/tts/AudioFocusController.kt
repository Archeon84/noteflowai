package com.noteflowai.app.data.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Seam around the platform audio-focus request. Deepgram reads over its own MediaPlayer, so the
 * app must claim (and abandon) audio focus so other apps pause/duck and so our playback reacts to
 * incoming phone calls / navigation / focus-stealing apps.
 *
 * The stock Android TTS backend does NOT use this: TextToSpeech runs in its own engine that
 * manages focus internally, and claiming focus in the app would double-claim and cause ducking.
 */
interface AudioFocusController {

    /** Gain transient focus with ducking. Returns true when granted. */
    fun requestFocus(): Boolean

    /** Release the focus this controller holds. Safe to call when none is held. */
    fun abandonFocus()

    /** Playback should be paused (transient focus loss). */
    var onLossTransient: (() -> Unit)?

    /** Playback should be fully stopped (permanent focus loss). */
    var onLoss: (() -> Unit)?

    /** Focus was regained (transient loss ended); playback may resume. */
    var onGain: (() -> Unit)?
}

/** Real [AudioFocusController] backed by [AudioManager]. */
class RealAudioFocusController(context: Context) : AudioFocusController {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override var onLossTransient: (() -> Unit)? = null
    override var onLoss: (() -> Unit)? = null
    override var onGain: (() -> Unit)? = null

    private var focusRequest: AudioFocusRequest? = null

    override fun requestFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(::onFocusChange)
                .build()
            focusRequest = request
            return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        // Pre-O is out of scope (minSdk is 26/O anyway), but keep a defensive path.
        return true
    }

    override fun abandonFocus() {
        val request = focusRequest
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
            audioManager.abandonAudioFocusRequest(request)
            focusRequest = null
        }
    }

    private fun onFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onLossTransient?.invoke()
            AudioManager.AUDIOFOCUS_LOSS -> onLoss?.invoke()
            AudioManager.AUDIOFOCUS_GAIN -> onGain?.invoke()
        }
    }
}
