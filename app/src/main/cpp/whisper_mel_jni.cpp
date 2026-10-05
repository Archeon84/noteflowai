#include <jni.h>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <android/log.h>

#define LOG_TAG "WhisperMel"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static const int SAMPLE_RATE = 16000;
static const int N_FFT = 400;
static const int HOP_LENGTH = 160;
static const int N_MEL = 80;
static const int CHUNK_LENGTH = 30;
static const int N_SAMPLES = SAMPLE_RATE * CHUNK_LENGTH; // 480000
static const int N_FRAMES = N_SAMPLES / HOP_LENGTH;     // 3000
static const int N_FREQ_BINS = N_FFT / 2 + 1;           // 201

static float hannWindow[N_FFT];
static float melFilterbank[N_MEL][N_FREQ_BINS];
// Precomputed DFT twiddle factors: dftCos[k*N_FFT+n] = cos(2*pi*k*n/N_FFT)
static float dftCos[N_FREQ_BINS * N_FFT];
static float dftSin[N_FREQ_BINS * N_FFT];

static void init_tables() {
    static bool initialized = false;
    if (initialized) return;
    initialized = true;

    // Hann window (periodic, size 400)
    for (int i = 0; i < N_FFT; i++) {
        hannWindow[i] = 0.5f * (1.0f - cosf(2.0f * M_PI * i / N_FFT));
    }

    // Precompute DFT twiddle factors
    for (int k = 0; k < N_FREQ_BINS; k++) {
        for (int n = 0; n < N_FFT; n++) {
            float angle = 2.0f * M_PI * k * n / N_FFT;
            dftCos[k * N_FFT + n] = cosf(angle);
            dftSin[k * N_FFT + n] = -sinf(angle);
        }
    }

    // Slaney mel scale
    auto hzToMelSlaney = [](float freq) -> float {
        if (freq >= 1000.0f) {
            float minLogMel = 15.0f;
            float logstep = 27.0f / logf(6.4f);
            return minLogMel + logf(freq / 1000.0f) * logstep;
        }
        return 3.0f * freq / 200.0f;
    };

    auto melToHzSlaney = [](float mel) -> float {
        if (mel >= 15.0f) {
            float minLogHertz = 1000.0f;
            float minLogMel = 15.0f;
            float logstep = logf(6.4f) / 27.0f;
            return minLogHertz * expf(logstep * (mel - minLogMel));
        }
        return 200.0f * mel / 3.0f;
    };

    float melMin = hzToMelSlaney(0.0f);
    float melMax = hzToMelSlaney(8000.0f);
    float melPointsHz[N_MEL + 2];
    for (int i = 0; i < N_MEL + 2; i++) {
        melPointsHz[i] = melToHzSlaney(melMin + i * (melMax - melMin) / (N_MEL + 1));
    }

    float fftFreqs[N_FREQ_BINS];
    for (int k = 0; k < N_FREQ_BINS; k++) {
        fftFreqs[k] = (float)k * SAMPLE_RATE / N_FFT;
    }

    for (int m = 0; m < N_MEL; m++) {
        float lowFreq = melPointsHz[m];
        float centerFreq = melPointsHz[m + 1];
        float highFreq = melPointsHz[m + 2];

        for (int k = 0; k < N_FREQ_BINS; k++) {
            float freq = fftFreqs[k];
            if (freq <= lowFreq || freq >= highFreq) {
                melFilterbank[m][k] = 0.0f;
            } else if (freq <= centerFreq) {
                melFilterbank[m][k] = (centerFreq - lowFreq > 0.0f) ?
                    (freq - lowFreq) / (centerFreq - lowFreq) : 0.0f;
            } else {
                melFilterbank[m][k] = (highFreq - centerFreq > 0.0f) ?
                    (highFreq - freq) / (highFreq - centerFreq) : 0.0f;
            }
        }

        float lowMel = hzToMelSlaney(melPointsHz[m]);
        float highMel = hzToMelSlaney(melPointsHz[m + 2]);
        float bandwidth = highMel - lowMel;
        if (bandwidth > 0.0f) {
            float enorm = 2.0f / bandwidth;
            for (int k = 0; k < N_FREQ_BINS; k++) {
                melFilterbank[m][k] *= enorm;
            }
        }
    }

    LOGI("init_tables: DFT twiddle factors and mel filterbank computed (N_FFT=%d, N_FREQ_BINS=%d)", N_FFT, N_FREQ_BINS);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_noteflowai_app_whisper_WhisperMelSpectrogram_computeNative(
    JNIEnv *env, jobject thiz, jfloatArray pcmArray) {

    init_tables();

    int pcmLen = env->GetArrayLength(pcmArray);
    float *pcm = env->GetFloatArrayElements(pcmArray, nullptr);
    if (pcm == nullptr) {
        jclass oom = env->FindClass("java/lang/OutOfMemoryError");
        env->ThrowNew(oom, "Failed to get float array elements (OOM)");
        return nullptr;
    }

    // Allocate large arrays on HEAP to avoid stack overflow
    // (stack is only ~1MB on Android, these total ~5MB)
    float *padded = (float *)calloc(N_SAMPLES, sizeof(float));
    float *paddedWithReflection = (float *)malloc((N_SAMPLES + N_FFT) * sizeof(float));
    float *result = (float *)malloc(N_MEL * N_FRAMES * sizeof(float));
    float fftReal[N_FFT];
    float powerSpec[N_FREQ_BINS];

    if (!padded || !paddedWithReflection || !result) {
        free(padded); free(paddedWithReflection); free(result);
        env->ReleaseFloatArrayElements(pcmArray, pcm, JNI_ABORT);
        jclass oom = env->FindClass("java/lang/OutOfMemoryError");
        env->ThrowNew(oom, "Failed to allocate work buffers");
        return nullptr;
    }

    int copyLen = (pcmLen < N_SAMPLES) ? pcmLen : N_SAMPLES;
    memcpy(padded, pcm, copyLen * sizeof(float));

    env->ReleaseFloatArrayElements(pcmArray, pcm, JNI_ABORT);

    for (int i = 0; i < N_FFT / 2; i++) {
        paddedWithReflection[i] = padded[N_FFT / 2 - 1 - i];
    }
    memcpy(paddedWithReflection + N_FFT / 2, padded, N_SAMPLES * sizeof(float));
    for (int i = 0; i < N_FFT / 2; i++) {
        paddedWithReflection[N_SAMPLES + N_FFT / 2 + i] = padded[N_SAMPLES - 1 - i];
    }

    for (int frame = 0; frame < N_FRAMES; frame++) {
        int offset = frame * HOP_LENGTH;

        // Windowed DFT input
        for (int i = 0; i < N_FFT; i++) {
            fftReal[i] = paddedWithReflection[offset + i] * hannWindow[i];
        }

        // Direct DFT (same as Kotlin but in C: ~10-20x faster)
        for (int k = 0; k < N_FREQ_BINS; k++) {
            float real = 0.0f;
            float imag = 0.0f;
            const float *cosRow = dftCos + k * N_FFT;
            const float *sinRow = dftSin + k * N_FFT;
            for (int n = 0; n < N_FFT; n++) {
                real += fftReal[n] * cosRow[n];
                imag += fftReal[n] * sinRow[n];
            }
            powerSpec[k] = real * real + imag * imag;
        }

        // Apply mel filterbank + log10
        for (int m = 0; m < N_MEL; m++) {
            float melEnergy = 0.0f;
            for (int k = 0; k < N_FREQ_BINS; k++) {
                melEnergy += powerSpec[k] * melFilterbank[m][k];
            }
            float clamped = (melEnergy > 1e-10f) ? melEnergy : 1e-10f;
            result[m * N_FRAMES + frame] = log10f(clamped);
        }
    }

    float rawMax = -1e30f, rawMin = 1e30f;
    for (int i = 0; i < N_MEL * N_FRAMES; i++) {
        if (result[i] > rawMax) rawMax = result[i];
        if (result[i] < rawMin) rawMin = result[i];
    }
    LOGI("Raw log10-mel: min=%.1f max=%.1f", rawMin, rawMax);

    // Clamp to max - 8 dB range
    float clampVal = rawMax - 8.0f;
    for (int i = 0; i < N_MEL * N_FRAMES; i++) {
        if (result[i] < clampVal) result[i] = clampVal;
    }

    // Normalize: (x + 4) / 4
    for (int i = 0; i < N_MEL * N_FRAMES; i++) {
        result[i] = (result[i] + 4.0f) / 4.0f;
    }

    float normMax = -1e30f, normMin = 1e30f;
    for (int i = 0; i < N_MEL * N_FRAMES; i++) {
        if (result[i] > normMax) normMax = result[i];
        if (result[i] < normMin) normMin = result[i];
    }
    LOGI("Normalized mel: min=%.3f max=%.3f", normMin, normMax);

    jfloatArray outArray = env->NewFloatArray(N_MEL * N_FRAMES);
    env->SetFloatArrayRegion(outArray, 0, N_MEL * N_FRAMES, result);

    // Free heap-allocated work buffers
    free(padded);
    free(paddedWithReflection);
    free(result);

    return outArray;
}
