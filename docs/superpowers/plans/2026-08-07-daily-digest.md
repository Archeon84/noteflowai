# Daily Digest Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate a personalized daily brief from the user's notes — recent activity, open questions, stale concepts, and hidden connections — delivered as a notification and viewable in-app.

**Architecture:** A `DailyDigestManager` assembles the digest by combining data from `TemporalIndex.getRecentConcepts()`, `RecallPredictor.findRecallSuggestions()`, `ConceptGraphRepository.getRelatedConcepts()`, and optional `OnDeviceAiManager.summarize()` per note. A `DailyDigestReceiver` BroadcastReceiver fires daily via AlarmManager, builds the notification, and stores the digest to disk. A new `DailyDigestScreen` composable displays the full digest in-app. All building blocks exist; this wires them together.

**Tech Stack:** Android AlarmManager + BroadcastReceiver (same pattern as RecallReminderManager), existing data layer components, Jetpack Compose Material3 for UI.

## Global Constraints

- Android minSdk 26, targetSdk 34
- Kotlin 1.9+, Compose BOM 2026.06
- No new dependencies — reuses existing RecallReminderManager/Receiver pattern
- Digest generated entirely on-device
- Digest persisted as JSON in `filesDir/digest/` for in-app viewing
- Default delivery: 8:00 AM daily

## File Structure

| File | Responsibility |
|------|---------------|
| `data/digest/DailyDigestManager.kt` | Assembles digest data from existing indexes |
| `data/digest/DailyDigest.kt` | Data classes for digest sections |
| `data/digest/DigestStorage.kt` | Persists/loads digest JSON to disk |
| `service/DailyDigestReceiver.kt` | BroadcastReceiver that triggers digest generation + notification |
| `service/DailyDigestScheduler.kt` | AlarmManager scheduling (mirrors RecallReminderManager) |
| `ui/screens/DailyDigestScreen.kt` | In-app digest viewer |
| `viewmodel/MainViewModel.kt` | Expose `dailyDigest` StateFlow |
| `ui/screens/HomeScreen.kt` | Add "Daily Digest" quick action |
| `ui/navigation/NoteFlowNavigation.kt` | Add DIGEST route |
| `AndroidManifest.xml` | Register DailyDigestReceiver |
| `ui/screens/SettingsSections.kt` | Add digest toggle + time picker |

---

### Task 1: Create digest data classes

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/digest/DailyDigest.kt`

**Interfaces:**
- Consumes: Nothing (pure data)
- Produces: `DailyDigest` data class consumed by storage, UI, and notification

- [ ] **Step 1: Create DailyDigest.kt**

Create `app/src/main/java/com/noteflowai/app/data/digest/DailyDigest.kt`:

```kotlin
package com.noteflowai.app.data.digest

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * A daily brief assembled from the user's notes, concepts, and recall data.
 */
data class DailyDigest(
    val generatedAt: Long = System.currentTimeMillis(),
    val recentActivity: List<RecentItem> = emptyList(),
    val openQuestions: List<OpenQuestion> = emptyList(),
    val staleConcepts: List<StaleItem> = emptyList(),
    val hiddenConnections: List<ConnectionItem> = emptyList()
) {
    data class RecentItem(
        val noteTitle: String,
        val concepts: List<String>,
        val summary: String
    )

    data class OpenQuestion(
        val concept: String,
        val detail: String,
        val fromNote: String
    )

    data class StaleItem(
        val concept: String,
        val daysSinceLastSeen: Long,
        val relatedNotes: List<String>
    )

    data class ConnectionItem(
        val conceptA: String,
        val conceptB: String,
        val strength: Float,
        val reason: String
    )

    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): DailyDigest = Gson().fromJson(json, DailyDigest::class.java)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (Gson already used elsewhere in project)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/digest/DailyDigest.kt
git commit -m "feat: add DailyDigest data classes"
```

---

### Task 2: Create DigestStorage

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/digest/DigestStorage.kt`

**Interfaces:**
- Consumes: `Context` (for filesDir)
- Produces: `save(digest)`, `loadLatest()`, `loadAll()`

- [ ] **Step 1: Create DigestStorage.kt**

Create `app/src/main/java/com/noteflowai/app/data/digest/DigestStorage.kt`:

