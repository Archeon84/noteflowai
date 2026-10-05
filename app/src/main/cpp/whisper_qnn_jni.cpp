#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <cstring>
#include <cmath>
#include <array>

#include "ort_headers/onnxruntime_c_api.h"

#define TAG "WhisperQnnCpp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define TOKEN_EOT 50256
#define TOKEN_SOT 50257
#define TOKEN_TRANSCRIBE 50358
#define TOKEN_EN 50259
#define TOKEN_ZH 50360
#define TOKEN_JA 50361
#define TOKEN_KO 50362
#define TOKEN_MS 50377
#define MAX_TOKENS 200

typedef const OrtApiBase* (*GetApiBaseFn)();

static const OrtApi* g_api = nullptr;
static OrtEnv* g_env = nullptr;
static OrtSession* g_encoder = nullptr;
static OrtSession* g_decoder = nullptr;
static OrtAllocator* g_alloc = nullptr;
static bool g_inited = false;

static bool chk(OrtStatus* s, const char* ctx) {
    if (s) {
        LOGE("%s: %d: %s", ctx, g_api->GetErrorCode(s), g_api->GetErrorMessage(s));
        g_api->ReleaseStatus(s);
        return false;
    }
    return true;
}

static std::vector<int64_t> tensor_dims(OrtValue* v) {
    OrtTensorTypeAndShapeInfo* info = nullptr;
    g_api->GetTensorTypeAndShape(v, &info);
    size_t nd = 0;
    g_api->GetDimensionsCount(info, &nd);
    std::vector<int64_t> d(nd);
    g_api->GetDimensions(info, d.data(), nd);
    g_api->ReleaseTensorTypeAndShapeInfo(info);
    return d;
}

static std::string join_path(const std::string& dir, const std::string& file) {
    if (dir.empty()) return file;
    if (dir.back() == '/' || dir.back() == '\\') return dir + file;
    return dir + "/" + file;
}

