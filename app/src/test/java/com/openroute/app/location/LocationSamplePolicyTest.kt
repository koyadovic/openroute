package com.openroute.app.location

import android.location.LocationManager
import com.openroute.app.data.LatLngPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSamplePolicyTest {
    @Test
    fun `accepts a fresh accurate gps sample`() {
        val sample = sample(
            latitude = 40.4168,
            longitude = -3.7038,
            capturedAtMillis = 10_000L,
            receivedAtMillis = 12_000L,
            accuracyMeters = 8f,
            provider = LocationManager.GPS_PROVIDER,
        )

        val assessment = LocationSamplePolicy.assess(sample)

        assertTrue(assessment.isAccepted)
        assertTrue(assessment.qualityScore > 0.5)
    }

    @Test
    fun `rejects stale samples`() {
        val sample = sample(
            latitude = 40.4168,
            longitude = -3.7038,
            capturedAtMillis = 10_000L,
            receivedAtMillis = 40_500L,
            accuracyMeters = 8f,
        )

        assertFalse(LocationSamplePolicy.isUsable(sample))
    }

    @Test
    fun `rejects inaccurate samples`() {
        val sample = sample(
            latitude = 40.4168,
            longitude = -3.7038,
            capturedAtMillis = 10_000L,
            receivedAtMillis = 12_000L,
            accuracyMeters = 120f,
        )

        assertFalse(LocationSamplePolicy.isUsable(sample))
    }

    @Test
    fun `rejects implausible jump against recent history`() {
        val previousPoint = LatLngPoint(
            latitude = 40.4168,
            longitude = -3.7038,
            timestampMillis = 10_000L,
        )
        val previousSample = sample(
            latitude = 40.4168,
            longitude = -3.7038,
            capturedAtMillis = 10_000L,
            receivedAtMillis = 10_500L,
            accuracyMeters = 5f,
        )
        val outlier = sample(
            latitude = 40.4308,
            longitude = -3.7038,
            capturedAtMillis = 14_000L,
            receivedAtMillis = 14_200L,
            accuracyMeters = 5f,
        )

        assertFalse(
            LocationSamplePolicy.isPlausibleAgainstHistory(
                sample = outlier,
                previousAcceptedPoint = previousPoint,
                previousAcceptedSample = previousSample,
                predictedPoint = previousPoint,
            ),
        )
    }

    @Test
    fun `avoids appending tiny movement as recorded point`() {
        val previousPoint = LatLngPoint(
            latitude = 40.4168,
            longitude = -3.7038,
            timestampMillis = 10_000L,
        )
        val candidatePoint = LatLngPoint(
            latitude = 40.41681,
            longitude = -3.7038,
            timestampMillis = 12_000L,
        )

        assertFalse(
            LocationSamplePolicy.shouldAppendTrackPoint(
                previousPoint = previousPoint,
                candidatePoint = candidatePoint,
                qualityScore = 0.9,
            ),
        )
    }

    @Test
    fun `appends visited point on meaningful movement`() {
        val previousPoint = LatLngPoint(
            latitude = 40.4168,
            longitude = -3.7038,
            timestampMillis = 10_000L,
        )
        val candidatePoint = LatLngPoint(
            latitude = 40.41682,
            longitude = -3.7038,
            timestampMillis = 11_500L,
        )

        assertTrue(
            LocationSamplePolicy.shouldAppendVisitedPoint(
                previousPoint = previousPoint,
                candidatePoint = candidatePoint,
            ),
        )
    }

    private fun sample(
        latitude: Double,
        longitude: Double,
        capturedAtMillis: Long,
        receivedAtMillis: Long,
        accuracyMeters: Float,
        provider: String = LocationManager.GPS_PROVIDER,
    ): LocationSample {
        return LocationSample(
            latitude = latitude,
            longitude = longitude,
            capturedAtMillis = capturedAtMillis,
            receivedAtMillis = receivedAtMillis,
            elapsedRealtimeNanos = capturedAtMillis * 1_000_000L,
            provider = provider,
            accuracyMeters = accuracyMeters,
        )
    }
}
