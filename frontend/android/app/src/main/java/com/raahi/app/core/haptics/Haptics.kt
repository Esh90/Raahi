package com.raahi.app.core.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.raahi.app.core.model.HapticKind

/** Vibration patterns for the server's `haptic` values (`short` | `long` | `arrived`). */
object HapticPatterns {
    /** one firm pulse - a warning/direction repeat */
    val SHORT = longArrayOf(0, 140)

    /** danger (< 0.8 m): two long pulses */
    val LONG = longArrayOf(0, 450, 120, 450)

    /** arrived at the target: short-short-long */
    val ARRIVED = longArrayOf(0, 90, 90, 90, 90, 260)

    fun forKind(kind: String): LongArray? = when (kind) {
        HapticKind.SHORT -> SHORT
        HapticKind.LONG -> LONG
        HapticKind.ARRIVED -> ARRIVED
        else -> null
    }
}

class Haptics(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    @Volatile private var enabled = true
    private var lastKind = HapticKind.NONE
    private var lastAt = 0L

    val isAvailable: Boolean get() = vibrator?.hasVibrator() == true
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) cancel()
    }

    val isEnabled: Boolean get() = enabled

    /**
     * Fire the pattern for [kind]. Identical patterns closer than [MIN_GAP_MS] are ignored so a stream
     * of replies cannot keep the motor running; a stronger pattern always replaces a weaker one.
     * Returns true if a vibration was started.
     */
    fun vibrate(kind: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val v = vibrator ?: return false
        if (!enabled || !v.hasVibrator()) return false
        val pattern = HapticPatterns.forKind(kind) ?: return false
        val stronger = rank(kind) > rank(lastKind)
        if (!stronger && nowMs - lastAt < MIN_GAP_MS) return false
        lastKind = kind
        lastAt = nowMs
        v.cancel()
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        return true
    }

    fun cancel() {
        vibrator?.cancel()
        lastKind = HapticKind.NONE
    }

    private fun rank(kind: String) = when (kind) {
        HapticKind.LONG -> 3
        HapticKind.ARRIVED -> 2
        HapticKind.SHORT -> 1
        else -> 0
    }

    companion object {
        const val MIN_GAP_MS = 700L
    }
}
