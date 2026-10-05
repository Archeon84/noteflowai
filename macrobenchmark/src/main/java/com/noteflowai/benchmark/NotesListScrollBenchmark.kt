package com.noteflowai.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Notes Library scroll fluidity: fling down/up over the notes list.
 * Covers the reported Library scroll-jank symptom with per-frame timing.
 */
@RunWith(AndroidJUnit4::class)
class NotesListScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun notesListScroll() = benchmarkRule.measureRepeated(
        packageName = BENCH_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            dismissIntroIfNeeded(device)
            goToTab(device, "Library")
        }
    ) {
        // Library screen may be ScrollView-backed rather than a LazyColumn:
        // gesture on scrollable first then refind on stale.
        val scrollables = device.findObjects(By.scrollable(true))
        val list = scrollables.firstOrNull()
        if (list != null) {
            try { list.fling(Direction.DOWN) } catch (_: Exception) {}
            device.waitForIdle()
            // refind after first fling can throw StaleObjectException
            val list2 = device.findObjects(By.scrollable(true)).firstOrNull()
            if (list2 != null) {
                try { list2.fling(Direction.UP) } catch (_: Exception) {}
                device.waitForIdle()
            }
        }
    }
}
