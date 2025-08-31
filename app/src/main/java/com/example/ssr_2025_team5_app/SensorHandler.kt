package com.example.ssr_2025_team5_app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.ImageView
import kotlin.math.round

class SensorHandler(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastTimestamp = 0L

    var roll = 0f
        private set
    var pitch = 0f
        private set
    var azimuth = 0f
        private set
    var px = 0f
        private set
    var py = 0f
        private set
    var pz = 0f
        private set
    private var vx = 0f
    private var vy = 0f
    private var vz = 0f

    private var listener: ((Float, Float, Float, Float, Float, Float) -> Unit)? = null

    init {
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun setOnSensorDataListener(listener: (Float, Float, Float, Float, Float, Float) -> Unit) {
        this.listener = listener
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val now = event.timestamp
                if (lastTimestamp != 0L) {
                    val dt = (now - lastTimestamp) / 1_000_000_000.0f
                    vx += ax * dt; vy += ay * dt; vz += az * dt
                    px += vx * dt; py += vy * dt; pz += vz * dt
                    px = round(px * 10) / 10f
                    py = round(py * 10) / 10f
                    pz = round(pz * 10) / 10f
                }
                lastTimestamp = now
            }
        }
        listener?.invoke(roll, pitch, azimuth, px, py, pz)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun cleanup() {
        sensorManager.unregisterListener(this)
    }
}