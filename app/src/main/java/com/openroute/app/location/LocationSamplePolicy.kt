package com.openroute.app.location

import android.location.Location
import android.location.LocationManager
import com.openroute.app.data.LatLngPoint
import com.openroute.app.data.distanceMeters
import kotlin.math.max
import kotlin.math.min

internal data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val capturedAtMillis: Long,
    val receivedAtMillis: Long,
    val elapsedRealtimeNanos: Long? = null,
    val provider: String? = null,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Double? = null,
    val speedAccuracyMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
    val bearingAccuracyDegrees: Double? = null,
)

internal data class OrientationSample(
    val timestampNanos: Long,
    val headingDegrees: Double,
)

internal data class ImuMotionSample(
    val timestampNanos: Long,
    val accelerationEastMetersPerSecondSquared: Double,
    val accelerationNorthMetersPerSecondSquared: Double,
)

internal enum class FusedLocationSource {
    Gnss,
    ImuBridge,
}

internal data class FusedLocationUpdate(
    val point: LatLngPoint,
    val source: FusedLocationSource,
    val qualityScore: Double,
    val shouldAppendTrackPoint: Boolean,
    val shouldAppendVisitedPoint: Boolean,
)

internal data class LocationSampleAssessment(
    val isAccepted: Boolean,
    val qualityScore: Double,
    val measurementSigmaMeters: Double,
)

internal object LocationSamplePolicy {
    private const val MAX_SAMPLE_AGE_MILLIS = 15_000L
    private const val MAX_ACCEPTED_ACCURACY_METERS = 75.0
    private const val MIN_MEASUREMENT_SIGMA_METERS = 3.0
    private const val DEFAULT_MEASUREMENT_SIGMA_METERS = 16.0
    private const val MIN_TRACK_SEGMENT_DISTANCE_METERS = 4.0
    private const val MIN_VISITED_SEGMENT_DISTANCE_METERS = 1.8
    private const val MIN_VISITED_SEGMENT_TIME_MILLIS = 800L
    private const val MAX_REALISTIC_SPEED_METERS_PER_SECOND = 24.0
    private const val ABSOLUTE_OUTLIER_DISTANCE_METERS = 180.0

    fun toPoint(
        sample: LocationSample,
        bearingDegrees: Double? = sample.bearingDegrees,
    ): LatLngPoint {
        return LatLngPoint(
            latitude = sample.latitude,
            longitude = sample.longitude,
            timestampMillis = sample.capturedAtMillis,
            bearingDegrees = bearingDegrees,
        )
    }

    fun assess(sample: LocationSample): LocationSampleAssessment {
        val ageMillis = sample.receivedAtMillis - sample.capturedAtMillis
        if (ageMillis > MAX_SAMPLE_AGE_MILLIS) {
            return LocationSampleAssessment(
                isAccepted = false,
                qualityScore = 0.0,
                measurementSigmaMeters = measurementSigmaMeters(sample, qualityScore = 0.0),
            )
        }

        val accuracyMeters = sample.accuracyMeters?.toDouble()
        if (accuracyMeters != null && accuracyMeters > MAX_ACCEPTED_ACCURACY_METERS) {
            return LocationSampleAssessment(
                isAccepted = false,
                qualityScore = 0.0,
                measurementSigmaMeters = measurementSigmaMeters(sample, qualityScore = 0.0),
            )
        }

        val qualityScore = qualityScore(sample)
        return LocationSampleAssessment(
            isAccepted = qualityScore >= MIN_ACCEPTED_QUALITY_SCORE,
            qualityScore = qualityScore,
            measurementSigmaMeters = measurementSigmaMeters(sample, qualityScore),
        )
    }

    fun isUsable(sample: LocationSample): Boolean = assess(sample).isAccepted