```kotlin
package com.noteflowai.app.data.digest

import android.content.Context
import android.util.Log
import java.io.File

/**
 * File-based storage for daily digests in filesDir/digest/.
 * Keeps the last 7 days.
 */
object DigestStorage {

    private const val TAG = "DigestStorage"
    private const val DIR_NAME = "digest"
    private const val MAX_DAYS = 7

    private fun getDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun save(context: Context, digest: DailyDigest) {
        val dir = getDir(context)
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(digest.generatedAt))
        val file = File(dir, "digest_$dateStr.json")
        file.writeText(digest.toJson())
        Log.i(TAG, "Saved digest for $dateStr")

        // Cleanup old digests
        cleanupOldDigests(dir)
    }

    fun loadLatest(context: Context): DailyDigest? {
        val dir = getDir(context)
        val files = dir.listFiles()
            ?.filter { it.name.startsWith("digest_") && it.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?: return null

        return files.firstOrNull()?.let {
            try {
                DailyDigest.fromJson(it.readText())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse digest: ${e.message}")
                null
            }
        }
    }

    fun loadAll(context: Context): List<DailyDigest> {
        val dir = getDir(context)
        return dir.listFiles()
            ?.filter { it.name.startsWith("digest_") && it.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?.mapNotNull { file ->
                try {
                    DailyDigest.fromJson(file.readText())
                } catch (e: Exception) {
                    null
                }
            }
            ?: emptyList()
    }

    private fun cleanupOldDigests(dir: File) {
        val files = dir.listFiles()
            ?.filter { it.name.startsWith("digest_") }
            ?.sortedByDescending { it.name }
            ?: return

        if (files.size > MAX_DAYS) {
            files.drop(MAX_DAYS).forEach { it.delete() }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/digest/DigestStorage.kt
git commit -m "feat: add DigestStorage for daily digest persistence"
```

---

### Task 3: Create DailyDigestManager

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/digest/DailyDigestManager.kt`

**Interfaces:**
- Consumes: `TemporalIndex`, `ConceptGraphRepository`, `RecallPredictor`, `NoteSearchIndex`
- Produces: `suspend fun generateDigest(): DailyDigest`

- [ ] **Step 1: Create DailyDigestManager.kt**

Create `app/src/main/java/com/noteflowai/app/data/digest/DailyDigestManager.kt`:

```kotlin
package com.noteflowai.app.data.digest

import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.search.NoteSearchIndex
import com.noteflowai.app.data.search.RecallPredictor
import com.noteflowai.app.data.temporal.TemporalIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Assembles a DailyDigest from existing data layer components.
 * Runs entirely on-device.
 */
