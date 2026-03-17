package com.openroute.app.location

import com.openroute.app.data.LatLngPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class LocationFusionEngine {
    private var reference: LocalReference? = null
    private var filter: AdaptiveKalmanFilter2D? = null
    private var lastAcceptedSample: LocationSample? = null
    private var lastAcceptedPoint: LatLngPoint? = null
    private var lastTrackPoint: LatLngPoint? = null
    private var lastVisitedPoint: LatLngPoint? = null
    private var lastPublishedImuTimestampNanos: Long? = null
    private var wallClockOffsetMillis: Double? = null
    private var latestOrientation: OrientationSample? = null
    private var lastResolvedHeadingDegrees: Double? = null
    private var smoothedAccelerationEast = 0.0
    private var smoothedAccelerationNorth = 0.0

    fun reset() {
        reference = null
        filter = null
        lastAcceptedSample = null
        lastAcceptedPoint = null
        lastTrackPoint = null
        lastVisitedPoint = null
        lastPublishedImuTimestampNanos = null
        wallClockOffsetMillis = null
        latestOrientation = null
        lastResolvedHeadingDegrees = null
        smoothedAccelerationEast = 0.0
        smoothedAccelerationNorth = 0.0
    }

    fun updateOrientation(sample: OrientationSample) {
        latestOrientation = sample
    }

    fun updateFromLocation(sample: LocationSample): FusedLocationUpdate? {
        val assessment = LocationSamplePolicy.assess(sample)
        if (!assessment.isAccepted) {
            return null
        }

        val reference = reference ?: LocalReference(
            latitude = sample.latitude,
            longitude = sample.longitude,
        ).also { reference = it }
        val timestampNanos = sample.elapsedRealtimeNanos
            ?: return null
        val rawPoint = LocationSamplePolicy.toPoint(sample)
        val predictedPoint = currentPredictedPointFor(sample)

        if (!LocationSamplePolicy.isPlausibleAgainstHistory(
                sample = sample,
                previousAcceptedPoint = lastAcceptedPoint,
                previousAcceptedSample = lastAcceptedSample,
                predictedPoint = predictedPoint,
            )
        ) {
            return null
        }

        val localMeasurement = rawPoint.toLocalMeters(reference)
        val measurementVariance = LocationSamplePolicy.measurementVarianceMetersSquared(
            sample = sample,
            innovationMeters = predictedPoint?.distanceTo(rawPoint),
        )

        val filter = filter ?: AdaptiveKalmanFilter2D(
            positionX = localMeasurement.eastMeters,
            positionY = localMeasurement.northMeters,
            timestampNanos = timestampNanos,
            positionVarianceMetersSquared = measurementVariance,
            velocityVarianceMetersSquared = INITIAL_VELOCITY_VARIANCE_METERS_SQUARED,
        ).also { filter = it }

        if (filter.lastTimestampNanos != timestampNanos) {
            filter.predictTo(
                timestampNanos = timestampNanos,
                processAccelerationStdDev = GNSS_PROCESS_ACCELERATION_STD_DEV,
            )
        }

        val innovationMeters = filter.updatePosition(
            measuredEastMeters = localMeasurement.eastMeters,
            measuredNorthMeters = localMeasurement.northMeters,
            measurementVarianceMetersSquared = measurementVariance,
        )

        resolveVelocityMeasurement(sample, rawPoint)?.let { velocityMeasurement ->
            val speedVariance = sample.speedAccuracyMetersPerSecond
                ?.toDouble()
                ?.let { accuracy -> max(accuracy * accuracy, 0.45) }
                ?: DEFAULT_VELOCITY_MEASUREMENT_VARIANCE_METERS_SQUARED
            filter.blendVelocity(
                measuredVelocityEastMetersPerSecond = velocityMeasurement.eastMeters,
                measuredVelocityNorthMetersPerSecond = velocityMeasurement.northMeters,
                measurementVarianceMetersSquared = speedVariance,
            )
        }

        wallClockOffsetMillis = sample.capturedAtMillis - (timestampNanos / 1_000_000.0)
        lastAcceptedSample = sample
        val snapshot = filter.snapshot()
        val resolvedHeading = resolveHeadingDegrees(
            snapshot = snapshot,
            sample = sample,
            timestampNanos = timestampNanos,
        )
        val fusedPoint = LocalMetersPoint(
            eastMeters = snapshot.eastMeters,
            northMeters = snapshot.northMeters,
        ).toLatLngPoint(
            reference = reference,
            timestampMillis = sample.capturedAtMillis,
            bearingDegrees = resolvedHeading,
        )
        lastAcceptedPoint = fusedPoint
        lastResolvedHeadingDegrees = resolvedHeading
        smoothedAccelerationEast = 0.0
        smoothedAccelerationNorth = 0.0

        val shouldAppendTrackPoint = LocationSamplePolicy.shouldAppendTrackPoint(
            previousPoint = lastTrackPoint,
            candidatePoint = fusedPoint,
            qualityScore = assessment.qualityScore,
        )
        if (shouldAppendTrackPoint) {
            lastTrackPoint = fusedPoint
        }

        val shouldAppendVisitedPoint = LocationSamplePolicy.shouldAppendVisitedPoint(
            previousPoint = lastVisitedPoint,
            candidatePoint = fusedPoint,
        )
        if (shouldAppendVisitedPoint) {
            lastVisitedPoint = fusedPoint
        }
        lastPublishedImuTimestampNanos = timestampNanos

        val innovationPenalty = if (innovationMeters <= assessment.measurementSigmaMeters) {
            0.0
        } else {
            min(0.22, ((innovationMeters - assessment.measurementSigmaMeters) / innovationMeters) * 0.22)
        }

        return FusedLocationUpdate(
            point = fusedPoint,
            source = FusedLocationSource.Gnss,
            qualityScore = (assessment.qualityScore - innovationPenalty).coerceIn(0.0, 1.0),
            shouldAppendTrackPoint = shouldAppendTrackPoint,
            shouldAppendVisitedPoint = shouldAppendVisitedPoint,
        )
    }

    fun updateFromImu(sample: ImuMotionSample): FusedLocationUpdate? {
        val filter = filter ?: return null
        val reference = reference ?: return null
        val lastAcceptedSample = lastAcceptedSample ?: return null
        val lastAcceptedTimestampNanos = lastAcceptedSample.elapsedRealtimeNanos ?: return null
        if (sample.timestampNanos <= filter.lastTimestampNanos) {
            return null
        }

        val elapsedSinceGnssMillis = (sample.timestampNanos - lastAcceptedTimestampNanos) / 1_000_000.0
        if (elapsedSinceGnssMillis > MAX_IMU_BRIDGE_MILLIS) {
            return null
        }

        val clippedEast = sample.accelerationEastMetersPerSecondSquared
            .coerceIn(-MAX_IMU_ACCELERATION_METERS_PER_SECOND_SQUARED, MAX_IMU_ACCELERATION_METERS_PER_SECOND_SQUARED)
        val clippedNorth = sample.accelerationNorthMetersPerSecondSquared
            .coerceIn(-MAX_IMU_ACCELERATION_METERS_PER_SECOND_SQUARED, MAX_IMU_ACCELERATION_METERS_PER_SECOND_SQUARED)
        smoothedAccelerationEast = (smoothedAccelerationEast * IMU_ACCELERATION_SMOOTHING) +
            (clippedEast * (1.0 - IMU_ACCELERATION_SMOOTHING))
        smoothedAccelerationNorth = (smoothedAccelerationNorth * IMU_ACCELERATION_SMOOTHING) +
            (clippedNorth * (1.0 - IMU_ACCELERATION_SMOOTHING))

        val deltaSeconds = filter.predictTo(
            timestampNanos = sample.timestampNanos,
            accelerationEastMetersPerSecondSquared = smoothedAccelerationEast,
            accelerationNorthMetersPerSecondSquared = smoothedAccelerationNorth,
            processAccelerationStdDev = IMU_PROCESS_ACCELERATION_STD_DEV,
        )
        if (deltaSeconds <= 0.0) {
            return null
        }

        val dampingFactor = if (hypot(smoothedAccelerationEast, smoothedAccelerationNorth) < IMU_STILL_ACCELERATION_THRESHOLD) {
            exp(-deltaSeconds * 0.65)
        } else {
            exp(-deltaSeconds * 0.10)
        }
        filter.applyVelocityDamping(dampingFactor)

        val previousPublishedImuTimestampNanos = lastPublishedImuTimestampNanos
        if (previousPublishedImuTimestampNanos != null &&
            sample.timestampNanos - previousPublishedImuTimestampNanos < MIN_IMU_PUBLISH_INTERVAL_NANOS
        ) {
            return null
        }

        val timestampMillis = wallClockOffsetMillis
            ?.let { offset -> (offset + (sample.timestampNanos / 1_000_000.0)).toLong() }
            ?: return null
        val snapshot = filter.snapshot()
        val resolvedHeading = resolveHeadingDegrees(
            snapshot = snapshot,
            sample = lastAcceptedSample,
            timestampNanos = sample.timestampNanos,
        )
        val fusedPoint = LocalMetersPoint(
            eastMeters = snapshot.eastMeters,
            northMeters = snapshot.northMeters,
        ).toLatLngPoint(
            reference = reference,
            timestampMillis = timestampMillis,
            bearingDegrees = resolvedHeading,
        )
        if (!LocationSamplePolicy.shouldAppendVisitedPoint(
                previousPoint = lastVisitedPoint,
                candidatePoint = fusedPoint,
            )
        ) {
            lastPublishedImuTimestampNanos = sample.timestampNanos
            return FusedLocationUpdate(
                point = fusedPoint,
                source = FusedLocationSource.ImuBridge,
                qualityScore = IMU_QUALITY_SCORE,
                shouldAppendTrackPoint = false,
                shouldAppendVisitedPoint = false,
            )
        }

        lastVisitedPoint = fusedPoint
        lastPublishedImuTimestampNanos = sample.timestampNanos
        lastResolvedHeadingDegrees = resolvedHeading

        return FusedLocationUpdate(
            point = fusedPoint,
            source = FusedLocationSource.ImuBridge,
            qualityScore = IMU_QUALITY_SCORE,
            shouldAppendTrackPoint = false,
            shouldAppendVisitedPoint = true,
        )
    }

    private fun currentPredictedPointFor(sample: LocationSample): LatLngPoint? {
        val filter = filter ?: return null
        val reference = reference ?: return null
        val timestampNanos = sample.elapsedRealtimeNanos ?: return null
        val lastTimestampNanos = filter.lastTimestampNanos
        val deltaSeconds = ((timestampNanos - lastTimestampNanos) / 1_000_000_000.0).coerceAtLeast(0.0)
        val snapshot = filter.snapshot()
        val predictedPoint = LocalMetersPoint(
            eastMeters = snapshot.eastMeters + (snapshot.velocityEastMetersPerSecond * deltaSeconds),
            northMeters = snapshot.northMeters + (snapshot.velocityNorthMetersPerSecond * deltaSeconds),
        ).toLatLngPoint(
            reference = reference,
            timestampMillis = sample.capturedAtMillis,
            bearingDegrees = lastResolvedHeadingDegrees,
        )
        return predictedPoint
    }

    private fun resolveVelocityMeasurement(
        sample: LocationSample,
        rawPoint: LatLngPoint,
    ): LocalMetersPoint? {
        if (sample.speedMetersPerSecond != null && sample.bearingDegrees != null) {
            return headingToVelocity(
                speedMetersPerSecond = sample.speedMetersPerSecond,
                headingDegrees = sample.bearingDegrees,
            )
        }

        val previousPoint = lastAcceptedPoint ?: return null
        val previousTimestamp = lastAcceptedSample?.capturedAtMillis ?: return null
        val deltaSeconds = ((sample.capturedAtMillis - previousTimestamp) / 1000.0).coerceAtLeast(0.0)
        if (deltaSeconds < MIN_VELOCITY_DELTA_SECONDS) {
            return null
        }

        val reference = reference ?: return null
        val currentLocal = rawPoint.toLocalMeters(reference)
        val previousLocal = previousPoint.toLocalMeters(reference)
        return LocalMetersPoint(
            eastMeters = (currentLocal.eastMeters - previousLocal.eastMeters) / deltaSeconds,
            northMeters = (currentLocal.northMeters - previousLocal.northMeters) / deltaSeconds,
        )
    }

    private fun resolveHeadingDegrees(
        snapshot: KalmanFilterSnapshot,
        sample: LocationSample,
        timestampNanos: Long,
    ): Double? {
        if (snapshot.speedMetersPerSecond >= MIN_HEADING_SPEED_METERS_PER_SECOND) {
            return velocityToHeadingDegrees(
                eastMetersPerSecond = snapshot.velocityEastMetersPerSecond,
                northMetersPerSecond = snapshot.velocityNorthMetersPerSecond,
            )
        }

        latestOrientation
            ?.takeIf { orientation -> abs(timestampNanos - orientation.timestampNanos) <= MAX_ORIENTATION_STALENESS_NANOS }
            ?.headingDegrees
            ?.let { return it }

        sample.bearingDegrees?.let { return it.normalizeHeadingDegrees() }
        return lastResolvedHeadingDegrees
    }

    private fun headingToVelocity(
        speedMetersPerSecond: Double,
        headingDegrees: Double,
    ): LocalMetersPoint {
        val headingRadians = headingDegrees.toRadians()
        return LocalMetersPoint(
            eastMeters = speedMetersPerSecond * sin(headingRadians),
            northMeters = speedMetersPerSecond * cos(headingRadians),
        )
    }

    private fun velocityToHeadingDegrees(
        eastMetersPerSecond: Double,
        northMetersPerSecond: Double,
    ): Double {
        return Math.toDegrees(kotlin.math.atan2(eastMetersPerSecond, northMetersPerSecond))
            .normalizeHeadingDegrees()
    }

    private data class LocalReference(
        val latitude: Double,
        val longitude: Double,
    )

    private data class LocalMetersPoint(
        val eastMeters: Double,
        val northMeters: Double,
    ) {
        fun toLatLngPoint(
            reference: LocalReference,
            timestampMillis: Long?,
            bearingDegrees: Double?,
        ): LatLngPoint {
            val metersPerDegreeLatitude = EARTH_RADIUS_METERS * PI / 180.0
            val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(Math.toRadians(reference.latitude))
            return LatLngPoint(
                latitude = reference.latitude + (northMeters / metersPerDegreeLatitude),
                longitude = reference.longitude + (eastMeters / metersPerDegreeLongitude),
                timestampMillis = timestampMillis,
                bearingDegrees = bearingDegrees,
            )
        }
    }

    private fun LatLngPoint.toLocalMeters(reference: LocalReference): LocalMetersPoint {
        val metersPerDegreeLatitude = EARTH_RADIUS_METERS * PI / 180.0
        val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(reference.latitude.toRadians())
        return LocalMetersPoint(
            eastMeters = (longitude - reference.longitude) * metersPerDegreeLongitude,
            northMeters = (latitude - reference.latitude) * metersPerDegreeLatitude,
        )
    }

    private fun LatLngPoint.distanceTo(other: LatLngPoint): Double {
        val latitudeDeltaRadians = (other.latitude - latitude).toRadians()
        val longitudeDeltaRadians = (other.longitude - longitude).toRadians()
        val startLatitudeRadians = latitude.toRadians()
        val endLatitudeRadians = other.latitude.toRadians()
        val a = sin(latitudeDeltaRadians / 2).let { it * it } +
            cos(startLatitudeRadians) *
            cos(endLatitudeRadians) *
            sin(longitudeDeltaRadians / 2).let { it * it }

        return 2.0 * EARTH_RADIUS_METERS * kotlin.math.asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    private fun Double.normalizeHeadingDegrees(): Double {
        return ((this % 360.0) + 360.0) % 360.0
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val INITIAL_VELOCITY_VARIANCE_METERS_SQUARED = 64.0
        const val DEFAULT_VELOCITY_MEASUREMENT_VARIANCE_METERS_SQUARED = 4.0
        const val GNSS_PROCESS_ACCELERATION_STD_DEV = 1.8
        const val IMU_PROCESS_ACCELERATION_STD_DEV = 2.6
        const val MAX_IMU_BRIDGE_MILLIS = 4_500.0
        const val MAX_IMU_ACCELERATION_METERS_PER_SECOND_SQUARED = 4.2
        const val IMU_ACCELERATION_SMOOTHING = 0.82
        const val IMU_STILL_ACCELERATION_THRESHOLD = 0.22
        const val MIN_HEADING_SPEED_METERS_PER_SECOND = 0.85
        const val MIN_VELOCITY_DELTA_SECONDS = 0.7
        const val MAX_ORIENTATION_STALENESS_NANOS = 2_000_000_000L
        const val MIN_IMU_PUBLISH_INTERVAL_NANOS = 250_000_000L
        const val IMU_QUALITY_SCORE = 0.55
    }
}
