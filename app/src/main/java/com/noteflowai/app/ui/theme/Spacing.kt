package com.noteflowai.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Consistent spacing tokens following 4dp grid.
 * Use these instead of raw dp literals to keep spacing harmonious.
 */
object AppSpacing {
    /** 4dp — tight inline spacing (icon-to-text, chip internals) */
    val xs = 4.dp

    /** 8dp — compact spacing (list item gaps, small spacers) */
    val sm = 8.dp

    /** 12dp — comfortable spacing (card internal padding, chip-to-chip) */
    val md = 12.dp

    /** 16dp — default card/screen padding */
    val lg = 16.dp

    /** 24dp — section separation */
    val xl = 24.dp

    /** 32dp — large screen padding (intro pages, full-width layouts) */
    val xxl = 32.dp

    /** 48dp — bottom safe area / screen edge breathing room */
    val xxxl = 48.dp
}

/**
 * Single motion duration scale (Soft Organic Intelligence).
 * Micro-interactions 150-300ms per platform guidance; slow/reveal for
 * page and hero entrances. Use these instead of hardcoded ms values.
 * Replaces the old split AppMotion/AppAnim scales.
 */
object AppMotion {
    /** 150ms — micro-interaction: ripple, focus, switch toggle, press scale */
    const val micro = 150

    /** 200ms — fast: color fade, chip select, small expand */
    const val fast = 200

    /** 300ms — standard: tab changes, list reorder, card expand, stagger base */
    const val standard = 300

    /** 500ms — slow: page transition, sheet enter */
    const val slow = 500

    /** 700ms — reveal: hero/intro entrance only */
    const val reveal = 700

    // Backward-compat aliases for the pre-merge names.
    @Deprecated("Use AppMotion.micro", ReplaceWith("AppMotion.micro"))
    const val short = micro

    @Deprecated("Use AppMotion.standard", ReplaceWith("AppMotion.standard"))
    const val medium = standard

    @Deprecated("Use AppMotion.slow", ReplaceWith("AppMotion.slow"))
    const val long = slow
}
