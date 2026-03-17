package com.openroute.app.location

import android.location.Location
import android.location.LocationManager
import com.openroute.app.data.LatLngPoint
import com.openroute.app.data.distanceMeters

internal data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val capturedAtMillis: Long,
    val receivedAtMillis: Long,
    val accuracyMeters: Float? = null,
    val bearingDegrees: Double? = null,
)

internal object LocationSamplePolicy {
    private const val MAX_SAMPLE_AGE_MILLIS = 20_000L
    private const val MAX_ACCEPTED_ACCURACY_METERS = 45f
    private const val MIN_TRACK_SEGMENT_DISTANCE_METERS = 5.0
    private const val MAX_REALISTIC_SPEED_METERS_PER_SECOND = 22.0

    fun toPoint(sample: LocationSample): LatLngPoint {
        return LatLngPoint(
            latitude = sample.latitude,
            longitude = sample.longitude,
            timestampMillis = sample.capturedAtMillis,
            bearingDegrees = sample.bearingDegrees,
        )
    }

    fun isUsable(sample: LocationSample): Boolean {
        val ageMillis = sample.receivedAtMillis - sample.capturedAtMillis
        if (ageMillis > MAX_SAMPLE_AGE_MILLIS) {
            return false
        }

        val accuracyMeters = sample.accuracyMeters
        if (accuracyMeters != null && accuracyMeters > MAX_ACCEPTED_ACCURACY_METERS) {
            return false
        }

        return true
    }

    fun shouldAppend(previousPoint: LatLngPoint?, sample: LocationSample): Boolean {
        if (!isUsable(sample)) {
            return false
        }

        if (previousPoint == null) {
            return true
        }

        val previousTimestamp = previousPoint.timestampMillis ?: return true
        if (sample.capturedAtMillis <= previousTimestamp) {
            return false
        }

        val candidatePoint = toPoint(sample)
        val segmentDistanceMeters = listOf(previousPoint, candidatePoint).distanceMeters()
        if (segmentDistanceMeters < MIN_TRACK_SEGMENT_DISTANCE_METERS) {
            return false
        }

        val elapsedSeconds = (sample.capturedAtMillis - previousTimestamp) / 1000.0
        if (elapsedSeconds <= 0.0) {
            return false
        }

        val segmentSpeedMetersPerSecond = segmentDistanceMeters / elapsedSeconds
        return segmentSpeedMetersPerSecond <= MAX_REALISTIC_SPEED_METERS_PER_SECOND
    }
}

internal fun Location.toLocationSample(receivedAtMillis: Long = System.currentTimeMillis()): LocationSample {
    val capturedAtMillis = time.takeIf { it > 0L } ?: receivedAtMillis
    return LocationSample(
        latitude = latitude,
        longitude = longitude,
        capturedAtMillis = capturedAtMillis,
        receivedAtMillis = receivedAtMillis,
        accuracyMeters = accuracy.takeIf { hasAccuracy() },
        bearingDegrees = bearing.takeIf { hasBearing() }?.toDouble(),
    )
}

internal fun LocationManager.preferredRouteProviders(): List<String> {
    return when {
        isProviderEnabled(LocationManager.GPS_PROVIDER) -> listOf(LocationManager.GPS_PROVIDER)
        isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> listOf(LocationManager.NETWORK_PROVIDER)
        else -> emptyList()
    }
}

internal fun LocationManager.freshestLastKnownLocation(providers: List<String>): Location? {
    return providers
        .mapNotNull { provider -> runCatching { getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull(Location::getTime)
}
