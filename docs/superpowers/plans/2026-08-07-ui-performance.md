# UI Performance & Responsiveness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate UI jank, reduce recomposition scope, and move heavy computation off the main thread so the app feels smooth and snappy.

**Architecture:** Fix performance at three layers: (1) data layer — debounce/filter off main thread, lazy repository init, parallelize index rebuilds; (2) ViewModel — memoize expensive derived state, scope state reads to narrow composable targets; (3) UI — split monolithic composables, add `contentType`/`remember` guards, isolate timer/visualizer recomposition.

**Tech Stack:** Jetpack Compose, Kotlin Coroutines, StateFlow, Material3, Gson (index persistence)

## Global Constraints

- Android API 26+ (minSdk)
- Kotlin 2.0+, Compose BOM current
- No new third-party dependencies (all fixes use existing Compose/coroutine APIs)
- All changes must pass `./gradlew assembleDebug`
- Existing navigation structure (NoteFlowApp AnimatedContent) must not change
- NoteFile is `@Immutable` data class — do not remove annotation

---

## Task 1: Debounce and dispatch `filteredNotes` off the UI thread

**The single highest-impact fix.** Currently `filteredNotes` combines 4 flows and runs BM25 search + fuzzy O(m·n) matching + sort on every keystroke on the composing thread.

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt:554-588`

**Interfaces:**
- Consumes: `savedNotes`, `searchQuery`, `selectedCategory`, `sortOption` (existing StateFlows)
- Produces: `filteredNotes: StateFlow<List<NoteFile>>` (same signature, different internals)

- [ ] **Step 1: Add debounce to searchQuery before filteredNotes**

Replace the current `filteredNotes` block (lines 554-588) with a version that debounces search input and runs the heavy work on `Dispatchers.Default`:

```kotlin
@OptIn(kotlinx.coroutines.FlowPreview::class)
val filteredNotes: StateFlow<List<NoteFile>> = run {
    val debouncedQuery = _searchQuery
        .debounce(300)
        .distinctUntilChanged()

    combine(savedNotes, debouncedQuery, selectedCategory, sortOption) { notes, query, cat, sort ->
        // Fuzzy match fileNames from NoteSearchIndex for ranking boost
        val fuzzyMatchFileNames = if (query.isNotBlank() && noteSearchIndex.indexSize() > 0) {
            noteSearchIndex.search(query, maxResults = 20).map { it.fileName }.toSet()
        } else emptySet()

        notes.filter { note ->
            (cat == "All" || note.category == cat) &&
            (note.fileName.contains(query, ignoreCase = true) ||
             note.preview.contains(query, ignoreCase = true) ||
             fuzzyMatchFileNames.contains(note.fileName) ||
             (query.isNotBlank() && com.noteflowai.app.data.search.FuzzyMatcher.matches(query, note.fileName)))
        }.let { filtered ->
            when (sort) {
                "Name (A-Z)" -> filtered.sortedBy { it.fileName.lowercase() }
                "Name (Z-A)" -> filtered.sortedByDescending { it.fileName.lowercase() }
                "Date (Oldest)" -> filtered.sortedWith(
                    compareByDescending<NoteFile> { it.pinned }.thenBy { it.lastModifiedEpoch }
                )
                else -> {
                    if (query.isNotBlank()) {
                        filtered.sortedWith(
                            compareByDescending<NoteFile> { it.pinned }
                                .thenByDescending { com.noteflowai.app.data.search.FuzzyMatcher.score(query, it.fileName) }
                        )
                    } else {
                        filtered
                    }
                }
            }
        }
    }.flowOn(Dispatchers.Default)
     .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
```

Key changes:
- `_searchQuery` is debounced 300ms before entering the combine (same cadence as `observeSearchSuggestions`)
- `distinctUntilChanged()` prevents identical queries from re-triggering
- `.flowOn(Dispatchers.Default)` moves the entire filter+sort pipeline off the main thread

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "perf: debounce filteredNotes search and dispatch to Default thread"
```

---

## Task 2: Lazy-init NoteRepository off the main thread

`NoteRepository` constructor calls `refreshNotesList()` synchronously, which reads every note file via `file.readText()` + Gson deserialization on the main thread. This blocks first frame.

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/NoteRepository.kt`

**Interfaces:**
- Consumes: nothing (self-contained)
- Produces: `notesFlow: StateFlow<List<NoteFile>>` (same API, lazy init)

- [ ] **Step 1: Make refreshNotesList lazy and init on IO**

Read the current `NoteRepository.kt` to find the constructor and `refreshNotesList`. The constructor currently calls `refreshNotesList()` synchronously. Change to:

1. Remove the `refreshNotesList()` call from `init {}`
2. Expose an `init()` suspend function that callers invoke from a coroutine
3. In `init()`, run `refreshNotesList()` on `Dispatchers.IO`
4. Start `_notes` with an empty list so the flow emits immediately

```kotlin
class NoteRepository(private val context: Context) {
    private val _notes = MutableStateFlow<List<NoteFile>>(emptyList())
    val notesFlow: StateFlow<List<NoteFile>> = _notes.asStateFlow()

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val notesDir = File(context.filesDir, "notes").also { it.mkdirs() }

    /** Call from a coroutine after construction — loads note metadata off the main thread. */
    suspend fun init() {
        withContext(Dispatchers.IO) {
            refreshNotesList()
        }
    }

    private fun refreshNotesList() {
        // ... existing implementation unchanged ...
    }
}
```

- [ ] **Step 2: Update MainViewModel to call init()**

In `MainViewModel`, find where `noteRepository` is constructed (line 56: `private val noteRepository = NoteRepository(application)`). Add a coroutine call after construction:

```kotlin
init {
    viewModelScope.launch(Dispatchers.IO) {
        noteRepository.init()
    }
    // ... existing init blocks ...
}
```

Note: The existing `notesFlow` collectors in `init` already handle the case where the list is initially empty — they'll receive the populated list once `init()` completes.

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/NoteRepository.kt app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "perf: lazy-init NoteRepository off the main thread"
```

---

## Task 3: Scope `recordingSeconds` to isolate timer recomposition

`recordingSeconds` is read at the top of `RecordScreen`, causing the entire screen (LazyColumn, visualizer, layout) to recompose every 500ms during recording.

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/RecordScreen.kt`

**Interfaces:**
- Consumes: `viewModel.recordingSeconds` (existing StateFlow<Int>)
- Produces: isolated timer Text composable

- [ ] **Step 1: Extract timer into a dedicated composable**

Find where `recordingSeconds` is read at the top level of `RecordScreen` and where the timer `Text` is rendered. Extract the timer display into a small composable that reads `recordingSeconds` internally:

```kotlin
@Composable
private fun RecordingTimer(viewModel: MainViewModel) {
    val seconds by viewModel.recordingSeconds.collectAsStateWithLifecycle()
    val formatted = String.format("%02d:%02d", seconds / 60, seconds % 60)
    Text(
        text = formatted,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}
```

Then in `RecordScreen`, replace the inline timer `Text` with `RecordingTimer(viewModel)`, and remove the top-level `val seconds by viewModel.recordingSeconds.collectAsStateWithLifecycle()` if it exists only for the timer.

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/RecordScreen.kt
git commit -m "perf: isolate recordingSeconds to prevent whole-screen recomposition"
```

---

## Task 4: Memoize `getConnectedNotes` and move suggestion analysis off main thread

Two issues in `NoteDetailContent`: (1) `getConnectedNotes()` runs in composition without `remember`, re-executing on every recomposition; (2) `analyzeCurrentNote` runs CPU-heavy suggestion analysis on `Dispatchers.Main` via `viewModelScope.launch`.

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NoteDetailContent.kt:439`
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt:3266-3278`

**Interfaces:**
- Consumes: `viewModel.getConnectedNotes(fileName)`, `viewModel.analyzeCurrentNote(...)`
- Produces: memoized `connectedNotes` val, background analysis

- [ ] **Step 1: Memoize getConnectedNotes**

Find `val connectedNotes = viewModel.getConnectedNotes(note.fileName)` inside the collapsible details section. Wrap it in `remember`:

```kotlin
val connectedNotes = remember(note.fileName) { viewModel.getConnectedNotes(note.fileName) }
```

- [ ] **Step 2: Move analyzeCurrentNote to Dispatchers.Default**

In `MainViewModel.kt`, find `fun analyzeCurrentNote` (line 3266). Change `viewModelScope.launch` to `viewModelScope.launch(Dispatchers.Default)`:

```kotlin
fun analyzeCurrentNote(noteFileName: String, content: String, tags: List<String>) {
    suggestionJob?.cancel()
    suggestionJob = viewModelScope.launch(Dispatchers.Default) {
        kotlinx.coroutines.delay(500)  // debounce 500ms
        val suggestions = noteSuggestionEngine.analyze(
            noteFileName = noteFileName,
            content = content,
            tags = tags,
            allNotes = savedNotes.value
        )
        _noteSuggestions.value = suggestions
    }
}
```

The `_noteSuggestions.value = suggestions` assignment is safe from `Dispatchers.Default` because `MutableStateFlow.value` is thread-safe. This moves the CPU-heavy `noteSuggestionEngine.analyze()` off the main thread.

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/NoteDetailContent.kt app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "perf: memoize getConnectedNotes and move suggestion analysis to Default dispatcher"
```

---

## Task 5: Memoize ChatMessage computed properties

`ChatMessage.wordCount` and `estimatedTokenCount` are computed properties that run `split("\\s+")` on every access. In `ChatScreen`, these are read per visible bubble on every recomposition.

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/data/chat/AiChatModels.kt` (or wherever `ChatMessage` is defined)

**Interfaces:**
- Consumes: `ChatMessage.content`
- Produces: precomputed `wordCount` and `estimatedTokenCount` fields

- [ ] **Step 1: Add precomputed fields to ChatMessage**

Read the current `ChatMessage` data class. Add `wordCount` and `estimatedTokenCount` as constructor parameters with default values computed from `content`:

```kotlin
@Immutable
data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachmentUri: String? = null,
    val attachmentType: String? = null,
    val images: List<String>? = null,
    val ragSources: List<RagSource>? = null,
    // Precomputed fields — avoid regex split per recomposition
    val wordCount: Int = if (content.isBlank()) 0 else content.trim().split("\\s+".toRegex()).size,
    val estimatedTokenCount: Int = (wordCount * 4 / 3) // ~1.33 tokens per word
)
```

Remove the existing `val wordCount get() = ...` and `val estimatedTokenCount get() = ...` computed property definitions if they exist.

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/chat/AiChatModels.kt
git commit -m "perf: precompute ChatMessage wordCount and estimatedTokenCount"
```

---

## Task 6: Parallelize index rebuilds

The `notesFlow` collector chains 6 independent rebuilds (search index, embeddings, graph, concept graph, temporal index, recall prediction) serially under one `withContext(Default)`. Each should run independently.

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt:646-685`

**Interfaces:**
- Consumes: `noteRepository.notesFlow` (existing)
- Produces: parallel rebuild on notesFlow change

- [ ] **Step 1: Replace serial chain with parallel launches**

Find the `noteRepository.notesFlow.debounce(2000).collect` block. Replace the single `withContext(Dispatchers.Default)` with parallel `launch` calls:

```kotlin
@OptIn(kotlinx.coroutines.FlowPreview::class)
viewModelScope.launch {
    noteRepository.notesFlow
        .debounce(2000)
        .collect { notes ->
            // Search index (CPU-bound, has fingerprint guard)
            launch(Dispatchers.Default) {
                noteSearchIndex.rebuildIndex(notes)
                noteSearchIndex.persistIndex(getApplication())
            }
            // Embeddings (network I/O, batched)
            launch(Dispatchers.IO) {
                try { rebuildEmbeddings(notes) } catch (e: Exception) {
                    Log.w("MainViewModel", "Embedding rebuild failed: ${e.message}")
                }
            }
            // Auto-linker graph
            launch(Dispatchers.Default) {
                try {
                    val newGraph = autoLinker.computeGraph(notes)
                    noteGraphRepository.updateGraph(newGraph, getApplication())
                } catch (e: Exception) {
                    Log.w("MainViewModel", "Graph rebuild failed: ${e.message}")
                }
            }
            // Concept graph
            launch(Dispatchers.Default) {
                try {
                    conceptGraphRepository.rebuildGraph(notes, getApplication())
                } catch (e: Exception) {
                    Log.w("MainViewModel", "Concept graph rebuild failed: ${e.message}")
                }
            }
            // Temporal index
            launch(Dispatchers.Default) {
                try {
                    temporalIndex.rebuildIndex(notes, getApplication())
                } catch (e: Exception) {
                    Log.w("MainViewModel", "Temporal index rebuild failed: ${e.message}")
                }
            }
            // Proactive recall
            launch(Dispatchers.Default) {
                try {
                    val suggestions = recallPredictor.findRecallSuggestions()
                    _recallSuggestions.value = suggestions
                } catch (e: Exception) {
                    Log.w("MainViewModel", "Recall prediction failed: ${e.message}")
                }
            }
        }
}
```

Each rebuild now runs concurrently. Embeddings use `Dispatchers.IO` since they make network calls; all others use `Dispatchers.Default` for CPU work.

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "perf: parallelize index rebuilds on notesFlow change"
```

---

## Task 7: Cache `isReducedMotionEnabled` in NoteFlowApp

`transitionSpec` in `AnimatedContent` reads `isReducedMotionEnabled()` (which calls `Settings.Global.getFloat`) on every transition frame.

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/NoteFlowApp.kt` (transitionSpec area)

**Interfaces:**
- Consumes: `isReducedMotionEnabled()` from `MotionUtils`
- Produces: cached boolean

- [ ] **Step 1: Hoist the value to a remember**

Find the `AnimatedContent` block and its `transitionSpec`. Add a `remember`-ed value before the `AnimatedContent`:

```kotlin
val isReducedMotion = remember { isReducedMotionEnabled() }
```

Then use `isReducedMotion` inside the `transitionSpec` lambda instead of calling `isReducedMotionEnabled()` directly. The value is stable for the app's lifetime (user doesn't change it mid-session), so `remember` without keys is correct.

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/NoteFlowApp.kt
git commit -m "perf: cache isReducedMotionEnabled in AnimatedContent transitionSpec"
```

---

## Task 8: Fix RecallReminderReceiver ANR risk

`RecallReminderReceiver.onReceive()` runs index loads and prediction synchronously on the main thread without `goAsync()`, risking ANR.

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/service/RecallReminderReceiver.kt`

**Interfaces:**
- Consumes: `NoteSearchIndex`, `RecallPredictor` (existing)
- Produces: non-blocking receiver

- [ ] **Step 1: Wrap onReceive in goAsync + coroutine**

Read the current `RecallReminderReceiver.kt`. Wrap the body in `goAsync()` with a coroutine:

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            // ... existing index load + prediction logic ...
        } catch (e: Exception) {
            Log.w("RecallReminderReceiver", "Failed: ${e.message}")
        } finally {
            pendingResult.finish()
        }
    }
}
```

Add the necessary imports: `androidx.core.content.goAsync`, `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.launch`.

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/service/RecallReminderReceiver.kt
git commit -m "perf: wrap RecallReminderReceiver in goAsync to prevent ANR"
```

