package com.noteflowai.app.ui.theme

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Android system navigation interaction modes.
 */
enum class NavigationMode(val displayName: String) {
    /**
     * Modern full-screen gesture navigation (thin home indicator bar or hidden).
     */
    GESTURAL("Gesture Navigation"),

    /**
     * Classic 3-button navigation (Back, Home, Recents/Overview).
     * Typically occupies 48dp-56dp at the bottom in portrait or a side bar in landscape.
     */
    THREE_BUTTON("3-Button Navigation"),

    /**
     * 2-button navigation (pill + back button).
     */
    TWO_BUTTON("2-Button Navigation");

    val isGestural: Boolean get() = this == GESTURAL
    val isButtonNav: Boolean get() = this == THREE_BUTTON || this == TWO_BUTTON
}

/**
 * CompositionLocal providing the auto-detected system navigation mode.
 */
val LocalNavigationMode = compositionLocalOf { NavigationMode.GESTURAL }

/**
 * Checks system internal resource `config_navBarInteractionMode` if available.
 * 0: 3-button navigation
 * 1: 2-button navigation
 * 2: Full-screen gesture navigation
 */
fun detectSystemNavBarInteractionMode(context: Context): Int? {
    return try {
        val resId = context.resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        if (resId > 0) context.resources.getInteger(resId) else null
    } catch (_: Exception) {
        null
    }
}

/**
 * Auto-detects the current Android system navigation mode (Gestural vs 3-Button vs 2-Button).
 *
 * Uses a hybrid multi-signal approach:
 * 1. Checks system configuration `config_navBarInteractionMode`.
 * 2. Cross-references live WindowInsets (`WindowInsets.tappableElement` and `WindowInsets.navigationBars`).
 * 3. Dynamically adapts when the user rotates the device or changes navigation modes in Android Settings.
 */
@Composable
fun rememberNavigationMode(): NavigationMode {
    val context = LocalContext.current

    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues()
    val tappablePadding = WindowInsets.tappableElement.asPaddingValues()

    val layoutDirection = LocalLayoutDirection.current
    val bottomNavBarDp = navBarsPadding.calculateBottomPadding()
    val rightNavBarDp = navBarsPadding.calculateRightPadding(layoutDirection)
    val leftNavBarDp = navBarsPadding.calculateLeftPadding(layoutDirection)
    val bottomTappableDp = tappablePadding.calculateBottomPadding()

    return remember(bottomNavBarDp, rightNavBarDp, leftNavBarDp, bottomTappableDp) {
        val sysMode = detectSystemNavBarInteractionMode(context)
        when {
            // Explicit system flag reporting 3-button mode
            sysMode == 0 -> NavigationMode.THREE_BUTTON

            // Explicit system flag reporting 2-button mode
            sysMode == 1 -> NavigationMode.TWO_BUTTON

            // Explicit system flag reporting gestural navigation, verified with reasonable insets
            sysMode == 2 && bottomNavBarDp <= 32.dp -> NavigationMode.GESTURAL

            // Insets-based detection for OEM ROMs (Samsung, Xiaomi, etc.) where system resource may be omitted:
            // 3-button navigation in portrait creates a tappable area with bottom height >= 36dp (standard 48dp)
            bottomTappableDp >= 36.dp || bottomNavBarDp >= 40.dp -> NavigationMode.THREE_BUTTON

            // In landscape, 3-button navigation docks to left or right edge with width >= 36dp
            rightNavBarDp >= 36.dp || leftNavBarDp >= 36.dp -> NavigationMode.THREE_BUTTON

            // Default to Gestural (thin bar <= 24dp or 0dp)
            else -> NavigationMode.GESTURAL
        }
    }
}

/**
 * Returns dynamic bottom clearance required for scrollable views or floating actions
 * to ensure they are never overlapped by system navigation bars or buttons.
 */
@Composable
fun adaptiveNavigationBarBottomPadding(extraPadding: Dp = 0.dp): Dp {
    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navMode = LocalNavigationMode.current
    val buffer = if (navMode.isButtonNav) 16.dp else 8.dp
    return navBarsBottom + buffer + extraPadding
}

/**
 * Modifier that applies adaptive navigation bar padding, taking into account
 * whether button navigation (3-button / 2-button) or gesture navigation is active.
 * Also handles landscape docked buttons on the horizontal edges.
 */
fun Modifier.adaptiveNavigationBarPadding(
    includeHorizontal: Boolean = true,
    additionalBottom: Dp = 0.dp
): Modifier = composed {
    val navBarsPadding = WindowInsets.navigationBars.asPaddingValues()
    val navMode = LocalNavigationMode.current
    val layoutDirection = LocalLayoutDirection.current

    val bottomInset = navBarsPadding.calculateBottomPadding()
    val startInset = if (includeHorizontal) navBarsPadding.calculateStartPadding(layoutDirection) else 0.dp
    val endInset = if (includeHorizontal) navBarsPadding.calculateEndPadding(layoutDirection) else 0.dp

    // In button navigation, ensure an additional safety margin so clickable buttons
    // don't crowd the system Back, Home, and Recents buttons.
    val safetyMargin = if (navMode.isButtonNav && bottomInset > 0.dp) 8.dp else 0.dp

    this.padding(
        start = startInset,
        end = endInset,
        bottom = bottomInset + safetyMargin + additionalBottom
    )
}
