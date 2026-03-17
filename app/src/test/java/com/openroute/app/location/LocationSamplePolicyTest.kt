package com.openroute.app.location

import com.openroute.app.data.LatLngPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSamplePolicyTest {
    @Test
    fun `accepts a fresh accurate first sample`() {
        val sample = sample(
            latitude = 40.4168,
            longitude = -3.7038,
            capturedAtMillis = 10_000L,
            receivedAtMillis = 12_000L,
            accuracyMeters = 8f,
        )

        assertTrue(LocationSamplePolicy.isUsable(sample))
        assertTrue(LocationSamplePolicy.shouldAppend(previousPoint = null, sample = sample))
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
        assertFalse(LocationSamplePolicy.shouldAppend(previousPoint = null, sample = sample))
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
    fun `rejects tiny movement that only adds GPS jitter`() {
        val previousPoint = LatLngPoint(
            latitude = 40.4168,
            longitude = -3.7038,
            timestampMillis = 10_000L,
        )
        val sample = sample(
            latitude = 40.41681,
            longitude = -3.7038,
            capturedAtMillis = 14_000L,
            receivedAtMillis = 14_500L,
            accuracyMeters = 6f,
        )

        assertFalse(LocationSamplePolicy.shouldAppend(previousPoint, sample))
    }

    @Test
    fun `rejects unrealistic jumps between consecutive points`() {
        val previousPoint = LatLngPoint(
            latitude = 40.4168,
            longitude = -3.7038,
            timestampMillis = 10_000L,
        )
        val sample = sample(
            latitude = 40.4268,
            longitude = -3.7038,
            capturedAtMillis = 20_000L,
            receivedAtMillis = 20_500L,
            accuracyMeters = 5f,
        )

        assertFalse(LocationSamplePolicy.shouldAppend(previousPoint, sample))
    }

    private fun sample(
        latitude: Double,
        longitude: Double,
        capturedAtMillis: Long,
        receivedAtMillis: Long,
        accuracyMeters: Float,
    ): LocationSample {
        return LocationSample(
            latitude = latitude,
            longitude = longitude,
            capturedAtMillis = capturedAtMillis,
            receivedAtMillis = receivedAtMillis,
            accuracyMeters = accuracyMeters,
        )
    }
}
