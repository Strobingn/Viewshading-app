package com.viewshed.app.viewshed

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Phase 5 — Device compass (magnetic heading).
 */
class CompassHelper(
    context: Context,
    private val onBearing: (Float) -> Unit
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val R = FloatArray(9)
    private val I = FloatArray(9)

    @Volatile
    var bearingDeg: Float = 0f
        private set

    val available: Boolean
        get() = accelerometer != null && magnetometer != null

    fun start() {
        accelerometer?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
            }
            else -> return
        }
        if (SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
            val orient = FloatArray(3)
            SensorManager.getOrientation(R, orient)
            var az = Math.toDegrees(orient[0].toDouble()).toFloat()
            if (az < 0) az += 360f
            bearingDeg = az
            onBearing(az)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
