# Voice-First RAG (TTS) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add text-to-speech output so AI responses in chat can be spoken aloud, completing the voice loop (transcription input already exists).

**Architecture:** A new `TtsManager` class wraps Android's `android.speech.tts.TextToSpeech` engine. It initializes lazily, exposes `speak(text)`, `stop()`, and `isSpeaking` state. The ChatScreen gets a speaker button on each AI message. Settings get TTS rate/pitch controls. No new dependencies — uses the built-in Android TTS framework.

**Tech Stack:** Android `android.speech.tts.TextToSpeech`, Jetpack Compose, DataStore preferences for TTS settings.

## Global Constraints

- Android minSdk 26, targetSdk 34
- Kotlin 1.9+, Compose BOM 2026.06
- No new library dependencies
- TTS runs on device — no network required
- Must handle TTS engine not installed gracefully

## File Structure

| File | Responsibility |
|------|---------------|
| `data/TtsManager.kt` | Wraps Android TextToSpeech, lifecycle-aware init/shutdown |
| `viewmodel/MainViewModel.kt:~564` | Hold TtsManager instance, expose speak/stop/isSpeaking |
| `ui/screens/chat/ChatScreen.kt:~1168` | Add speaker button per AI message |
| `ui/screens/SettingsSections.kt` | Add TTS rate/pitch sliders |
| `data/SettingsManager.kt` | Persist TTS rate/pitch values |

---

### Task 1: Create TtsManager

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/TtsManager.kt`

**Interfaces:**
- Consumes: `Context` (for TTS initialization)
- Produces: `speak(text)`, `stop()`, `isSpeaking: StateFlow<Boolean>`, `shutdown()`

- [ ] **Step 1: Create TtsManager.kt**

Create `app/src/main/java/com/noteflowai/app/data/TtsManager.kt`:

```kotlin
package com.noteflowai.app.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Wraps Android TextToSpeech for speaking AI responses aloud.
 * Initialize with context, configure rate/pitch, then call speak().
 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TtsManager"
    }

    private var tts: TextToSpeech? = null
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<String>>(emptyList())
    val availableVoices: StateFlow<List<String>> = _availableVoices.asStateFlow()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { engine ->
                val result = engine.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "English not supported, falling back to default")
                }
                engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS error: $errorCode")
                        _isSpeaking.value = false
                    }
                })

                // List available voices
                val voices = engine.voices?.map { it.name } ?: emptyList()
                _availableVoices.value = voices
                _isReady.value = true
                Log.i(TAG, "TTS initialized, ${voices.size} voices available")
            }
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    /**
     * Speak the given text. Truncates to ~3000 chars to avoid TTS buffer overflow.
     */
    fun speak(text: String, rate: Float = 1.0f, pitch: Float = 1.0f) {
        val engine = tts ?: return
        if (!_isReady.value) return

        // Truncate very long texts
        val truncated = if (text.length > 3000) {
            text.take(3000) + "... [truncated]"
        } else {
            text
        }

        engine.setSpeechRate(rate)
        engine.setPitch(pitch)

        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "ai_response_${System.currentTimeMillis()}")
        }
        engine.speak(truncated, TextToSpeech.QUEUE_FLUSH, params, "ai_response")
    }

    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _isSpeaking.value = false
        _isReady.value = false
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/TtsManager.kt
git commit -m "feat: add TtsManager wrapping Android TextToSpeech"
```

---

### Task 2: Persist TTS settings

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/SettingsManager.kt` — add ttsRate/ttsPitch/ttsEnabled preferences

**Interfaces:**
- Consumes: DataStore preferences (existing)
- Produces: `ttsEnabled: StateFlow<Boolean>`, `ttsRate: StateFlow<Float>`, `ttsPitch: StateFlow<Float>`, `setTtsEnabled(Boolean)`, `setTtsRate(Float)`, `setTtsPitch(Float)`

- [ ] **Step 1: Add TTS settings to SettingsManager**

In `SettingsManager.kt`, add after existing preference keys (near the other `dataStorePreferencesKeys`):

```kotlin
// Inside companion object or top-level keys:
private val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
private val TTS_RATE = floatPreferencesKey("tts_rate")
private val TTS_PITCH = floatPreferencesKey("tts_pitch")
```

Add StateFlow fields:

```kotlin
private val _ttsEnabled = MutableStateFlow(false)
val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

private val _ttsRate = MutableStateFlow(1.0f)
val ttsRate: StateFlow<Float> = _ttsRate.asStateFlow()

private val _ttsPitch = MutableStateFlow(1.0f)
val ttsPitch: StateFlow<Float> = _ttsPitch.asStateFlow()
```

Add setter methods:

```kotlin
suspend fun setTtsEnabled(enabled: Boolean) {
    _ttsEnabled.value = enabled
    context.dataStore.edit { it[TTS_ENABLED] = enabled }
}

suspend fun setTtsRate(rate: Float) {
    _ttsRate.value = rate
    context.dataStore.edit { it[TTS_RATE] = rate }
}

suspend fun setTtsPitch(pitch: Float) {
    _ttsPitch.value = pitch
    context.dataStore.edit { it[TTS_PITCH] = pitch }
}
```

Load from preferences in the existing `loadAll()` or init block:

```kotlin
val ttsEnabled = preferences[TTS_ENABLED] ?: false
_ttsEnabled.value = ttsEnabled
val ttsRate = preferences[TTS_RATE] ?: 1.0f
_ttsRate.value = ttsRate
val ttsPitch = preferences[TTS_PITCH] ?: 1.0f
_ttsPitch.value = ttsPitch
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/SettingsManager.kt
git commit -m "feat: add TTS rate/pitch/enabled persistence to SettingsManager"
```

