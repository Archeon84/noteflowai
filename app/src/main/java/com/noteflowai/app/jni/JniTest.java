package com.noteflowai.app.jni;

import android.util.Log;

public class JniTest {
    static {
        System.loadLibrary("whisper_jni");
        // jni_test library is only available in debug builds
        try {
            System.loadLibrary("jni_test");
        } catch (UnsatisfiedLinkError e) {
            Log.w("JniTest", "jni_test library not available (release build)");
        }
    }

    /**
     * Runs native JNI tests for WAV header parsing logic.
     * Returns array of test result strings.
     */
    public static native String[] runNativeTests();

    /**
     * Runs all native tests and logs results.
     * Returns true if all tests pass.
     */
    public static boolean runAndLogTests() {
        try {
            String[] results = runNativeTests();
            boolean allPass = true;
            for (String result : results) {
                boolean pass = result.contains("PASS");
                if (!pass) allPass = false;
                Log.i("JniTest", result);
            }
            return allPass;
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.w("JniTest", "Native tests not available: " + e.getMessage());
            return false;
        }
    }
}