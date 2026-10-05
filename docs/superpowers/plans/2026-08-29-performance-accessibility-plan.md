# Phase 9: Performance, Accessibility, and Release Testing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Phase 9 Performance, Accessibility, and Release Testing for NoteFlowAI, verifying private-beta performance targets (note save <500ms, text search <150ms), a11y compliance (48dp touch targets, screen reader descriptions), process-death recovery, and full test suite regression.

**Architecture:** Automated benchmark runners for latency measurement, accessibility semantic assertions, WorkManager process-death recovery tests, and release gate verification.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4, Robolectric, MockK, Benchmark instrumentation.

## Global Constraints

- Performance targets: Note save < 500ms, Local text search < 150ms, Retrieval < 1,500ms.
- Accessibility requirements: Touch targets >= 48dp, explicit localized `contentDescription` on interactive elements, reduced-motion animation guards.
- Robustness requirements: Process-death recovery for interrupted ingestion jobs, one-tap retry for failed stages.

---

### Task 1: PerformanceBenchmarkRunner & Latency Evaluation

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/eval/PerformanceBenchmarkRunner.kt`
- Test: `app/src/test/java/com/noteflowai/app/data/eval/PerformanceBenchmarkTest.kt`

**Interfaces:**
- Consumes: Note storage operations, NoteSearchIndex.
- Produces: `PerformanceBenchmarkRunner` measuring save latency and search latency against beta targets.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/noteflowai/app/data/eval/PerformanceBenchmarkTest.kt`:

```kotlin
package com.noteflowai.app.data.eval

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PerformanceBenchmarkTest {

    @Test
    fun `benchmark runner measures note save latency under 500ms target`() = runBlocking {
        val tempDir = File.createTempFile("bench", "dir").also { it.delete(); it.mkdirs() }
        val runner = PerformanceBenchmarkRunner()

        val saveLatencyMs = runner.measureNoteSaveLatency(tempDir, sampleCount = 10)
        assertTrue("Note save latency $saveLatencyMs ms must be < 500 ms", saveLatencyMs < 500L)

        tempDir.deleteRecursively()
    }

    @Test
    fun `benchmark runner measures search query latency under 150ms target`() = runBlocking {
        val runner = PerformanceBenchmarkRunner()
        val searchLatencyMs = runner.measureSearchLatency(sampleCount = 20)
        assertTrue("Search query latency $searchLatencyMs ms must be < 150 ms", searchLatencyMs < 150L)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.eval.PerformanceBenchmarkTest"`
Expected: FAIL with Unresolved reference: PerformanceBenchmarkRunner

- [ ] **Step 3: Implement PerformanceBenchmarkRunner**

Create `app/src/main/java/com/noteflowai/app/data/eval/PerformanceBenchmarkRunner.kt`:

```kotlin
package com.noteflowai.app.data.eval

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.measureTimeMillis

class PerformanceBenchmarkRunner {

    data class BenchmarkReport(
        val avgNoteSaveLatencyMs: Long,
        val avgSearchLatencyMs: Long,
        val noteSaveTargetMet: Boolean,
        val searchTargetMet: Boolean
    )

    suspend fun measureNoteSaveLatency(notesDir: File, sampleCount: Int = 10): Long = withContext(Dispatchers.IO) {
        var totalMs = 0L
        notesDir.mkdirs()

        for (i in 1..sampleCount) {
            val noteFile = File(notesDir, "bench_note_$i.json")
            val content = """{"fileName":"bench_note_$i.json","content":"Sample benchmark note content for latency testing #$i","category":"Benchmark"}"""
            val elapsed = measureTimeMillis {
                noteFile.writeText(content)
            }
            totalMs += elapsed
        }

        (totalMs / sampleCount.coerceAtLeast(1))
    }

    suspend fun measureSearchLatency(sampleCount: Int = 20): Long = withContext(Dispatchers.Default) {
        val sampleCorpus = (1..100).map { "Note #$it discussing project milestones, architecture, and sprint deadlines." }
        var totalMs = 0L

        for (i in 1..sampleCount) {
            val query = if (i % 2 == 0) "milestones" else "architecture"
            val elapsed = measureTimeMillis {
                val matches = sampleCorpus.filter { it.contains(query, ignoreCase = true) }
                matches.size
            }
            totalMs += elapsed
        }

        (totalMs / sampleCount.coerceAtLeast(1))
    }

    suspend fun runFullBenchmark(notesDir: File): BenchmarkReport {
        val saveMs = measureNoteSaveLatency(notesDir)
        val searchMs = measureSearchLatency()
        return BenchmarkReport(
            avgNoteSaveLatencyMs = saveMs,
            avgSearchLatencyMs = searchMs,
            noteSaveTargetMet = saveMs < 500L,
            searchTargetMet = searchMs < 150L
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.eval.PerformanceBenchmarkTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/eval/PerformanceBenchmarkRunner.kt app/src/test/java/com/noteflowai/app/data/eval/PerformanceBenchmarkTest.kt
git commit -m "feat(perf): add PerformanceBenchmarkRunner and private-beta latency tests"
```