    fun isPlausibleAgainstHistory(
        sample: LocationSample,
        previousAcceptedPoint: LatLngPoint?,
        previousAcceptedSample: LocationSample?,
        predictedPoint: LatLngPoint?,
    ): Boolean {
        val rawPoint = toPoint(sample)
        val accuracyAllowanceMeters = max(
            sample.accuracyMeters?.toDouble()?.times(2.5) ?: DEFAULT_MEASUREMENT_SIGMA_METERS,
            14.0,
        )

        if (predictedPoint != null) {
            val innovationMeters = rawPoint.distanceTo(predictedPoint)
            if (innovationMeters > max(ABSOLUTE_OUTLIER_DISTANCE_METERS, accuracyAllowanceMeters * 4.0)) {
                return false
            }
        }

        if (previousAcceptedPoint == null || previousAcceptedSample == null) {
            return true
        }

        val elapsedSeconds = ((sample.capturedAtMillis - previousAcceptedSample.capturedAtMillis) / 1000.0)
            .coerceAtLeast(0.0)
        if (elapsedSeconds <= 0.0) {
            return false
        }

        val travelledDistanceMeters = rawPoint.distanceTo(previousAcceptedPoint)
        val feasibleDistanceMeters = (MAX_REALISTIC_SPEED_METERS_PER_SECOND * elapsedSeconds) + accuracyAllowanceMeters
        return travelledDistanceMeters <= feasibleDistanceMeters
    }

    fun shouldAppendTrackPoint(
        previousPoint: LatLngPoint?,
        candidatePoint: LatLngPoint,
        qualityScore: Double,
    ): Boolean {
        if (previousPoint == null) {
            return true
        }

        val distanceMeters = previousPoint.distanceTo(candidatePoint)
        val elapsedMillis = elapsedMillisBetween(previousPoint, candidatePoint)
        val minDistanceMeters = (MIN_TRACK_SEGMENT_DISTANCE_METERS + ((1.0 - qualityScore) * 3.5))
            .coerceIn(MIN_TRACK_SEGMENT_DISTANCE_METERS, 8.0)

        return distanceMeters >= minDistanceMeters ||
            (elapsedMillis >= 12_000L && distanceMeters >= 2.0)
    }

    fun shouldAppendVisitedPoint(
        previousPoint: LatLngPoint?,
        candidatePoint: LatLngPoint,
    ): Boolean {
        if (previousPoint == null) {
            return true
        }

        val distanceMeters = previousPoint.distanceTo(candidatePoint)
        val elapsedMillis = elapsedMillisBetween(previousPoint, candidatePoint)
        return distanceMeters >= MIN_VISITED_SEGMENT_DISTANCE_METERS ||
            elapsedMillis >= MIN_VISITED_SEGMENT_TIME_MILLIS
    }

    fun measurementVarianceMetersSquared(
        sample: LocationSample,
        innovationMeters: Double? = null,
    ): Double {
        val assessment = assess(sample)
        val sigmaMeters = assessment.measurementSigmaMeters
        val adaptiveInflation = when {
            innovationMeters == null -> 1.0
            innovationMeters <= sigmaMeters -> 1.0
            else -> min(4.0, 1.0 + ((innovationMeters / sigmaMeters) - 1.0) * 0.45)
        }

        return (sigmaMeters * adaptiveInflation).let { it * it }
    }

