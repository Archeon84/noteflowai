# NoteFlow AI — Developer Setup

## Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- NDK 27.0.12077973 (installed via SDK Manager)
- CMake 3.22.1 (installed via SDK Manager)

## Building

```bash
# Debug build (no signing config needed)
./gradlew assembleDebug
```

## Release Signing

Release builds require a keystore. The signing config reads from **gradle properties** or **environment variables**.

### 1. Create a keystore

```bash
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias noteflowai
```

### 2. Set properties

Add to `local.properties` (git-ignored) or set as environment variables:

```properties
# local.properties
KEYSTORE_PATH=../keystore/release.jks
KEYSTORE_PASSWORD=your_store_password
KEY_ALIAS=noteflowai
KEY_PASSWORD=your_key_password
```

Or via environment variables:

```bash
export KEYSTORE_PATH=/path/to/release.jks
export KEYSTORE_PASSWORD=your_store_password
export KEY_ALIAS=noteflowai
export KEY_PASSWORD=your_key_password
```

### 3. Build release

```bash
./gradlew assembleRelease
```

Without properties set, the release build falls back to debug signing (usable for testing but not for Play Store).

## Google Drive Backup

The app uses manual Google Sign-In (not Firebase), so `google-services.json` is **not required**. Drive backup works out of the box with the existing `play-services-auth` dependency.

To enable Drive backup on a new Google Cloud project:

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create OAuth 2.0 Client ID (Android type)
3. Use your app's SHA-1 fingerprint: `keytool -list -v -keystore keystore/release.jks -alias noteflowai`
4. The app uses `GoogleSignInOptions.requestScopes(DriveScopes.DRIVE_FILE)` — no additional config needed in the app

## Native Libraries

The app builds whisper.cpp and llama.cpp via CMake/JNI. Ensure NDK and CMake are installed:

```
SDK Manager → SDK Tools → NDK (Side by side) → 27.0.12077973
SDK Manager → SDK Tools → CMake → 3.22.1
```
