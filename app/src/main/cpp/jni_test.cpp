#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstring>
#include <vector>

#define LOG_TAG "JniTest"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Test memcmp-based RIFF/WAVE header detection
extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_noteflowai_app_jni_JniTest_runNativeTests(
        JNIEnv *env, jobject thiz) {

    std::vector<std::string> results;

    // Test 1: Valid RIFF header
    {
        char header[12] = {'R','I','F','F', 0,0,0,0, 'W','A','V','E'};
        bool isRiff = (memcmp(header, "RIFF", 4) == 0);
        bool isWave = (memcmp(header + 8, "WAVE", 4) == 0);
        results.push_back(std::string("Test 1 (valid RIFF/WAVE): ") + (isRiff && isWave ? "PASS" : "FAIL"));
    }

    // Test 2: Invalid RIFF (NOT RIFF)
    {
        char header[12] = {'N','O','T',' ', 0,0,0,0, 'W','A','V','E'};
        bool isRiff = (memcmp(header, "RIFF", 4) == 0);
        results.push_back(std::string("Test 2 (invalid RIFF): ") + (!isRiff ? "PASS" : "FAIL"));
    }

    // Test 3: Valid RIFF but not WAVE
    {
        char header[12] = {'R','I','F','F', 0,0,0,0, 'X','X','X','X'};
        bool isRiff = (memcmp(header, "RIFF", 4) == 0);
        bool isWave = (memcmp(header + 8, "WAVE", 4) == 0);
        results.push_back(std::string("Test 3 (RIFF but not WAVE): ") + (isRiff && !isWave ? "PASS" : "FAIL"));
    }

    // Test 4: Chunk ID comparison (fmt )
    {
        char chunkId[4] = {'f','m','t',' '};
        bool isFmt = (memcmp(chunkId, "fmt ", 4) == 0);
        results.push_back(std::string("Test 4 (fmt chunk): ") + (isFmt ? "PASS" : "FAIL"));
    }

    // Test 5: Chunk ID comparison (data)
    {
        char chunkId[4] = {'d','a','t','a'};
        bool isData = (memcmp(chunkId, "data", 4) == 0);
        results.push_back(std::string("Test 5 (data chunk): ") + (isData ? "PASS" : "FAIL"));
    }

    // Test 6: Non-null terminated buffer safety
    {
        char buf[4] = {'R','I','F','F'}; // No null terminator
        // This should NOT read past 4 bytes
        bool isRiff = (memcmp(buf, "RIFF", 4) == 0);
        results.push_back(std::string("Test 6 (no null terminator): ") + (isRiff ? "PASS" : "FAIL"));
    }

    // Convert to Java String array
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray resultArray = env->NewObjectArray(results.size(), stringClass, nullptr);

    for (size_t i = 0; i < results.size(); i++) {
        env->SetObjectArrayElement(resultArray, i, env->NewStringUTF(results[i].c_str()));
    }

    return resultArray;
}