static OrtSession* make_session(const char* model, const char* ep_lib, const char* htp_backend) {
    OrtSessionOptions* so = nullptr;
    if (!chk(g_api->CreateSessionOptions(&so), "CreateSO")) return nullptr;
    g_api->SetSessionGraphOptimizationLevel(so, ORT_ENABLE_ALL);
    g_api->SetIntraOpNumThreads(so, 4);

    LOGI("Registering QNN EP library from: %s", ep_lib);
    OrtStatus* s = g_api->RegisterExecutionProviderLibrary(g_env, "QNNExecutionProvider", ep_lib);
    if (!chk(s, "RegEP")) { g_api->ReleaseSessionOptions(so); return nullptr; }

    const OrtEpDevice* const* devs = nullptr;
    size_t ndev = 0;
    s = g_api->GetEpDevices(g_env, &devs, &ndev);
    if (!chk(s, "GetDevs")) { g_api->ReleaseSessionOptions(so); return nullptr; }

    LOGI("EP devices: %zu", ndev);
    const OrtEpDevice* qnn_dev = nullptr;
    for (size_t i = 0; i < ndev; i++) {
        const char* n = g_api->EpDevice_EpName(devs[i]);
        LOGI("  [%zu] %s", i, n ? n : "?");
        if (n && strstr(n, "QNN")) qnn_dev = devs[i];
    }
    if (!qnn_dev) {
        LOGE("No QNN device found");
        g_api->ReleaseSessionOptions(so);
        return nullptr;
    }

    const char* k[] = {"backend_path"};
    const char* v[] = {htp_backend};
    s = g_api->SessionOptionsAppendExecutionProvider_V2(so, g_env, &qnn_dev, 1, k, v, 1);
    if (!chk(s, "AppendEP")) { g_api->ReleaseSessionOptions(so); return nullptr; }

    OrtSession* sess = nullptr;
    s = g_api->CreateSession(g_env, model, so, &sess);
    g_api->ReleaseSessionOptions(so);
    if (!chk(s, "CreateSess")) return nullptr;
    LOGI("Session OK: %s", model);
    return sess;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_noteflowai_app_whisper_WhisperQnnBridge_nativeInit(
    JNIEnv* env, jobject thiz,
    jstring enc_path, jstring dec_path, jstring native_lib_dir) {

    if (g_inited) return JNI_TRUE;

    const char* ep = env->GetStringUTFChars(enc_path, nullptr);
    const char* dp = env->GetStringUTFChars(dec_path, nullptr);
    const char* lib_dir = env->GetStringUTFChars(native_lib_dir, nullptr);

    std::string ep_lib = join_path(lib_dir, "libonnxruntime_providers_qnn.so");
    std::string htp_backend = join_path(lib_dir, "libQnnHtp.so");

    LOGI("=== QNN NPU Init ===");
    LOGI("Enc model: %s", ep);
    LOGI("Dec model: %s", dp);
    LOGI("EP library: %s", ep_lib.c_str());
    LOGI("HTP backend: %s", htp_backend.c_str());

    const char* existing = getenv("ADSP_LIBRARY_PATH");
    std::string new_path = std::string(lib_dir);
    if (existing && *existing) {
        new_path += ";";
        new_path += existing;
    }
    setenv("ADSP_LIBRARY_PATH", new_path.c_str(), 1);
    LOGI("ADSP_LIBRARY_PATH: %s", getenv("ADSP_LIBRARY_PATH"));

    void* h = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_GLOBAL);
    if (!h) {
        LOGE("dlopen libonnxruntime.so failed: %s", dlerror());
        return JNI_FALSE;
    }

    auto getApiBase = (GetApiBaseFn)dlsym(h, "OrtGetApiBase");
    if (!getApiBase) {
        LOGE("dlsym OrtGetApiBase failed: %s", dlerror());
        return JNI_FALSE;
    }

    g_api = getApiBase()->GetApi(ORT_API_VERSION);
    if (!g_api) { LOGE("No ORT API"); return JNI_FALSE; }
    LOGI("ORT API v%d", ORT_API_VERSION);

    if (!chk(g_api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "WQ", &g_env), "Env")) return JNI_FALSE;
    if (!chk(g_api->GetAllocatorWithDefaultOptions(&g_alloc), "Alloc")) return JNI_FALSE;

    g_encoder = make_session(ep, ep_lib.c_str(), htp_backend.c_str());
    if (!g_encoder) { LOGE("Encoder fail"); return JNI_FALSE; }

    g_decoder = make_session(dp, ep_lib.c_str(), htp_backend.c_str());
    if (!g_decoder) {
        LOGE("Decoder fail");
        g_api->ReleaseSession(g_encoder); g_encoder = nullptr;
        return JNI_FALSE;
    }

    g_inited = true;
    LOGI("=== QNN NPU Ready ===");

    env->ReleaseStringUTFChars(enc_path, ep);
    env->ReleaseStringUTFChars(dec_path, dp);
    env->ReleaseStringUTFChars(native_lib_dir, lib_dir);
    return JNI_TRUE;
}

