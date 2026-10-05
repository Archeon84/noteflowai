package com.noteflowai.app.whisper

import android.util.Log
import kotlin.math.*

/**
 * Log-Mel Spectrogram computation for Whisper.
 * Matches HuggingFace WhisperFeatureExtractor exactly:
 *   - mel_scale="slaney" (piecewise linear+log)
 *   - norm="slaney" (area normalization)
 *   - log_mel="log10"
 *   - (log10 + 4) / 4 with 8dB clamping
 *
 * Input: float32 PCM samples at 16kHz mono
 * Output: FloatArray of size N_MEL * N_FRAMES (80 x 3000 = 240000 for 30s)
 */
object WhisperMelSpectrogram {

    init {
        System.loadLibrary("whisper_jni")
    }

    private external fun computeNative(pcm: FloatArray): FloatArray

    const val SAMPLE_RATE = 16000
    const val N_FFT = 400
    const val HOP_LENGTH = 160
    const val N_MEL = 80
    const val CHUNK_LENGTH = 30 // seconds
    const val N_SAMPLES = SAMPLE_RATE * CHUNK_LENGTH // 480000
    const val N_FRAMES = N_SAMPLES / HOP_LENGTH // 3000
    const val N_FREQ_BINS = N_FFT / 2 + 1 // 201

    private const val MEL_FLOOR = 1e-10f

    // Precomputed Hann window (periodic, size 400)
    private val hannWindow = FloatArray(N_FFT) { i ->
        (0.5f * (1.0f - cos(2.0 * PI * i / N_FFT))).toFloat()
    }

    // Precomputed DFT twiddle factors
    private val dftCos = FloatArray(N_FREQ_BINS * N_FFT)
    private val dftSin = FloatArray(N_FREQ_BINS * N_FFT)
    init {
        for (k in 0 until N_FREQ_BINS) {
            for (n in 0 until N_FFT) {
                val angle = 2.0 * PI * k * n / N_FFT
                dftCos[k * N_FFT + n] = cos(angle).toFloat()
                dftSin[k * N_FFT + n] = -sin(angle).toFloat()
            }
        }
    }

    // Mel filterbank: shape [N_MEL][N_FREQ_BINS]
    // HuggingFace: norm="slaney", mel_scale="slaney"
    private val melFilterbank = computeMelFilterbank()

    /**
     * Compute log-mel spectrogram from PCM float32 samples.
     * Returns FloatArray of size N_MEL * N_FRAMES.
     */
    fun compute(pcmf: FloatArray): FloatArray {
        // Use native C implementation for speed (radix-2 FFT, ~20x faster)
        return try {
            val t0 = System.currentTimeMillis()
            val result = computeNative(pcmf)
            val elapsed = System.currentTimeMillis() - t0
            Log.i("WhisperMel", "Native mel compute: ${elapsed}ms")
            result
        } catch (e: UnsatisfiedLinkError) {
            Log.w("WhisperMel", "Native mel not available, using Kotlin fallback: ${e.message}")
            computeKotlin(pcmf)
        }
    }

    private fun computeKotlin(pcmf: FloatArray): FloatArray {
        // Pad or truncate to 30 seconds
        val padded = FloatArray(N_SAMPLES)
        val copyLen = minOf(pcmf.size, N_SAMPLES)
        pcmf.copyInto(padded, 0, 0, copyLen)

        // Center reflect padding (pad_mode="reflect", pad by N_FFT//2 on each side)
        val paddedWithReflection = FloatArray(N_SAMPLES + N_FFT)
        for (i in 0 until N_FFT / 2) {
            paddedWithReflection[i] = padded[N_FFT / 2 - 1 - i]
        }
        padded.copyInto(paddedWithReflection, N_FFT / 2)
        for (i in 0 until N_FFT / 2) {
            paddedWithReflection[N_SAMPLES + N_FFT / 2 + i] = padded[N_SAMPLES - 1 - i]
        }

        val result = FloatArray(N_MEL * N_FRAMES)

        for (frame in 0 until N_FRAMES) {
            val offset = frame * HOP_LENGTH

            // Windowed FFT
            val fftReal = FloatArray(N_FFT)
            for (i in 0 until N_FFT) {
                fftReal[i] = paddedWithReflection[offset + i] * hannWindow[i]
            }

            // Direct DFT (rfft equivalent)
            val powerSpec = FloatArray(N_FREQ_BINS)
            for (k in 0 until N_FREQ_BINS) {
                var real = 0f
                var imag = 0f
                val base = k * N_FFT
                for (n in 0 until N_FFT) {
                    real += fftReal[n] * dftCos[base + n]
                    imag += fftReal[n] * dftSin[base + n]
                }
                powerSpec[k] = real * real + imag * imag
            }

            // Apply mel filterbank: mel_spec = mel_filters.T @ power_spec
            for (mel in 0 until N_MEL) {
                var melEnergy = 0f
                for (k in 0 until N_FREQ_BINS) {
                    melEnergy += powerSpec[k] * melFilterbank[mel][k]
                }
                // Clamp to mel_floor, then log10
                val clamped = max(melEnergy, MEL_FLOOR)
                result[mel * N_FRAMES + frame] = log10(clamped.toDouble()).toFloat()
            }
        }

        var rawMax = Float.MIN_VALUE
        var rawMin = Float.MAX_VALUE
        for (v in result) {
            if (v > rawMax) rawMax = v
            if (v < rawMin) rawMin = v
        }
        Log.i("WhisperMel", "Raw log10-mel: min=$rawMin max=$rawMax")

        // Clamp to max - 8 dB range
        val clampVal = rawMax - 8.0f
        for (i in result.indices) {
            if (result[i] < clampVal) result[i] = clampVal
        }

        // Normalize: (log_spec + 4.0) / 4.0
        for (i in result.indices) {
            result[i] = (result[i] + 4.0f) / 4.0f
        }

        var normMax = Float.MIN_VALUE
        var normMin = Float.MAX_VALUE
        for (v in result) {
            if (v > normMax) normMax = v
            if (v < normMin) normMin = v
        }
        Log.i("WhisperMel", "Normalized mel: min=$normMin max=$normMax")

        return result
    }

