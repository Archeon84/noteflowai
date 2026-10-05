#include <jni.h>
#include <stdlib.h>
#include <android/log.h>

#define TAG "QnnEnvSetup"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_noteflowai_app_whisper_WhisperQnnBridge_nativeSetupEnv(
    JNIEnv* env, jobject thiz, jstring nativeLibDir) {

    const char* libDir = env->GetStringUTFChars(nativeLibDir, nullptr);
    if (!libDir) return JNI_FALSE;

    const char* existing = getenv("ADSP_LIBRARY_PATH");
    char newPath[2048];
    if (existing && *existing) {
        snprintf(newPath, sizeof(newPath), "%s;%s", libDir, existing);
    } else {
        snprintf(newPath, sizeof(newPath), "%s", libDir);
    }

    setenv("ADSP_LIBRARY_PATH", newPath, 1);
    __android_log_print(ANDROID_LOG_INFO, TAG, "ADSP_LIBRARY_PATH: %s", getenv("ADSP_LIBRARY_PATH"));

    env->ReleaseStringUTFChars(nativeLibDir, libDir);
    return JNI_TRUE;
}

}