JNIEXPORT jintArray JNICALL
Java_com_noteflowai_app_whisper_WhisperQnnBridge_nativeTranscribe(
    JNIEnv* env, jobject thiz, jfloatArray mel_data, jint lang_token) {

    if (!g_encoder || !g_decoder) return nullptr;

    jfloat* mel = env->GetFloatArrayElements(mel_data, nullptr);
    jsize mel_len = env->GetArrayLength(mel_data);

    const int64_t enc_shape[] = {1, 80, 3000};
    OrtValue* enc_in = nullptr;
    g_api->CreateTensorWithDataAsOrtValue(
        g_alloc->Info(g_alloc), mel, (size_t)mel_len,
        enc_shape, 3, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &enc_in);

    char* enc_in_name = nullptr;
    g_api->SessionGetInputName(g_encoder, 0, g_alloc, &enc_in_name);

    size_t n_out = 0;
    g_api->SessionGetOutputCount(g_encoder, &n_out);
    std::vector<OrtValue*> enc_out(n_out, nullptr);
    std::vector<std::string> oname(n_out);
    std::vector<const char*> oname_c(n_out);
    for (size_t i = 0; i < n_out; i++) {
        char* n = nullptr;
        g_api->SessionGetOutputName(g_encoder, i, g_alloc, &n);
        oname[i] = n;
        oname_c[i] = oname[i].c_str();
        g_alloc->Free(g_alloc, n);
    }

    OrtStatus* s = g_api->Run(g_encoder, nullptr,
        &enc_in_name, (const OrtValue* const*)&enc_in, 1,
        oname_c.data(), n_out, enc_out.data());

    g_api->ReleaseValue(enc_in);
    g_alloc->Free(g_alloc, enc_in_name);
    env->ReleaseFloatArrayElements(mel_data, mel, JNI_ABORT);

    if (!chk(s, "RunEncoder")) { LOGE("Encoder failed"); return nullptr; }
    LOGI("Encoder OK, %zu outputs", n_out);

    struct CacheData {
        const float* data;
        std::vector<int64_t> dims;
        ONNXTensorElementDataType dtype;
        OrtValue* owner;
    };
    std::vector<CacheData> cross_caches(n_out);
    for (size_t i = 0; i < n_out; i++) {
        float* data = nullptr;
        g_api->GetTensorMutableData(enc_out[i], (void**)&data);
        auto d = tensor_dims(enc_out[i]);
        OrtTensorTypeAndShapeInfo* ti = nullptr;
        g_api->GetTensorTypeAndShape(enc_out[i], &ti);
        ONNXTensorElementDataType dt;
        g_api->GetTensorElementType(ti, &dt);
        g_api->ReleaseTensorTypeAndShapeInfo(ti);
        cross_caches[i] = {data, d, dt, enc_out[i]};
    }

    std::vector<int64_t> input_ids = {TOKEN_SOT, (int64_t)lang_token, TOKEN_TRANSCRIBE};
    std::vector<int64_t> all_tokens;

    for (int step = 0; step < MAX_TOKENS; step++) {
        size_t num_inputs = 0;
        g_api->SessionGetInputCount(g_decoder, &num_inputs);

        std::vector<OrtValue*> ins(num_inputs, nullptr);
        std::vector<std::string> iname(num_inputs);
        std::vector<const char*> iname_c(num_inputs);

        for (size_t i = 0; i < num_inputs; i++) {
            char* n = nullptr;
            g_api->SessionGetInputName(g_decoder, i, g_alloc, &n);
            iname[i] = n;
            iname_c[i] = iname[i].c_str();
            g_alloc->Free(g_alloc, n);

            const std::string& nm = iname[i];

            if (nm == "input_ids") {
                std::vector<int32_t> ids(input_ids.begin(), input_ids.end());
                int64_t sh[] = {1, (int64_t)ids.size()};
                g_api->CreateTensorWithDataAsOrtValue(
                    g_alloc->Info(g_alloc), ids.data(), ids.size(),
                    sh, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32, &ins[i]);
            } else if (nm == "attention_mask") {
                int64_t total = step + (int64_t)input_ids.size() + 1;
                std::vector<float> mask(200, 0.0f);
                for (int64_t j = total; j < 200; j++) mask[j] = -INFINITY;
                int64_t sh[] = {1, 1, 1, 200};
                g_api->CreateTensorWithDataAsOrtValue(
                    g_alloc->Info(g_alloc), mask.data(), mask.size(),
                    sh, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &ins[i]);
            } else if (nm == "position_ids") {
                int32_t pos = step;
                int64_t sh[] = {1};
                g_api->CreateTensorWithDataAsOrtValue(
                    g_alloc->Info(g_alloc), &pos, 1,
                    sh, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32, &ins[i]);
            } else if (nm.find("k_cache_cross") != std::string::npos ||
                       nm.find("v_cache_cross") != std::string::npos) {
                bool is_k = nm[0] == 'k';
                int idx = nm.back() - '0';
                if (idx >= 0 && idx < (int)cross_caches.size()) {
                    int ci = is_k ? idx * 2 : idx * 2 + 1;
                    if (ci < (int)cross_caches.size()) {
                        size_t elem_count = 1;
                        for (auto d : cross_caches[ci].dims) elem_count *= d;
                        g_api->CreateTensorWithDataAsOrtValue(
                            g_alloc->Info(g_alloc),
                            (void*)cross_caches[ci].data,
                            elem_count,
                            cross_caches[ci].dims.data(), (size_t)cross_caches[ci].dims.size(),
                            cross_caches[ci].dtype, &ins[i]);
                    }
                }
            } else if (nm.find("k_cache_self") != std::string::npos && nm.find("_in") != std::string::npos) {
                int64_t sh[] = {6, 1, 64, 199};
                static float zeros_k[6 * 1 * 64 * 199] = {};
                g_api->CreateTensorWithDataAsOrtValue(
                    g_alloc->Info(g_alloc), zeros_k, sizeof(zeros_k)/sizeof(float),
                    sh, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16, &ins[i]);
            } else if (nm.find("v_cache_self") != std::string::npos && nm.find("_in") != std::string::npos) {
                int64_t sh[] = {6, 1, 199, 64};
                static float zeros_v[6 * 1 * 199 * 64] = {};
                g_api->CreateTensorWithDataAsOrtValue(
                    g_alloc->Info(g_alloc), zeros_v, sizeof(zeros_v)/sizeof(float),
                    sh, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16, &ins[i]);
            } else {
                OrtTypeInfo* ti = nullptr;
                g_api->SessionGetInputTypeInfo(g_decoder, i, &ti);
                const OrtTensorTypeAndShapeInfo* tsi = nullptr;
                g_api->CastTypeInfoToTensorInfo(ti, &tsi);
                size_t nd = 0;
                g_api->GetDimensionsCount(tsi, &nd);
                std::vector<int64_t> dims(nd);
                g_api->GetDimensions(tsi, dims.data(), nd);
                ONNXTensorElementDataType dt;
                g_api->GetTensorElementType(tsi, &dt);
                g_api->ReleaseTypeInfo(ti);
                size_t total = 1;
                for (auto d : dims) total *= d;
                std::vector<float> z(total, 0.0f);
                g_api->CreateTensorWithDataAsOrtValue(
                    g_alloc->Info(g_alloc), z.data(), total,
                    dims.data(), nd, dt, &ins[i]);
            }
        }

        size_t num_out = 0;
        g_api->SessionGetOutputCount(g_decoder, &num_out);
        std::vector<OrtValue*> outs(num_out, nullptr);
        std::vector<std::string> o_name(num_out);
        std::vector<const char*> o_name_c(num_out);
        for (size_t i = 0; i < num_out; i++) {
            char* n = nullptr;
            g_api->SessionGetOutputName(g_decoder, i, g_alloc, &n);
            o_name[i] = n;
            o_name_c[i] = o_name[i].c_str();
            g_alloc->Free(g_alloc, n);
        }

        s = g_api->Run(g_decoder, nullptr,
            iname_c.data(), (const OrtValue* const*)ins.data(), num_inputs,
            o_name_c.data(), num_out, outs.data());

        for (auto& v : ins) if (v) g_api->ReleaseValue(v);

        if (!chk(s, "RunDecoder")) break;

        float* logits = nullptr;
        g_api->GetTensorMutableData(outs[0], (void**)&logits);
        auto ld = tensor_dims(outs[0]);
        int64_t vocab = ld.size() > 1 ? ld[1] : 51865;

        int best = 0;
        for (int64_t i = 1; i < vocab; i++) {
            if (logits[i] > logits[best]) best = i;
        }

        all_tokens.push_back(best);
        input_ids = {(int64_t)best};

        for (auto& v : outs) if (v) g_api->ReleaseValue(v);

        if (best == TOKEN_EOT) break;
    }

    for (auto& v : enc_out) if (v) g_api->ReleaseValue(v);

    jintArray result = env->NewIntArray((jsize)all_tokens.size());
    if (result) env->SetIntArrayRegion(result, 0, (jsize)all_tokens.size(), (const jint*)all_tokens.data());
    LOGI("Done: %zu tokens", all_tokens.size());
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_noteflowai_app_whisper_WhisperQnnBridge_nativeRelease(
    JNIEnv* env, jobject thiz) {
    if (g_encoder) { g_api->ReleaseSession(g_encoder); g_encoder = nullptr; }
    if (g_decoder) { g_api->ReleaseSession(g_decoder); g_decoder = nullptr; }
    if (g_alloc) { g_api->ReleaseAllocator(g_alloc); g_alloc = nullptr; }
    if (g_env) {
        g_api->UnregisterExecutionProviderLibrary(g_env, "QNNExecutionProvider");
        g_api->ReleaseEnv(g_env); g_env = nullptr;
    }
    g_api = nullptr;
    g_inited = false;
    LOGI("Released");
    return JNI_TRUE;
}

}
