package com.noteflowai.app.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic feedback utilities for important user actions.
 * Uses the platform's haptic API — no library dependency.
 */
object Haptics {

    /** Light tap — toggle, selection, chip press */
    fun tap(context: Context) {
        perform(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    }

    /** Medium confirmation — save, send, complete action */
    fun confirm(context: Context) {
        perform(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    }

    /** Subtle tick — slider change, carousel snap */
    fun tick(context: Context) {
        perform(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    /** Warning / destructive action — delete, error */
    fun warning(context: Context) {
        perform(context, VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    @Suppress("DEPRECATION")
    private fun perform(context: Context, effect: VibrationEffect) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            vibrator.vibrate(effect)
        } catch (_: Exception) {
            // Some devices (e.g. Xiaomi) throw SecurityException for vibration
            // even with VIBRATE permission. Silently ignore.
        }
    }
}