class DailyDigestManager(
    private val temporalIndex: TemporalIndex,
    private val conceptGraph: ConceptGraphRepository,
    private val searchIndex: NoteSearchIndex
) {

    suspend fun generateDigest(): DailyDigest = withContext(Dispatchers.IO) {
        val recentConcepts = temporalIndex.getRecentConcepts(noteCount = 10)
        val predictor = RecallPredictor(temporalIndex, conceptGraph)
        val recallSuggestions = predictor.findRecallSuggestions()

        // Recent activity: top concepts from recent notes
        val recentActivity = recentConcepts.map { (concept, count) ->
            val evolution = temporalIndex.getEvolution(concept)
            DailyDigest.RecentItem(
                noteTitle = concept.replaceFirstChar { it.uppercase() },
                concepts = listOf(concept),
                summary = evolution?.summary ?: "Appeared in $count notes"
            )
        }

        // Open questions: UNRESOLVED_IDEA and FOLLOW_UP suggestions
        val openQuestions = recallSuggestions
            .filter { it.type == RecallPredictor.RecallType.UNRESOLVED_IDEA || it.type == RecallPredictor.RecallType.FOLLOW_UP }
            .map { suggestion ->
                DailyDigest.OpenQuestion(
                    concept = suggestion.concept,
                    detail = suggestion.detail,
                    fromNote = suggestion.relatedNotes.firstOrNull() ?: "unknown"
                )
            }

        // Stale concepts: STALE_CONCEPT suggestions
        val staleConcepts = recallSuggestions
            .filter { it.type == RecallPredictor.RecallType.STALE_CONCEPT }
            .map { suggestion ->
                DailyDigest.StaleItem(
                    concept = suggestion.concept,
                    daysSinceLastSeen = suggestion.stalenessDays,
                    relatedNotes = suggestion.relatedNotes
                )
            }

        // Hidden connections: concepts with high co-occurrence not obviously related
        val allConcepts = conceptGraph.getAllConcepts()
        val hiddenConnections = mutableListOf<DailyDigest.ConnectionItem>()
        for (concept in allConcepts.take(20)) {
            val related = conceptGraph.getRelatedConcepts(concept.canonicalForm, maxResults = 3)
            for ((relatedConcept, strength) in related) {
                if (strength > 0.5f && hiddenConnections.size < 5) {
                    hiddenConnections.add(
                        DailyDigest.ConnectionItem(
                            conceptA = concept.displayForm,
                            conceptB = relatedConcept,
                            strength = strength,
                            reason = "Co-occurs in ${concept.noteCount} notes"
                        )
                    )
                }
            }
        }

        DailyDigest(
            recentActivity = recentActivity,
            openQuestions = openQuestions,
            staleConcepts = staleConcepts,
            hiddenConnections = hiddenConnections.distinctBy { "${it.conceptA}-${it.conceptB}" }
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/digest/DailyDigestManager.kt
git commit -m "feat: add DailyDigestManager assembling digest from existing indexes"
```

---

### Task 4: Create DailyDigestScheduler (AlarmManager)

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/service/DailyDigestScheduler.kt`

**Interfaces:**
- Consumes: `Context` (for AlarmManager)
- Produces: `schedule(hour, minute)`, `cancel()`

- [ ] **Step 1: Create DailyDigestScheduler.kt**

Create `app/src/main/java/com/noteflowai/app/service/DailyDigestScheduler.kt`:

```kotlin
package com.noteflowai.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

/**
 * Schedules daily digest generation via AlarmManager.
 * Mirrors the RecallReminderManager pattern.
 */
class DailyDigestScheduler(private val context: Context) {

    companion object {
        private const val TAG = "DailyDigestScheduler"
        private const val REQUEST_CODE = 3
        const val ACTION_GENERATE_DIGEST = "com.noteflowai.app.GENERATE_DAILY_DIGEST"
    }

    fun schedule(hour: Int = 8, minute: Int = 0) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DailyDigestReceiver::class.java).apply {
            action = ACTION_GENERATE_DIGEST
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )

        Log.i(TAG, "Scheduled daily digest at $hour:${"%02d".format(minute)}")
    }

    fun cancel() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DailyDigestReceiver::class.java).apply {
            action = ACTION_GENERATE_DIGEST
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "Cancelled daily digest")
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/service/DailyDigestScheduler.kt
git commit -m "feat: add DailyDigestScheduler with AlarmManager daily trigger"
```

---

### Task 5: Create DailyDigestReceiver

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/service/DailyDigestReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml` — register receiver

**Interfaces:**
- Consumes: `DailyDigestManager` (from Task 3), `DigestStorage` (from Task 2)
- Produces: Notification with digest summary, persisted digest JSON

- [ ] **Step 1: Create DailyDigestReceiver.kt**

Create `app/src/main/java/com/noteflowai/app/service/DailyDigestReceiver.kt`:

```kotlin
package com.noteflowai.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.noteflowai.app.MainActivity
import com.noteflowai.app.R
import com.noteflowai.app.data.concept.ConceptExtractor
import com.noteflowai.app.data.concept.ConceptGraphRepository
import com.noteflowai.app.data.digest.DailyDigestManager
import com.noteflowai.app.data.digest.DigestStorage
import com.noteflowai.app.data.search.NoteSearchIndex
import com.noteflowai.app.data.temporal.TemporalIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DailyDigestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DailyDigestReceiver"
        private const val CHANNEL_ID = "daily_digest"
        private const val NOTIFICATION_ID = 3
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DailyDigestScheduler.ACTION_GENERATE_DIGEST) return

        createNotificationChannel(context)

        // Use goAsync() for coroutine work
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val searchIndex = NoteSearchIndex()
                val extractor = ConceptExtractor(searchIndex)
                val temporalIndex = TemporalIndex(extractor)
                val conceptGraph = ConceptGraphRepository(extractor)

                temporalIndex.loadFromDisk(context)
                conceptGraph.loadFromDisk(context)

                val manager = DailyDigestManager(temporalIndex, conceptGraph, searchIndex)
                val digest = manager.generateDigest()

                // Save to disk
                DigestStorage.save(context, digest)

                // Build notification
                val activityCount = digest.recentActivity.size
                val questionCount = digest.openQuestions.size
                val staleCount = digest.staleConcepts.size
                val connectionCount = digest.hiddenConnections.size

                val title = "Your Daily Digest"
                val detail = buildString {
                    if (activityCount > 0) append("$activityCount recent topics")
                    if (questionCount > 0) {
                        if (isNotEmpty()) append(", ")
                        append("$questionCount open questions")
                    }
                    if (staleCount > 0) {
                        if (isNotEmpty()) append(", ")
                        append("$staleCount stale concepts")
                    }
                    if (connectionCount > 0) {
                        if (isNotEmpty()) append(", ")
                        append("$connectionCount hidden connections")
                    }
                    if (isEmpty()) append("All caught up!")
                }

                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("navigate_to", "DIGEST")
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_mic)
                    .setContentTitle(title)
                    .setContentText(detail)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(
                        NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification_mic)
                            .setContentTitle("Daily Digest")
                            .setContentText("Your daily brief is ready")
                            .build()
                    )
                    .setAutoCancel(true)
                    .build()

                val managerNotif = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                managerNotif.notify(NOTIFICATION_ID, notification)

                Log.i(TAG, "Daily digest generated: $detail")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate digest: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Daily Digest",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Daily summary of your notes, concepts, and open questions"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
