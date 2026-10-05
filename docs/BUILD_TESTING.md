# NoteFlowAI — Build & Test Commands

Canonical commands for building and testing NoteFlowAI (single `:app` module; Android Gradle
Plugin root at the repo root). Run everything with the Gradle wrapper from the repository root.

## Prerequisites

- JDK 17 (project targets Java/Kotlin `17`, see `app/build.gradle.kts`).
- Android SDK with `compileSdk = 35`, NDK `27.0.12077973`, CMake `3.22.1`.
  Set `sdk.dir` in `local.properties` or `ANDROID_HOME`.
- Native libs (whisper.cpp / llama.cpp) are vendored as git submodules and built by CMake
  (`app/src/main/cpp/CMakeLists.txt`) for `arm64-v8a` and `x86_64`.

## Build

```bash
# Compile debug sources (fast correctness check)
./gradlew :app:compileDebugKotlin

# Assemble a debug APK
./gradlew :app:assembleDebug

# Assemble a release APK (requires a keystore, see Signing below)
./gradlew :app:assembleRelease

# Lint (optional; a baseline suppresses pre-existing issues)
./gradlew :app:lintDebug
```

## Signing (release)

The release signing config reads from Gradle properties or environment variables. If unset,
release builds fall back to debug signing.

```bash
export KEYSTORE_PATH=/path/to/release.keystore
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

## Tests

```bash
# Full unit test suite (JVM + Robolectric)
./gradlew :app:testDebugUnitTest

# A single test class
./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.graph.AutoLinkerTest"

# A single test method
./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.FeatureFlagsTest"

# Android instrumentation tests (require a device/emulator)
./gradlew :app:connectedDebugAndroidTest
```

Notes on the unit-test setup:

- `testOptions.unitTests.isIncludeAndroidResources = true` enables Robolectric resource access.
- Room schema JSONs (`app/schemas/`) are mounted as main assets so Robolectric's
  `MigrationTestHelper` can read `<database>/<version>.json`.
- SQLCipher loads `libsqlcipher`; DAO/migration tests use Robolectric with a supported SDK (34).

## Guide-context commands

The Agentic Implementation Guide (§10 "Agent Commands") defines read-only inspection, code-review,
security-audit, and performance-audit command prompts. Those are agent-workflow instructions, not
Gradle tasks; invoke them as prompts through the coding agent rather than as build commands.