    /**
     * Slaney mel scale: linear below 1000 Hz, logarithmic above.
     * Matches transformers hertz_to_mel(freq, mel_scale="slaney").
     */
    private fun hzToMelSlaney(freq: Float): Float {
        if (freq >= 1000.0f) {
            val minLogMel = 15.0f
            val logstep = 27.0f / ln(6.4).toFloat()
            return minLogMel + ln(freq / 1000.0f) * logstep
        }
        return 3.0f * freq / 200.0f
    }

    /**
     * Slaney mel-to-hertz: linear below 15 mel, logarithmic above.
     * Matches transformers mel_to_hertz(mels, mel_scale="slaney").
     */
    private fun melToHzSlaney(mel: Float): Float {
        if (mel >= 15.0f) {
            val minLogHertz = 1000.0f
            val minLogMel = 15.0f
            val logstep = ln(6.4).toFloat() / 27.0f
            return minLogHertz * exp(logstep * (mel - minLogMel))
        }
        return 200.0f * mel / 3.0f
    }

    /**
     * Compute mel filterbank with Slaney scale and Slaney normalization.
     * Matches transformers mel_filter_bank(norm="slaney", mel_scale="slaney").
     *
     * Returns FloatArray[N_MEL][N_FREQ_BINS].
     */
    private fun computeMelFilterbank(): Array<FloatArray> {
        val fMin = 0.0f
        val fMax = SAMPLE_RATE / 2.0f  // 8000 Hz

        // Mel center points (linearly spaced in Slaney mel domain)
        val melMin = hzToMelSlaney(fMin)
        val melMax = hzToMelSlaney(fMax)
        val melPointsHz = FloatArray(N_MEL + 2) { i ->
            melToHzSlaney(melMin + i * (melMax - melMin) / (N_MEL + 1))
        }

        // FFT bin frequencies in Hz (linearly spaced)
        val fftFreqs = FloatArray(N_FREQ_BINS) { k ->
            k.toFloat() * SAMPLE_RATE / N_FFT
        }

        val filterbank = Array(N_MEL) { FloatArray(N_FREQ_BINS) }

        // Create triangular filters
        for (mel in 0 until N_MEL) {
            val lowFreq = melPointsHz[mel]
            val centerFreq = melPointsHz[mel + 1]
            val highFreq = melPointsHz[mel + 2]

            for (k in 0 until N_FREQ_BINS) {
                val freq = fftFreqs[k]
                filterbank[mel][k] = when {
                    freq <= lowFreq || freq >= highFreq -> 0f
                    freq <= centerFreq -> {
                        if (centerFreq - lowFreq > 0f) (freq - lowFreq) / (centerFreq - lowFreq) else 0f
                    }
                    else -> {
                        if (highFreq - centerFreq > 0f) (highFreq - freq) / (highFreq - centerFreq) else 0f
                    }
                }
            }
        }

        // Slaney area normalization: enorm = 2.0 / (highMel - lowMel)
        // Each filter is multiplied by 2 / (bandwidth in mel)
        for (mel in 0 until N_MEL) {
            val lowMel = hzToMelSlaney(melPointsHz[mel])
            val highMel = hzToMelSlaney(melPointsHz[mel + 2])
            val bandwidth = highMel - lowMel
            if (bandwidth > 0f) {
                val enorm = 2.0f / bandwidth
                for (k in 0 until N_FREQ_BINS) {
                    filterbank[mel][k] *= enorm
                }
            }
        }

        return filterbank
    }
}
