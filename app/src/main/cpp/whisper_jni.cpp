#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <mutex>
#include <thread>
#include <algorithm>
#include <cmath>

#include "whisper.h"
#include <android/log.h>

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static struct whisper_context* g_ctx = nullptr;
static std::mutex g_mutex;
static int g_n_threads = 0;

// WAV loader – validates 16kHz mono 16-bit PCM
static std::vector<float> load_wav(const std::string& fname) {
    std::ifstream file(fname, std::ios::binary);
    if (!file.is_open()) {
        LOGW("load_wav: cannot open file: %s", fname.c_str());
        return {};
    }

    // Get file size
    file.seekg(0, std::ios::end);
    uint32_t fileSize = (uint32_t)file.tellg();
    file.seekg(0, std::ios::beg);
    LOGI("load_wav: file=%s size=%u", fname.c_str(), fileSize);

    if (fileSize < 44) {
        LOGW("load_wav: file too small (%u bytes)", fileSize);
        return {};
    }

    // Read RIFF header (12 bytes)
    char header[12];
    file.read(header, 12);
    if (file.gcount() != 12) return {};
    if (memcmp(header, "RIFF", 4) != 0) {
        LOGW("load_wav: not RIFF header");
        return {};
    }
    if (memcmp(header + 8, "WAVE", 4) != 0) {
        LOGW("load_wav: not WAVE format");
        return {};
    }

    int16_t numChannels = 0;
    uint32_t sampleRate = 0;
    int16_t bitsPerSample = 16;
    uint32_t dataSize = 0;
    bool dataFound = false;

    // Scan chunks until we find "data"
    while (file.good() && !file.eof()) {
        char chunkId[4];
        file.read(chunkId, 4);
        if (file.gcount() != 4) break;

        uint32_t chunkSize = 0;
        file.read(reinterpret_cast<char*>(&chunkSize), 4);
        if (file.gcount() != 4) break;

        if (memcmp(chunkId, "fmt ", 4) == 0) {
            if (chunkSize >= 16) {
                char fmt[16];
                file.read(fmt, 16);
                if (file.gcount() != 16) break;
                numChannels  = *reinterpret_cast<int16_t*>(fmt + 2);
                sampleRate   = *reinterpret_cast<uint32_t*>(fmt + 4);
                bitsPerSample = *reinterpret_cast<int16_t*>(fmt + 14);
                if (chunkSize > 16) file.ignore(chunkSize - 16);
            } else {
                file.ignore(chunkSize);
            }
        } else if (memcmp(chunkId, "data", 4) == 0) {
            dataSize = chunkSize;
            dataFound = true;
            break;
        } else {
            file.ignore(chunkSize);
        }
    }

    LOGI("load_wav: channels=%d rate=%u bits=%d dataSize=%u dataFound=%d",
         numChannels, sampleRate, bitsPerSample, dataSize, dataFound);

    if (!dataFound || bitsPerSample != 16) return {};
    if (sampleRate != 16000 || numChannels != 1) return {};
    if (dataSize == 0) return {};

    // Read PCM data
    uint32_t numSamples = dataSize / 2;
    std::vector<int16_t> pcm16(numSamples);

    // Read in a loop to handle partial reads
    uint32_t totalRead = 0;
    while (totalRead < numSamples) {
        uint32_t remaining = numSamples - totalRead;
        file.read(reinterpret_cast<char*>(pcm16.data() + totalRead), remaining * 2);
        uint32_t bytesRead = (uint32_t)file.gcount();
        if (bytesRead == 0) break;
        totalRead += bytesRead / 2;
    }

    LOGI("load_wav: samples=%u totalRead=%u", numSamples, totalRead);

    if (totalRead == 0) return {};

    // Check signal energy
    double sumSq = 0.0;
    int16_t maxVal = 0;
    for (size_t i = 0; i < totalRead; ++i) {
        int16_t s = pcm16[i];
        if (s < 0) s = -s;
        if (s > maxVal) maxVal = s;
        sumSq += (double)pcm16[i] * pcm16[i];
    }
    double rms = sqrt(sumSq / totalRead);
    LOGI("load_wav: maxSample=%d rms=%.1f (silence threshold ~100)", maxVal, rms);

    std::vector<float> pcmf(totalRead);
    for (size_t i = 0; i < totalRead; ++i) {
        pcmf[i] = pcm16[i] / 32768.0f;
    }

    // Normalize audio: boost quiet recordings to use full dynamic range
    // Find actual max absolute sample in float domain
    float fmax = 0.0f;
    for (size_t i = 0; i < pcmf.size(); ++i) {
        float a = pcmf[i] < 0 ? -pcmf[i] : pcmf[i];
        if (a > fmax) fmax = a;
    }
    LOGI("load_wav: float max=%.6f rms_before_norm check", fmax);

    // If max amplitude is below 0.5 (50%), normalize to ~0.7 (70%)
    // This boosts quiet recordings without clipping loud ones
    if (fmax > 0.0f && fmax < 0.5f) {
        float scale = 0.7f / fmax;  // boost to 70% of full scale
        LOGI("load_wav: normalizing audio by %.1fx (max was %.4f)", scale, fmax);
        for (size_t i = 0; i < pcmf.size(); ++i) {
            pcmf[i] *= scale;
            // Soft limiter to prevent overflow without harmonic distortion
            float x = pcmf[i];
            if (x > 1.0f) pcmf[i] = 1.0f - (1.0f / (x + 1.0f));
            else if (x < -1.0f) pcmf[i] = -1.0f + (1.0f / (-x + 1.0f));
        }
    }

    return pcmf;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_noteflowai_app_whisper_WhisperBridge_initialize(
    JNIEnv *env,
    jobject /* this */,
    jstring modelFilePath,
    jint nThreads) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    const char* path = env->GetStringUTFChars(modelFilePath, nullptr);
    LOGI("initialize: loading model from %s, threads=%d", path, nThreads);

    g_n_threads = (nThreads > 0) ? nThreads : std::max(1, (int)std::thread::hardware_concurrency());

    whisper_context_params cparams = whisper_context_default_params();
    // Enable flash attention for faster attention computation on supported hardware
    cparams.flash_attn = true;
    // Use GPU/NPU via ggml backend if available
    cparams.use_gpu = true;

    g_ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelFilePath, path);

    LOGI("initialize: result g_ctx=%p, threads=%d", (void*)g_ctx, g_n_threads);
    return g_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_noteflowai_app_whisper_WhisperBridge_transcribe(
    JNIEnv *env,
    jobject /* this */,
    jstring audioFilePath,
    jstring language,
    jboolean transcribe,
    jstring initialPrompt) {

    if (!g_ctx) {
        return env->NewStringUTF("[Error: Whisper engine not initialized. Load a model first.]");
    }

    const char* audioPath = env->GetStringUTFChars(audioFilePath, nullptr);
    const char* langCode = env->GetStringUTFChars(language, nullptr);
    const char* promptStr = initialPrompt ? env->GetStringUTFChars(initialPrompt, nullptr) : nullptr;

    LOGI("transcribe: path=%s lang=%s translate=%d prompt=%s", audioPath, langCode, !transcribe,
         promptStr ? promptStr : "(none)");

    std::vector<float> pcmf = load_wav(audioPath);
    env->ReleaseStringUTFChars(audioFilePath, audioPath);

    if (pcmf.empty()) {
        LOGW("transcribe: load_wav returned empty");
        env->ReleaseStringUTFChars(language, langCode);
        if (promptStr) env->ReleaseStringUTFChars(initialPrompt, promptStr);
        return env->NewStringUTF("[Error: Could not read audio file. Ensure it is a valid 16kHz Mono WAV.]");
    }

    if (pcmf.size() < 4800) {
        env->ReleaseStringUTFChars(language, langCode);
        if (promptStr) env->ReleaseStringUTFChars(initialPrompt, promptStr);
        return env->NewStringUTF("[Error: Audio too short (less than 0.3 seconds).]");
    }

    std::lock_guard<std::mutex> lock(g_mutex);

    // Determine the language to use for transcription
    bool needDetect = (langCode == nullptr || langCode[0] == '\0');
    std::string detectedLang = "en"; // fallback

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    if (needDetect) {
        // Use lightweight auto-detect instead of full inference pass
        int langId = whisper_lang_auto_detect(g_ctx, 0, g_n_threads, nullptr);
        const char* langStr = whisper_lang_str(langId);
        if (langStr) detectedLang = langStr;
        LOGI("transcribe: detected language id=%d str='%s'", langId, detectedLang.c_str());
    }

    // Transcribe with explicit language
    params.translate = !transcribe;
    params.language = needDetect ? detectedLang.c_str() : langCode;
    params.detect_language = false;  // always false — we handle detection ourselves
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;

    // Greedy: evaluate only 1 candidate per step (default is 5 — wasteful)
    params.greedy.best_of = 1;

    // Thread count — use pre-configured thread count
    params.n_threads = g_n_threads;

    // Accuracy / quality settings
    params.no_speech_thold = 0.6f;
    params.logprob_thold  = -1.0f;
    params.entropy_thold  = 2.4f;
    params.max_initial_ts = 1.0f;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.no_context = false;             // allow cross-segment context for continuity
    params.single_segment = false;         // allow multiple segments for better segmentation
    params.max_tokens = 0;                 // no limit on tokens per segment

    // Set initial prompt if provided
    if (promptStr && promptStr[0] != '\0') {
        params.initial_prompt = promptStr;
        LOGI("transcribe: using initial_prompt='%s'", promptStr);
    }

    LOGI("transcribe: pcmf.size=%zu lang='%s' translate=%d threads=%d",
         pcmf.size(), params.language, params.translate, params.n_threads);

    int ret = whisper_full(g_ctx, params, pcmf.data(), pcmf.size());
    if (ret != 0) {
        LOGW("transcribe: whisper_full returned %d", ret);
        env->ReleaseStringUTFChars(language, langCode);
        if (promptStr) env->ReleaseStringUTFChars(initialPrompt, promptStr);
        return env->NewStringUTF("[Error: Whisper processing failed. The audio may be corrupted or unsupported.]");
    }

    const int n_segments = whisper_full_n_segments(g_ctx);
    LOGI("transcribe: n_segments=%d", n_segments);

    std::string result;
    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(g_ctx, i);
        float no_speech_prob = whisper_full_get_segment_no_speech_prob(g_ctx, i);
        LOGI("transcribe: segment[%d] no_speech_prob=%.3f text='%s'", i, no_speech_prob, text);
        result += text;
        if (i < n_segments - 1) result += " ";
    }

    LOGI("transcribe: result='%s'", result.c_str());
    env->ReleaseStringUTFChars(language, langCode);
    if (promptStr) env->ReleaseStringUTFChars(initialPrompt, promptStr);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_noteflowai_app_whisper_WhisperBridge_release(
    JNIEnv *env,
    jobject /* this */) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_com_noteflowai_app_whisper_WhisperBridge_diagnoseWav(
    JNIEnv *env,
    jobject /* this */,
    jstring audioFilePath) {

    const char* audioPath = env->GetStringUTFChars(audioFilePath, nullptr);

    std::ifstream file(audioPath, std::ios::binary);
    if (!file.is_open()) {
        env->ReleaseStringUTFChars(audioFilePath, audioPath);
        return env->NewStringUTF("ERROR: Cannot open file");
    }

    file.seekg(0, std::ios::end);
    uint32_t fileSize = (uint32_t)file.tellg();
    file.seekg(0, std::ios::beg);

    char header[12];
    file.read(header, 12);
    if (file.gcount() != 12) {
        env->ReleaseStringUTFChars(audioFilePath, audioPath);
        return env->NewStringUTF("ERROR: File too small for RIFF header");
    }

    bool isRiff = (memcmp(header, "RIFF", 4) == 0);
    bool isWave = (memcmp(header + 8, "WAVE", 4) == 0);

    int16_t numChannels = 0;
    uint32_t sampleRate = 0;
    int16_t bitsPerSample = 0;
    uint32_t dataSize = 0;
    bool dataFound = false;

    while (file.good() && !file.eof()) {
        char chunkId[4];
        file.read(chunkId, 4);
        if (file.gcount() != 4) break;

        uint32_t chunkSize = 0;
        file.read(reinterpret_cast<char*>(&chunkSize), 4);
        if (file.gcount() != 4) break;

        if (memcmp(chunkId, "fmt ", 4) == 0) {
            if (chunkSize >= 16) {
                char fmt[16];
                file.read(fmt, 16);
                if (file.gcount() != 16) break;
                numChannels  = *reinterpret_cast<int16_t*>(fmt + 2);
                sampleRate   = *reinterpret_cast<uint32_t*>(fmt + 4);
                bitsPerSample = *reinterpret_cast<int16_t*>(fmt + 14);
                if (chunkSize > 16) file.ignore(chunkSize - 16);
            } else {
                file.ignore(chunkSize);
            }
        } else if (memcmp(chunkId, "data", 4) == 0) {
            dataSize = chunkSize;
            dataFound = true;
            break;
        } else {
            file.ignore(chunkSize);
        }
    }

    std::string diag = "File: " + std::to_string(fileSize) + " bytes\n";
    diag += "RIFF: " + std::string(isRiff ? "yes" : "NO") + "\n";
    diag += "WAVE: " + std::string(isWave ? "yes" : "NO") + "\n";
    diag += "Channels: " + std::to_string(numChannels) + "\n";
    diag += "Sample Rate: " + std::to_string(sampleRate) + " Hz\n";
    diag += "Bits/Sample: " + std::to_string(bitsPerSample) + "\n";
    diag += "Data Chunk: " + std::string(dataFound ? "found" : "NOT FOUND") + "\n";
    diag += "Data Size: " + std::to_string(dataSize) + " bytes\n";

    if (dataFound && dataSize > 0) {
        uint32_t numSamples = dataSize / 2;
        diag += "Samples: " + std::to_string(numSamples) + "\n";
        diag += "Duration: " + std::to_string((float)numSamples / sampleRate) + "s\n";

        // Read first few samples to check energy
        std::vector<int16_t> check(std::min(numSamples, (uint32_t)16000));
        file.read(reinterpret_cast<char*>(check.data()), check.size() * 2);
        uint32_t nRead = (uint32_t)file.gcount() / 2;

        double sumSq = 0;
        int16_t maxVal = 0;
        for (uint32_t i = 0; i < nRead; ++i) {
            int16_t s = check[i];
            if (s < 0) s = -s;
            if (s > maxVal) maxVal = s;
            sumSq += (double)check[i] * check[i];
        }
        double rms = sqrt(sumSq / nRead);
        diag += "Max Sample: " + std::to_string(maxVal) + "\n";
        diag += "RMS: " + std::to_string(rms) + "\n";

        // Check first 16 bytes as hex
        file.seekg(44, std::ios::beg);
        char pcmCheck[16];
        file.read(pcmCheck, 16);
        diag += "First PCM hex: ";
        for (int i = 0; i < 16; i++) {
            char buf[4];
            snprintf(buf, sizeof(buf), "%02x", (unsigned char)pcmCheck[i]);
            diag += buf;
            diag += " ";
        }
    }

    env->ReleaseStringUTFChars(audioFilePath, audioPath);
    return env->NewStringUTF(diag.c_str());
}

} // extern "C"