```

- [ ] **Step 2: Register in AndroidManifest.xml**

In `AndroidManifest.xml`, after the existing `RecallReminderReceiver` block, add:

```xml
<receiver
    android:name=".service.DailyDigestReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.noteflowai.app.GENERATE_DAILY_DIGEST" />
    </intent-filter>
</receiver>
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/service/DailyDigestReceiver.kt \
       app/src/main/AndroidManifest.xml
git commit -m "feat: add DailyDigestReceiver with notification + manifest registration"
```

---

### Task 6: Wire digest into MainViewModel

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt`

**Interfaces:**
- Consumes: `DailyDigestManager`, `DigestStorage`, `DailyDigestScheduler`
- Produces: `val dailyDigest: StateFlow<DailyDigest?>`, `fun refreshDigest()`

- [ ] **Step 1: Add digest fields to MainViewModel**

After the recallReminderManager declaration (~line 571):

```kotlin
private val dailyDigestManager = DailyDigestManager(temporalIndex, conceptGraphRepository, noteSearchIndex)
private val _dailyDigest = MutableStateFlow<DailyDigest?>(null)
val dailyDigest: StateFlow<DailyDigest?> = _dailyDigest.asStateFlow()

private val dailyDigestScheduler = DailyDigestScheduler(getApplication())
private val _dailyDigestEnabled = MutableStateFlow(false)
val dailyDigestEnabled: StateFlow<Boolean> = _dailyDigestEnabled.asStateFlow()
```

- [ ] **Step 2: Load latest digest on startup**

In the `init` block, after loading the recall suggestions:

```kotlin
// Load latest daily digest
val latestDigest = DigestStorage.loadLatest(getApplication())
_dailyDigest.value = latestDigest
```

- [ ] **Step 3: Add refresh and toggle methods**

```kotlin
fun refreshDigest() {
    viewModelScope.launch {
        val digest = dailyDigestManager.generateDigest()
        DigestStorage.save(getApplication(), digest)
        _dailyDigest.value = digest
    }
}

fun setDailyDigestEnabled(enabled: Boolean) {
    _dailyDigestEnabled.value = enabled
    viewModelScope.launch {
        settingsManager.setDailyDigestEnabled(enabled)
        if (enabled) {
            dailyDigestScheduler.schedule(hour = 8, minute = 0)
        } else {
            dailyDigestScheduler.cancel()
        }
    }
}
```

Add imports:
```kotlin
import com.noteflowai.app.data.digest.DailyDigest
import com.noteflowai.app.data.digest.DailyDigestManager
import com.noteflowai.app.data.digest.DigestStorage
import com.noteflowai.app.service.DailyDigestScheduler
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "feat: wire DailyDigestManager into MainViewModel"
```

---

