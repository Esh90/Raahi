package com.raahi.app.core.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/** Pure geometry so it can be unit tested. Device axes: x right, y up the screen, z out of the screen. */
object Orientation {
    const val ENTER_DEG = 35.0 // upright when within this of vertical
    const val LEAVE_DEG = 48.0 // ...and only "not upright" once past this (hysteresis)

    /** Angle between the phone's long axis and the gravity-vertical, degrees (0 = perfectly upright portrait). */
    fun pitchFromVertical(x: Float, y: Float, z: Float): Double {
        val g = sqrt((x * x + y * y + z * z).toDouble())
        if (g < 1e-3) return 0.0
        return Math.toDegrees(acos((y / g).coerceIn(-1.0, 1.0)))
    }

    /** Sideways lean (roll) in degrees. */
    fun rollDeg(x: Float, y: Float): Double = Math.toDegrees(atan2(x.toDouble(), y.toDouble()))

    fun isUpright(x: Float, y: Float, z: Float, wasUpright: Boolean): Boolean {
        val pitch = pitchFromVertical(x, y, z)
        val roll = abs(rollDeg(x, y))
        val limit = if (wasUpright) LEAVE_DEG else ENTER_DEG
        return pitch <= limit && roll <= limit
    }
}

/**
 * Tells the guidance session whether the phone is held upright. The server has no orientation gate
 * (server/main.py processes any frame), so per PLAN.md F3 the *phone* withholds frames and says
 * `phone_upright` when the phone is flat or lying sideways.
 */
class UprightMonitor(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _upright = MutableStateFlow(true)
    val upright: StateFlow<Boolean> = _upright.asStateFlow()

    /** False when the device has no gravity/accelerometer: we cannot gate, so we never block. */
    val available: Boolean get() = sensor != null

    private var candidate = true
    private var candidateSince = 0L

    fun start() {
        val s = sensor ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sm.unregisterListener(this)
        _upright.value = true
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        val ok = Orientation.isUpright(e.values[0], e.values[1], e.values[2], _upright.value)
        if (ok != candidate) {
            candidate = ok
            candidateSince = now
        }
        // debounce: the new state must hold for 500 ms
        if (candidate != _upright.value && now - candidateSince >= DEBOUNCE_MS) _upright.value = candidate
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val DEBOUNCE_MS = 500L
    }
}
