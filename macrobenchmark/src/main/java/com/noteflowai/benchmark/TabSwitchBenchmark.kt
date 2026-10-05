package com.noteflowai.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tab-switch fluidity: Home -> Library -> Recall -> Insights -> Home.
 * Covers the reported "each menu screen loads" symptom with per-frame timing.
 */
@RunWith(AndroidJUnit4::class)
class TabSwitchBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun tabSwitch() = benchmarkRule.measureRepeated(
        packageName = BENCH_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            dismissIntroIfNeeded(device)
            Thread.sleep(500)
            device.waitForIdle()
        }
    ) {
        goToTab(device, "Library")
        goToTab(device, "Recall")
        goToTab(device, "Insights")
        goToTab(device, "Home")
    }
}
