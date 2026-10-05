# NoteFlow AI — Developer Setup & Quick-Start Guide

## ⏱️ Quick Start (< 10 Minutes)

Follow this step-by-step path to compile, install, and run NoteFlow AI on a physical device or emulator with zero troubleshooting:

1. **Open in Android Studio**:
   - Open Android Studio Hedgehog (2023.1.1) or newer.
   - Select **Open** and choose the `NoteFlowAI` repository root.
2. **Gradle & NDK Sync**:
   - Allow Gradle to sync dependencies (~2-3 minutes on first open).
   - If NDK `27.0.12077973` or CMake `3.22.1` are missing, Android Studio's SDK Manager prompt will ask to install them automatically. (Or install via `Settings > Appearance & Behavior > System Settings > Android SDK > SDK Tools`).
3. **Connect Device or Emulator**:
   - **Recommended:** Connect a physical Android device running Android 11+ (API 30+, preferably API 33+) with USB Debugging enabled. (Physical devices provide hardware OpenCL GPU acceleration for LiteRT-LM).
   - If using an Android Emulator, ensure an x86_64 image with API 30+ is running with software rendering fallback.
4. **Compile & Install**:
   - Run from Android Studio by clicking the green **Run (Play)** button, or execute in your terminal:
     ```bash
     ./gradlew installDebug
     ```
5. **Verify "What Success Looks Like"**:
   - The app launches into the interactive **NoteFlow AI Intro / Onboarding Screen**.
   - You can swipe through the feature highlights and tap **Get Started**.
6. **First AI Model Initialization**:
   - Navigate to **Settings > AI Engine**.
   - Download the on-device **Gemma 4 E2B (~1.2 GB)** model (download takes ~5–8 minutes over Wi-Fi).
   - Once downloaded, switch to **Talk to Notes** or **Talk with AI Assistant** in the Chat tab or record an offline voice note via the mic button on the home screen.

---

## 🛠️ Prerequisites

- **Android Studio**: Hedgehog (2023.1.1) or Ladybug / Koala / newer
- **JDK**: 17 (Eclipse Temurin, OpenJDK 17, or Android Studio bundled JetBrains Runtime 17)
- **NDK**: `27.0.12077973` (Side-by-side NDK)
- **CMake**: `3.22.1`

### Native Toolchain Verification
Ensure NDK and CMake are installed:
```
Android Studio → Settings → Languages & Frameworks → Android SDK → SDK Tools:
  ☑ NDK (Side by side) → version 27.0.12077973
  ☑ CMake → version 3.22.1
```

---

## 🏗️ Build Commands

```bash
# Clean build cache
./gradlew clean

# Build Debug APK (outputs to app/build/outputs/apk/debug/app-debug.apk)
./gradlew assembleDebug

# Install directly to attached ADB device
./gradlew installDebug

# Run unit test suite
./gradlew testDebugUnitTest
```

---

## 🔧 Troubleshooting & Common Setup Pitfalls

| Symptom | Cause | Solution |
| :--- | :--- | :--- |
| `CMake not found` or `C++ configuration failure` | CMake 3.22.1 not installed via SDK Manager | Go to `SDK Manager > SDK Tools > CMake`, select `Show Package Details`, check `3.22.1`, apply. |
| `ninja: command not found` / NDK mismatch | System using wrong NDK version | Ensure `local.properties` does not pin an invalid `ndk.dir`. Set `ndkVersion = "27.0.12077973"` in `app/build.gradle.kts`. |
| App crashes on launch with `dlopen failed: library "libOpenCL.so" not found` | Running on emulator without GPU compute passthrough | LiteRT automatically falls back to CPU XNNPACK. If crash occurs, run on a physical test device with vendor OpenCL drivers. |
| Model download fails or gets stuck | Network timeout or insufficient internal storage | Ensure at least 4 GB free internal storage space. Partial files can be cleared at `/sdcard/Android/data/com.noteflowai.app/cache/models/`. |
| Out of Memory (OOM) during build | Gradle daemon heap limit | Ensure `gradle.properties` has `org.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8`. |

---

## 🔑 Release Signing

Release builds require a keystore. The signing configuration reads from `local.properties` or environment variables:

### 1. Create a Keystore
```bash
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias noteflowai
```

### 2. Set Properties
Add to `local.properties` (git-ignored):
```properties
KEYSTORE_PATH=../keystore/release.jks
KEYSTORE_PASSWORD=your_store_password
KEY_ALIAS=noteflowai
KEY_PASSWORD=your_key_password
```
Or export as environment variables:
```bash
export KEYSTORE_PATH=/path/to/release.jks
export KEYSTORE_PASSWORD=your_store_password
export KEY_ALIAS=noteflowai
export KEY_PASSWORD=your_key_password
```

### 3. Build Release APK
```bash
./gradlew assembleRelease
```
*Note: Without these properties set, the release build falls back to debug signing.*

---

## ☁️ Google Drive Backup Setup

The app utilizes manual Google Sign-In with OAuth 2.0 (Play Services Auth), meaning `google-services.json` is **not required**.

To configure Google Drive backup on a new Google Cloud project:
1. Open [Google Cloud Console](https://console.cloud.google.com/).
2. Create an **OAuth 2.0 Client ID** (Application type: Android).
3. Provide your package name (`com.noteflowai.app`) and SHA-1 fingerprint (`keytool -list -v -keystore keystore/release.jks -alias noteflowai`).
4. The app requests `DriveScopes.DRIVE_FILE` scope — no extra app changes needed.