---

## Task 9: Reduce AudioVisualizer list allocation churn

Every 100ms during recording, a new `MutableList` is created via `toMutableList()`, an element is added, and if >50 elements one is removed. This creates GC pressure during long recordings.

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt` (audio level emission, ~line 1093-1100)

**Interfaces:**
- Consumes: `audioRecorder` callback audio chunks
- Produces: `_audioLevels` StateFlow (same API, reduced allocation)

- [ ] **Step 1: Use a pre-allocated circular buffer**

Replace the `toMutableList()` pattern with a pre-allocated ring buffer:

```kotlin
// Add as a field alongside other recording state
private val audioLevelBuffer = FloatArray(50)
private var audioLevelWritePos = 0
private var audioLevelCount = 0

// In the audioRecorder callback, replace the currentLevels logic with:
val idx = audioLevelWritePos % 50
audioLevelBuffer[idx] = rms
audioLevelWritePos++
if (audioLevelCount < 50) audioLevelCount++

// Emit a snapshot (copy only what's needed)
val snapshot = if (audioLevelCount < 50) {
    audioLevelBuffer.copyOf(audioLevelCount)
} else {
    // Rotate: elements are at positions [writePos..writePos+count) mod 50
    FloatArray(50) { i -> audioLevelBuffer[(audioLevelWritePos - audioLevelCount + i + 100) % 50] }
}
_audioLevels.value = snapshot.toList()
```

This eliminates the per-emit `MutableList` allocation and `removeAt(0)` O(n) shift.

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "perf: use circular buffer for audio levels to reduce GC churn"
```

---

## Verification

After all tasks are complete:

1. `./gradlew assembleDebug` — compile check
2. `./gradlew installDebug` — deploy to device
3. Test: open Notes screen with 50+ notes, type in search — should feel instant, no lag
4. Test: open a note, switch to edit mode, type rapidly — no stutter from suggestion analysis
5. Test: start recording — timer and visualizer should be smooth, no jank
6. Test: open chat with long conversation — scroll should be smooth
7. Test: navigate between screens — transitions should be fluid
8. Test: cold start — app should show content faster (no main-thread file I/O blocking first frame)
