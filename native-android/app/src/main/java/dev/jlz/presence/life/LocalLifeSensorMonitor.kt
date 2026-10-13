package dev.jlz.presence.life

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import kotlin.math.sqrt

/** Voluntary on-device light/shake reminders: no raw samples uploaded or saved. */
class LocalLifeSensorMonitor(context: Context) : SensorEventListener {
    private val app = context.applicationContext
    private val manager = app.getSystemService(SensorManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private val alerts = LocalLifeReminderEngine(app)
    private var registered = false
    private var darkStartedAt = 0L
    private var peakAt = 0L
    private var shakeCount = 0

    fun start() {
        if (registered || power?.isInteractive != true || !alerts.enabled()) return
        val light = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        val acceleration = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = light?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } ?: false
        val a = acceleration?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } ?: false
        registered = l || a
    }
    fun stop() {
        if (registered) manager?.unregisterListener(this)
        registered = false
        darkStartedAt = 0L
        peakAt = 0L
        shakeCount = 0
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || power?.isInteractive != true || !alerts.enabled()) {
            stop()
            return
        }
        val now = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val lux = event.values.firstOrNull() ?: return
                if (lux < 8f) {
                    if (darkStartedAt == 0L) darkStartedAt = now
                    if (now - darkStartedAt >= 120_000L) {
                        alerts.lowLight()
                        darkStartedAt = now
                    }
                } else darkStartedAt = 0L
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size < 3) return
                val g = sqrt(event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] + event.values[2] * event.values[2])
                if (g > 24f && now - peakAt >= 400L) {
                    shakeCount = if (now - peakAt <= 2500L) shakeCount + 1 else 1
                    peakAt = now
                    if (shakeCount >= 3) {
                        shakeCount = 0
                        alerts.shake()
                    }
                }
            }
        }
    }
}