---

### Task 3: Wire TtsManager into MainViewModel

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt:~564` — add TtsManager field and expose methods

**Interfaces:**
- Consumes: `TtsManager` (from Task 1), `SettingsManager.ttsRate/ttsPitch/ttsEnabled` (from Task 2)
- Produces: `fun speakAiResponse(text: String)`, `fun stopSpeaking()`, `val isSpeaking: StateFlow<Boolean>`

- [ ] **Step 1: Add TtsManager to MainViewModel**

After the existing `mediaPlayer` declaration (~line 564):

```kotlin
private var ttsManager: TtsManager? = null

val isSpeaking: StateFlow<Boolean> get() = ttsManager?.isSpeaking ?: MutableStateFlow(false)
```

- [ ] **Step 2: Initialize TtsManager in init block**

In the `init` block, after existing initialization:

```kotlin
// Initialize TTS (lazy — created on first use or when enabled)
if (settingsManager.ttsEnabled.value) {
    ttsManager = TtsManager(getApplication())
}
```

- [ ] **Step 3: Add speak/stop methods**

```kotlin
fun speakAiResponse(text: String) {
    if (!settingsManager.ttsEnabled.value) return

    // Lazy-init if needed
    if (ttsManager == null) {
        ttsManager = TtsManager(getApplication())
    }

    ttsManager?.speak(
        text = text,
        rate = settingsManager.ttsRate.value,
        pitch = settingsManager.ttsPitch.value
    )
}

fun stopSpeaking() {
    ttsManager?.stop()
}
```

- [ ] **Step 4: Shut down TtsManager on ViewModel clear**

In `onCleared()`:

```kotlin
override fun onCleared() {
    super.onCleared()
    ttsManager?.shutdown()
    // ... existing cleanup
}
```

Add import: `import com.noteflowai.app.data.TtsManager`

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "feat: wire TtsManager into MainViewModel with speak/stop"
```

---

### Task 4: Add speaker button to ChatScreen

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt:~1168` — add speaker icon on AI messages

**Interfaces:**
- Consumes: `viewModel.speakAiResponse(text)`, `viewModel.stopSpeaking()`, `viewModel.isSpeaking`
- Produces: Speaker toggle button on each AI message bubble

- [ ] **Step 1: Add speaker button in AI message rendering**

In `ChatScreen.kt`, find where AI messages are rendered (the assistant message bubble). After the message content text and before the message metadata row, add a speaker toggle:

```kotlin
// After the AI message text content:
val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()

Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.End
) {
    IconButton(
        onClick = {
            if (isSpeaking) {
                viewModel.stopSpeaking()
            } else {
                viewModel.speakAiResponse(message.content)
            }
        },
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            imageVector = if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
            contentDescription = if (isSpeaking) "Stop speaking" else "Read aloud",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

Add imports:
```kotlin
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.lifecycle.compose.collectAsStateWithLifecycle
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/chat/ChatScreen.kt
git commit -m "feat: add speaker button to AI messages in ChatScreen"
```

---

### Task 5: Add TTS settings UI

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/SettingsSections.kt` — add TTS section

**Interfaces:**
- Consumes: `settingsManager.ttsEnabled/ttsRate/ttsPitch`, `settingsManager.setTtsEnabled/setTtsRate/setTtsPitch`
- Produces: Toggle, rate slider, pitch slider in settings

- [ ] **Step 1: Add TTS section in SettingsSections**

In `SettingsSections.kt`, after the existing "Recall Reminders" toggle section, add:

```kotlin
// --- Voice Output (TTS) ---
item {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Voice Output",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        val ttsEnabled by settingsManager.ttsEnabled.collectAsStateWithLifecycle()
        val ttsRate by settingsManager.ttsRate.collectAsStateWithLifecycle()
        val ttsPitch by settingsManager.ttsPitch.collectAsStateWithLifecycle()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Read AI responses aloud")
            Switch(
                checked = ttsEnabled,
                onCheckedChange = { scope.launch { settingsManager.setTtsEnabled(it) } }
            )
        }

        if (ttsEnabled) {
            Spacer(modifier = Modifier.height(8.dp))

            Text("Speed: ${"%.1f".format(ttsRate)}x")
            Slider(
                value = ttsRate,
                onValueChange = { scope.launch { settingsManager.setTtsRate(it) } },
                valueRange = 0.5f..2.0f,
                steps = 5
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text("Pitch: ${"%.1f".format(ttsPitch)}x")
            Slider(
                value = ttsPitch,
                onValueChange = { scope.launch { settingsManager.setTtsPitch(it) } },
                valueRange = 0.5f..2.0f,
                steps = 5
            )
        }
    }
}
```

Add imports if needed:
```kotlin
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/SettingsSections.kt
git commit -m "feat: add TTS settings UI with rate/pitch sliders"
```

---

### Task 6: End-to-end test

- [ ] **Step 1: Build and install**

Run: `./gradlew installDebug`
Expected: App installs

- [ ] **Step 2: Manual verification**

1. Open Settings → Voice Output → enable TTS
2. Adjust speed and pitch sliders
3. Open Chat → send a message → AI responds
4. Tap speaker icon on AI response → verify speech plays
5. Tap again (stop icon) → verify speech stops
6. Verify icon toggles between VolumeUp and Stop while speaking
7. Disable TTS in settings → verify speaker button still shows but does nothing when tapped
8. Test with very long AI response → verify truncation works

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: voice-first RAG (TTS) complete"
```
