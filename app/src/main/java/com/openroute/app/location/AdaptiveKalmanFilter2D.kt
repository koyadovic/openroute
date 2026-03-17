package com.openroute.app.location

import kotlin.math.sqrt

internal class AdaptiveKalmanFilter2D(
    positionX: Double,
    positionY: Double,
    timestampNanos: Long,
    positionVarianceMetersSquared: Double,
    velocityVarianceMetersSquared: Double,
) {
    private val eastAxis = ConstantVelocityKalmanAxis(
        position = positionX,
        velocity = 0.0,
        positionVariance = positionVarianceMetersSquared,
        velocityVariance = velocityVarianceMetersSquared,
    )
    private val northAxis = ConstantVelocityKalmanAxis(
        position = positionY,
        velocity = 0.0,
        positionVariance = positionVarianceMetersSquared,
        velocityVariance = velocityVarianceMetersSquared,
    )

    var lastTimestampNanos: Long = timestampNanos
        private set

    fun predictTo(
        timestampNanos: Long,
        accelerationEastMetersPerSecondSquared: Double = 0.0,
        accelerationNorthMetersPerSecondSquared: Double = 0.0,
        processAccelerationStdDev: Double,
    ): Double {
        val deltaSeconds = ((timestampNanos - lastTimestampNanos) / 1_000_000_000.0)
            .coerceAtLeast(0.0)
        if (deltaSeconds <= 0.0) {
            return 0.0
        }

        eastAxis.predict(
            deltaSeconds = deltaSeconds,
            accelerationMetersPerSecondSquared = accelerationEastMetersPerSecondSquared,
            processAccelerationStdDev = processAccelerationStdDev,
        )
        northAxis.predict(
            deltaSeconds = deltaSeconds,
            accelerationMetersPerSecondSquared = accelerationNorthMetersPerSecondSquared,
            processAccelerationStdDev = processAccelerationStdDev,
        )
        lastTimestampNanos = timestampNanos
        return deltaSeconds
    }

    fun updatePosition(
        measuredEastMeters: Double,
        measuredNorthMeters: Double,
        measurementVarianceMetersSquared: Double,
    ): Double {
        val eastInnovation = eastAxis.updatePosition(
            positionMeters = measuredEastMeters,
            varianceMetersSquared = measurementVarianceMetersSquared,
        )
        val northInnovation = northAxis.updatePosition(
            positionMeters = measuredNorthMeters,
            varianceMetersSquared = measurementVarianceMetersSquared,
        )
        return sqrt((eastInnovation * eastInnovation) + (northInnovation * northInnovation))
    }

    fun blendVelocity(
        measuredVelocityEastMetersPerSecond: Double,
        measuredVelocityNorthMetersPerSecond: Double,
        measurementVarianceMetersSquared: Double,
    ) {
        eastAxis.updateVelocity(
            velocityMetersPerSecond = measuredVelocityEastMetersPerSecond,
            varianceMetersSquared = measurementVarianceMetersSquared,
        )
        northAxis.updateVelocity(
            velocityMetersPerSecond = measuredVelocityNorthMetersPerSecond,
            varianceMetersSquared = measurementVarianceMetersSquared,
        )
    }

    fun applyVelocityDamping(factor: Double) {
        val boundedFactor = factor.coerceIn(0.0, 1.0)
        eastAxis.scaleVelocity(boundedFactor)
        northAxis.scaleVelocity(boundedFactor)
    }

    fun snapshot(): KalmanFilterSnapshot {
        return KalmanFilterSnapshot(
            eastMeters = eastAxis.position,
            northMeters = northAxis.position,
            velocityEastMetersPerSecond = eastAxis.velocity,
            velocityNorthMetersPerSecond = northAxis.velocity,
            positionStdDevMeters = sqrt((eastAxis.positionVariance + northAxis.positionVariance) / 2.0),
        )
    }
}

internal data class KalmanFilterSnapshot(
    val eastMeters: Double,
    val northMeters: Double,
    val velocityEastMetersPerSecond: Double,
    val velocityNorthMetersPerSecond: Double,
    val positionStdDevMeters: Double,
) {
    val speedMetersPerSecond: Double
        get() = sqrt(
            (velocityEastMetersPerSecond * velocityEastMetersPerSecond) +
                (velocityNorthMetersPerSecond * velocityNorthMetersPerSecond),
        )
}

private class ConstantVelocityKalmanAxis(
    position: Double,
    velocity: Double,
    positionVariance: Double,
    velocityVariance: Double,
) {
    var position: Double = position
        private set
    var velocity: Double = velocity
        private set

    private var covariance00 = positionVariance
    private var covariance01 = 0.0
    private var covariance10 = 0.0
    private var covariance11 = velocityVariance

    val positionVariance: Double
        get() = covariance00

    fun predict(
        deltaSeconds: Double,
        accelerationMetersPerSecondSquared: Double,
        processAccelerationStdDev: Double,
    ) {
        val dt = deltaSeconds
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt3 * dt

        position += (velocity * dt) + (0.5 * accelerationMetersPerSecondSquared * dt2)
        velocity += accelerationMetersPerSecondSquared * dt

        val q = processAccelerationStdDev * processAccelerationStdDev
        val process00 = q * dt4 / 4.0
        val process01 = q * dt3 / 2.0
        val process11 = q * dt2

        val next00 = covariance00 + (dt * (covariance01 + covariance10)) + (dt2 * covariance11) + process00
        val next01 = covariance01 + (dt * covariance11) + process01
        val next10 = covariance10 + (dt * covariance11) + process01
        val next11 = covariance11 + process11

        covariance00 = next00
        covariance01 = next01
        covariance10 = next10
        covariance11 = next11
    }

    fun updatePosition(
        positionMeters: Double,
        varianceMetersSquared: Double,
    ): Double {
        val innovation = positionMeters - position
        val s = covariance00 + varianceMetersSquared
        if (s <= 1e-6) {
            return innovation
        }

        val k0 = covariance00 / s
        val k1 = covariance10 / s
        val previousCovariance00 = covariance00
        val previousCovariance01 = covariance01
        val previousCovariance10 = covariance10
        val previousCovariance11 = covariance11

        position += k0 * innovation
        velocity += k1 * innovation

        covariance00 = (1.0 - k0) * previousCovariance00
        covariance01 = (1.0 - k0) * previousCovariance01
        covariance10 = previousCovariance10 - (k1 * previousCovariance00)
        covariance11 = previousCovariance11 - (k1 * previousCovariance01)
        return innovation
    }

    fun updateVelocity(
        velocityMetersPerSecond: Double,
        varianceMetersSquared: Double,
    ) {
        val innovation = velocityMetersPerSecond - velocity
        val s = covariance11 + varianceMetersSquared
        if (s <= 1e-6) {
            return
        }

        val k0 = covariance01 / s
        val k1 = covariance11 / s
        val previousCovariance00 = covariance00
        val previousCovariance01 = covariance01
        val previousCovariance10 = covariance10
        val previousCovariance11 = covariance11

        position += k0 * innovation
        velocity += k1 * innovation

        covariance00 = previousCovariance00 - (k0 * previousCovariance10)
        covariance01 = previousCovariance01 - (k0 * previousCovariance11)
        covariance10 = (1.0 - k1) * previousCovariance10
        covariance11 = (1.0 - k1) * previousCovariance11
    }

    fun scaleVelocity(factor: Double) {
        velocity *= factor
        covariance11 = (covariance11 / factor.coerceAtLeast(0.25)).coerceAtMost(4_000.0)
    }
}
