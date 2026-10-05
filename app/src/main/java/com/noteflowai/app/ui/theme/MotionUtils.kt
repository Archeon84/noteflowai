package com.noteflowai.app.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext

/**
 * Checks if the user has enabled reduced motion in system settings.
 * On API 31+, checks Settings.Global.ANIMATOR_DURATION_SCALE.
 * On older APIs, returns false (assume animations are desired).
 */
@ReadOnlyComposable
@Composable
fun isReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return computeIsReducedMotionEnabled(context)
}

/**
 * Non-composable version of [isReducedMotionEnabled] for use with [remember] and other non-composable contexts.
 */
fun computeIsReducedMotionEnabled(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val durationScale = android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        durationScale == 0f
    } else {
        false
    }
}

/**
 * Returns an animation spec that respects reduced motion preferences.
 * If reduced motion is enabled, returns a very fast (near-instant) tween.
 * Otherwise, returns the provided spec or a default spring.
 */
@ReadOnlyComposable
@Composable
fun <T> reducedMotionSpec(
    normalSpec: AnimationSpec<T>? = null
): AnimationSpec<T> {
    return if (isReducedMotionEnabled()) {
        tween(durationMillis = 1, easing = LinearEasing)
    } else {
        normalSpec ?: spring()
    }
}

/**
 * Returns a duration in milliseconds, reduced to near-instant if reduced motion is enabled.
 */
@ReadOnlyComposable
@Composable
fun reducedMotionDuration(normalDurationMs: Int): Int {
    return if (isReducedMotionEnabled()) 1 else normalDurationMs
}

/**
 * Backward-compat alias for the merged [AppMotion] scale.
 * Prefer AppMotion.micro/fast/standard/slow/reveal in new code.
 */
@Deprecated("Use AppMotion instead", ReplaceWith("AppMotion", "com.noteflowai.app.ui.theme.AppMotion"))
typealias AppAnim = AppMotion