### Task 7: Add DailyDigestScreen

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/ui/screens/DailyDigestScreen.kt`
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NoteFlowApp.kt` — add DIGEST to Screen enum
- Modify: `app/src/main/java/com/noteflowai/app/ui/navigation/NoteFlowNavigation.kt` — add route
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/HomeScreen.kt` — add quick action

**Interfaces:**
- Consumes: `MainViewModel.dailyDigest` (from Task 6)
- Produces: Full digest viewer with sections for each digest category

- [ ] **Step 1: Add DIGEST to Screen enum**

In `NoteFlowApp.kt`:

```kotlin
enum class Screen {
    HOME, RECORD, NOTES, NOTE_DETAIL, SCAN, YOUTUBE, DOCUMENT, CHAT, SETTINGS, GRAPH, DIGEST
}
```

- [ ] **Step 2: Create DailyDigestScreen.kt**

Create `app/src/main/java/com/noteflowai/app/ui/screens/DailyDigestScreen.kt`:

```kotlin
package com.noteflowai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noteflowai.app.data.digest.DailyDigest
import com.noteflowai.app.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyDigestScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val digest by viewModel.dailyDigest.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daily Digest") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshDigest() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (digest == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No digest yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Tap refresh to generate your daily brief",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                item {
                    val dateStr = SimpleDateFormat("MMMM d, yyyy", Locale.US)
                        .format(Date(digest!!.generatedAt))
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Recent Activity
                if (digest!!.recentActivity.isNotEmpty()) {
                    item {
                        DigestSection(title = "Recent Activity") {
                            digest!!.recentActivity.forEach { item ->
                                DigestItem(
                                    title = item.noteTitle,
                                    subtitle = item.summary
                                )
                            }
                        }
                    }
                }

                // Open Questions
                if (digest!!.openQuestions.isNotEmpty()) {
                    item {
                        DigestSection(title = "Open Questions") {
                            digest!!.openQuestions.forEach { item ->
                                DigestItem(
                                    title = item.concept,
                                    subtitle = item.detail
                                )
                            }
                        }
                    }
                }

                // Stale Concepts
                if (digest!!.staleConcepts.isNotEmpty()) {
                    item {
                        DigestSection(title = "Stale Concepts") {
                            digest!!.staleConcepts.forEach { item ->
                                DigestItem(
                                    title = item.concept,
                                    subtitle = "Last seen ${item.daysSinceLastSeen} days ago"
                                )
                            }
                        }
                    }
                }

                // Hidden Connections
                if (digest!!.hiddenConnections.isNotEmpty()) {
                    item {
                        DigestSection(title = "Hidden Connections") {
                            digest!!.hiddenConnections.forEach { item ->
                                DigestItem(
                                    title = "${item.conceptA} <-> ${item.conceptB}",
                                    subtitle = "${(item.strength * 100).toInt()}% overlap"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DigestSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DigestItem(
    title: String,
    subtitle: String
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

- [ ] **Step 3: Add navigation route**

In `NoteFlowNavigation.kt`, after the GRAPH composable route:

```kotlin
composable(Screen.DIGEST.name) {
    DailyDigestScreen(
        viewModel = viewModel,
        onBack = { navController.popBackStack() }
    )
}
```

Add import: `import com.noteflowai.app.ui.screens.DailyDigestScreen`

- [ ] **Step 4: Add HomeScreen quick action**

In `HomeScreen.kt`, add to the quick actions section:

```kotlin
QuickActionButton(
    icon = Icons.Default.Summarize,
    label = "Digest",
    onClick = { onNavigate(Screen.DIGEST) }
)
```

Add import: `import androidx.compose.material.icons.filled.Summarize`

- [ ] **Step 5: Add settings toggle**

In `SettingsSections.kt`, add after the TTS section:

```kotlin
// --- Daily Digest ---
item {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Daily Digest",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        val digestEnabled by viewModel.dailyDigestEnabled.collectAsStateWithLifecycle()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Daily brief at 8:00 AM")
            Switch(
                checked = digestEnabled,
                onCheckedChange = { viewModel.setDailyDigestEnabled(it) }
            )
        }
    }
}
```

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/DailyDigestScreen.kt \
       app/src/main/java/com/noteflowai/app/ui/screens/NoteFlowApp.kt \
       app/src/main/java/com/noteflowai/app/ui/navigation/NoteFlowNavigation.kt \
       app/src/main/java/com/noteflowai/app/ui/screens/HomeScreen.kt \
       app/src/main/java/com/noteflowai/app/ui/screens/SettingsSections.kt
git commit -m "feat: add DailyDigestScreen with navigation and settings toggle"
```

---

### Task 8: End-to-end test

- [ ] **Step 1: Build and install**

Run: `./gradlew installDebug`

- [ ] **Step 2: Manual verification**

1. Create several notes with different concepts
2. Open HomeScreen → tap "Digest" quick action
3. Tap refresh → verify digest generates with sections
4. Verify notification fires (or simulate with: `adb shell am broadcast -a com.noteflowai.app.GENERATE_DAILY_DIGEST -n com.noteflowai.app/.service.DailyDigestReceiver`)
5. Open Settings → verify Daily Digest toggle works
6. Disable digest → verify scheduler cancels
7. Verify digest persists — close and reopen app, digest still shows

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: daily digest complete"
```
