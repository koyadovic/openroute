package com.openroute.app.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.PI

internal class DeviceMotionBridge(
    context: Context,
    private val onOrientationSample: (OrientationSample) -> Unit,
    private val onMotionSample: (ImuMotionSample) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linearAccelerationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val sensorHandler = Handler(Looper.getMainLooper())
    private var currentRotationMatrix: FloatArray? = null
    private var lastMotionDispatchTimestampNanos: Long? = null

    fun start() {
        val sensorManager = sensorManager ?: return
        rotationVectorSensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME,
                sensorHandler,
            )
        }
        linearAccelerationSensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME,
                sensorHandler,
            )
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        currentRotationMatrix = null
        lastMotionDispatchTimestampNanos = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event)
            Sensor.TYPE_LINEAR_ACCELERATION -> handleLinearAcceleration(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleRotationVector(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        currentRotationMatrix = rotationMatrix

        val orientationValues = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientationValues)
        val headingDegrees = Math.toDegrees(orientationValues[0].toDouble())
            .let { heading -> 360.0 - heading }
            .normalizeHeadingDegrees()

        onOrientationSample(
            OrientationSample(
                timestampNanos = event.timestamp,
                headingDegrees = headingDegrees,
            ),
        )
    }

    private fun handleLinearAcceleration(event: SensorEvent) {
        val rotationMatrix = currentRotationMatrix ?: return
        val lastDispatch = lastMotionDispatchTimestampNanos
        if (lastDispatch != null && event.timestamp - lastDispatch < MIN_MOTION_DISPATCH_INTERVAL_NANOS) {
            return
        }

        val deviceAcceleration = event.values
        val eastMetersPerSecondSquared =
            (rotationMatrix[0] * deviceAcceleration[0]) +
                (rotationMatrix[1] * deviceAcceleration[1]) +
                (rotationMatrix[2] * deviceAcceleration[2])
        val northMetersPerSecondSquared =
            (rotationMatrix[3] * deviceAcceleration[0]) +
                (rotationMatrix[4] * deviceAcceleration[1]) +
                (rotationMatrix[5] * deviceAcceleration[2])

        lastMotionDispatchTimestampNanos = event.timestamp
        onMotionSample(
            ImuMotionSample(
                timestampNanos = event.timestamp,
                accelerationEastMetersPerSecondSquared = eastMetersPerSecondSquared.toDouble(),
                accelerationNorthMetersPerSecondSquared = northMetersPerSecondSquared.toDouble(),
            ),
        )
    }

    private fun Double.normalizeHeadingDegrees(): Double {
        return ((this % FULL_CIRCLE_DEGREES) + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES
    }

    private companion object {
        const val MIN_MOTION_DISPATCH_INTERVAL_NANOS = 120_000_000L
        const val FULL_CIRCLE_DEGREES = 360.0
    }
}
