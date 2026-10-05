package com.noteflowai.app.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * Onboarding screen entry point.
 * Delegates to [IntroScreen] to deliver the unified, gold-standard welcome,
 * feature showcase, and model download mini-tutorial experience.
 */
@Composable
fun OnboardingScreen(onDismiss: () -> Unit) {
    IntroScreen(onComplete = onDismiss)
}

object OnboardingPrefs {
    private const val PREF_NAME = "onboarding"
    private const val KEY_SHOWN = "has_shown"

    fun hasShown(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SHOWN, false)
    }

    fun markShown(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOWN, true).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOWN, false).apply()
    }
}