    fun qualityScore(sample: LocationSample): Double {
        val accuracyScore = sample.accuracyMeters
            ?.toDouble()
            ?.let { accuracy ->
                ((MAX_ACCEPTED_ACCURACY_METERS - accuracy) / (MAX_ACCEPTED_ACCURACY_METERS - 3.0))
                    .coerceIn(0.0, 1.0)
            }
            ?: 0.45
        val ageMillis = (sample.receivedAtMillis - sample.capturedAtMillis).coerceAtLeast(0L)
        val ageScore = (1.0 - (ageMillis / MAX_SAMPLE_AGE_MILLIS.toDouble())).coerceIn(0.0, 1.0)
        val providerScore = when (sample.provider) {
            LocationManager.GPS_PROVIDER -> 1.0
            LocationManager.NETWORK_PROVIDER -> 0.72
            LocationManager.PASSIVE_PROVIDER -> 0.55
            else -> 0.65
        }
        val speedScore = sample.speedAccuracyMetersPerSecond
            ?.let { accuracy -> (1.0 - (accuracy / 4.5)).coerceIn(0.0, 1.0) }
            ?: sample.speedMetersPerSecond?.let { 0.7 }
            ?: 0.4
        val bearingScore = sample.bearingAccuracyDegrees
            ?.let { accuracy -> (1.0 - (accuracy / 48.0)).coerceIn(0.0, 1.0) }
            ?: sample.bearingDegrees?.let { 0.7 }
            ?: 0.4

        return (
            (accuracyScore * 0.48) +
                (ageScore * 0.18) +
                (providerScore * 0.16) +
                (speedScore * 0.10) +
                (bearingScore * 0.08)
            ).coerceIn(0.0, 1.0)
    }

    private fun measurementSigmaMeters(
        sample: LocationSample,
        qualityScore: Double,
    ): Double {
        val baseSigmaMeters = sample.accuracyMeters?.toDouble() ?: DEFAULT_MEASUREMENT_SIGMA_METERS
        val providerInflation = when (sample.provider) {
            LocationManager.GPS_PROVIDER -> 1.0
            LocationManager.NETWORK_PROVIDER -> 1.3
            LocationManager.PASSIVE_PROVIDER -> 1.4
            else -> 1.15
        }
        val qualityInflation = 1.0 + ((1.0 - qualityScore) * 0.9)
        return (baseSigmaMeters * providerInflation * qualityInflation)
            .coerceIn(MIN_MEASUREMENT_SIGMA_METERS, MAX_ACCEPTED_ACCURACY_METERS * 1.5)
    }

    private fun elapsedMillisBetween(
        previousPoint: LatLngPoint,
        candidatePoint: LatLngPoint,
    ): Long {
        val previousTimestamp = previousPoint.timestampMillis ?: return Long.MAX_VALUE
        val candidateTimestamp = candidatePoint.timestampMillis ?: return Long.MAX_VALUE
        return candidateTimestamp - previousTimestamp
    }

    private const val MIN_ACCEPTED_QUALITY_SCORE = 0.24
}

internal fun Location.toLocationSample(receivedAtMillis: Long = System.currentTimeMillis()): LocationSample {
    val capturedAtMillis = time.takeIf { it > 0L } ?: receivedAtMillis
    return LocationSample(
        latitude = latitude,
        longitude = longitude,
        capturedAtMillis = capturedAtMillis,
        receivedAtMillis = receivedAtMillis,
        elapsedRealtimeNanos = elapsedRealtimeNanos.takeIf { it > 0L },
        provider = provider,
        accuracyMeters = accuracy.takeIf { hasAccuracy() },
        speedMetersPerSecond = speed.takeIf { hasSpeed() }?.toDouble(),
        speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond
            .takeIf { hasSpeedAccuracy() }
            ?.toDouble(),
        bearingDegrees = bearing.takeIf { hasBearing() }?.toDouble(),
        bearingAccuracyDegrees = bearingAccuracyDegrees
            .takeIf { hasBearingAccuracy() }
            ?.toDouble(),
    )
}

internal fun LocationManager.preferredRouteProviders(): List<String> {
    return buildList {
        if (isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            add(LocationManager.GPS_PROVIDER)
        }
        if (isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            add(LocationManager.NETWORK_PROVIDER)
        }
        if (isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
            add(LocationManager.PASSIVE_PROVIDER)
        }
    }
}

internal fun LocationManager.freshestLastKnownLocation(providers: List<String>): Location? {
    return providers
        .mapNotNull { provider -> runCatching { getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull(Location::getTime)
}

private fun LatLngPoint.distanceTo(other: LatLngPoint): Double {
    return listOf(this, other).distanceMeters()
}
