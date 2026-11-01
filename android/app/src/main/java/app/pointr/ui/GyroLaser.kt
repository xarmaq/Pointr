package app.pointr.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

/**
 * While [active], turns the phone into an air pointer: turning it left/right (about the
 * real-world vertical, whatever the grip) moves the dot sideways, tilting it moves it up/down.
 */
@Composable
fun GyroLaser(active: Boolean, sensitivity: Float, onDelta: (Float, Float) -> Unit) {
    val context = LocalContext.current
    val emit by rememberUpdatedState(onDelta)
    val gain by rememberUpdatedState(1400f * sensitivity) // px per radian

    DisposableEffect(active) {
        val sm = context.getSystemService(SensorManager::class.java)
        val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (!active || gyro == null) return@DisposableEffect onDispose { }

        val up = floatArrayOf(0f, 1f, 0f) // until gravity reports: assume upright
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type == Sensor.TYPE_GRAVITY) {
                    val n = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                    if (n > 0.1f) for (i in 0..2) up[i] = e.values[i] / n
                    return
                }
                if (last != 0L) {
                    val dt = (e.timestamp - last) / 1e9f
                    val (wx, wy, wz) = e.values
                    val yaw = wx * up[0] + wy * up[1] + wz * up[2]
                    emit(-yaw * dt * gain, -wx * dt * gain)
                }
                last = e.timestamp
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(listener) }
    }
}
