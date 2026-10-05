package com.noteflowai.benchmark

import androidx.test.uiautomator.UiDevice

/**
 * Shared UiAutomator helpers for the benchmark suite.
 *
 * Bottom-nav labels (see strings.xml nav_label_*): Home, Library, Capture,
 * Recall, Insights. Tabs are clicked by visible label text.
 */
internal const val BENCH_PACKAGE = "com.noteflowai.app"

internal fun dismissIntroIfNeeded(device: UiDevice) {
    // Intro is SharedPreferences-gated and normally already dismissed on a
    // used device; handle a fresh-data edge case so iterations stay valid.
    runCatching {
        if (device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Skip")), 1500)) {
            device.findObject(androidx.test.uiautomator.By.text("Skip")).click()
            device.waitForIdle()
        }
    }
    runCatching {
        if (device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Get Started")), 1500)) {
            device.findObject(androidx.test.uiautomator.By.text("Get Started")).click()
            device.waitForIdle()
        }
    }
}

internal fun goToTab(device: UiDevice, label: String) {
    val target = androidx.test.uiautomator.By.text(label)
    // Crosshair: try text lookup several times before falling back to
    // coordinates (needed when Perfetto capture + recompilation keeps
    // the first frame's accessibility tree stale).
    var labelNode: androidx.test.uiautomator.UiObject2? = null
    var elapsed = 0L
    while (labelNode == null && elapsed < 8000) {
        labelNode = device.findObject(target)
        if (labelNode == null) {
            Thread.sleep(300)
            elapsed += 300
        }
    }
    if (labelNode != null) {
        var clickable: androidx.test.uiautomator.UiObject2? = labelNode.parent
        var guard = 0
        while (clickable != null && !clickable.isClickable && guard < 6) {
            clickable = clickable.parent
            guard++
        }
        if (clickable != null && clickable.isClickable) clickable.click() else labelNode.click()
        device.waitForIdle()
        Thread.sleep(500)
        device.waitForIdle()
        return
    }
    // Fallback: tap bottom-nav slot by position — resilient to text-tree staleness.
    val slotIndex = when (label) {
        "Library" -> 1  // bottomNav slots: 0=Home,1=Library,2=Capture,3=Recall,4=Insights
        "Recall" -> 3
        "Insights" -> 4
        "Home" -> 0
        else -> -1
    }
    if (slotIndex >= 0) {
        val w = device.displayWidth
        val h = device.displayHeight
        val slotW = w / 5
        val x = slotW * slotIndex + slotW / 2
        val y = h - 140 // just above nav line on 1440×3200
        device.click(x, y)
        Thread.sleep(600)
        device.waitForIdle()
        return
    }
    error("tab label not found (no slot fallback): $label")
}
