# Whisper.cpp Integration – Complete

The setup script `clone_whisper.ps1` has cloned the whisper.cpp repository.
The JNI code now uses the real Whisper C API.

## Steps to make it work:

1. **Run the clone script** (already done if you’re reading this after running it):
   Open PowerShell in `app/src/main/cpp/` and execute `.\clone_whisper.ps1`.

2. **Download a Whisper model** from Hugging Face:
   - `ggml-tiny.bin` (recommended for fast testing)
   - Place it into `app/src/main/assets/models/ggml-tiny.bin`.
   - You can also add `ggml-base.bin` or `ggml-small.bin` if you want to switch models in‑app.

3. **Build the project** – Android Studio will automatically run CMake and compile the native library.
   The first build may take a while because whisper.cpp contains many source files.

4. **Run the app** – press the microphone button, speak, then stop. You’ll get real transcription.

All local processing – no internet required.