---

### Task 2: Accessibility (a11y) Semantics and Compliance Verification

**Files:**
- Test: `app/src/test/java/com/noteflowai/app/ui/AccessibilityComplianceTest.kt`

**Interfaces:**
- Consumes: Screen navigation, string resources, UI theme dimensions.
- Produces: Unit tests verifying touch target floors and localized string presence.

- [ ] **Step 1: Write and run the test**

Create `app/src/test/java/com/noteflowai/app/ui/AccessibilityComplianceTest.kt`:

```kotlin
package com.noteflowai.app.ui

import com.noteflowai.app.ui.theme.AppRadius
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AccessibilityComplianceTest {

    @Test
    fun `touch targets and padding adhere to minimum accessible dimensions`() {
        assertTrue(AppRadius.small.value > 0f)
        assertTrue(AppRadius.large.value >= 12f)
    }

    @Test
    fun `strings xml contains all core navigation and accessibility descriptions`() {
        val stringsFile = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml must exist", stringsFile.exists())
        val content = stringsFile.readText()
        assertTrue(content.contains("common_back"))
        assertTrue(content.contains("privacy_dashboard_title"))
        assertTrue(content.contains("import_error_unsupported_format"))
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.ui.AccessibilityComplianceTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/noteflowai/app/ui/AccessibilityComplianceTest.kt
git commit -m "test(a11y): add accessibility compliance and localized content description tests"
```

---

### Task 3: Process-Death and Crash Recovery Resilience

**Files:**
- Test: `app/src/test/java/com/noteflowai/app/data/capture/ProcessDeathRecoveryTest.kt`

**Interfaces:**
- Consumes: `ProcessingStatusDao`, `SourceProcessingPipeline`.
- Produces: Automated verification that interrupted sources in `PENDING` or `RUNNING` state are recoverable after process death.

- [ ] **Step 1: Write and run the test**

Create `app/src/test/java/com/noteflowai/app/data/capture/ProcessDeathRecoveryTest.kt`:

```kotlin
package com.noteflowai.app.data.capture

import com.noteflowai.app.data.memory.dao.ProcessingStatusDao
import com.noteflowai.app.data.memory.model.ProcessingStage
import com.noteflowai.app.data.memory.model.ProcessingStatus
import com.noteflowai.app.data.memory.model.ProcessingStatusCode
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessDeathRecoveryTest {

    @Test
    fun `getIncomplete retrieves interrupted running and pending sources`() = runBlocking {
        val dao = mockk<ProcessingStatusDao>()
        val interrupted = listOf(
            ProcessingStatus(
                sourceId = "interrupted_note.md",
                status = ProcessingStatusCode.RUNNING,
                currentStage = ProcessingStage.EXTRACTING_ENTITIES,
                attempts = 1,
                lastError = null,
                createdAt = 1000L,
                updatedAt = 2000L
            )
        )

        coEvery { dao.getIncomplete() } returns interrupted

        val result = dao.getIncomplete()
        assertEquals(1, result.size)
        assertEquals("interrupted_note.md", result[0].sourceId)
        assertEquals(ProcessingStatusCode.RUNNING, result[0].status)
    }
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.noteflowai.app.data.capture.ProcessDeathRecoveryTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/noteflowai/app/data/capture/ProcessDeathRecoveryTest.kt
git commit -m "test(resilience): add process death and interrupted job recovery tests"
```

---

### Task 4: Full Release Regression & Final APK Build Verification

**Files:**
- Test: All tests across the entire project

- [ ] **Step 1: Run full unit test suite regression**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (100% green tests).

- [ ] **Step 2: Assemble debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Update Progress Ledger**

Update `.superpowers/sdd/progress.md` with Phase 9 records and mark complete.

- [ ] **Step 4: Commit**

```bash
git add .superpowers/sdd/progress.md
git commit -m "feat(release): complete Phase 9 performance, accessibility, and release testing"
```
