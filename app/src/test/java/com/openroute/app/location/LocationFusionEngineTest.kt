package com.openroute.app.location

import android.location.LocationManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFusionEngineTest {
    @Test
    fun `rejects a far noisy network fix after a good gps fix`() {
        val engine = LocationFusionEngine()
        val firstFix = engine.updateFromLocation(
            sample(
                latitude = 40.416800,
                longitude = -3.703800,
                capturedAtMillis = 1_000L,
                accuracyMeters = 5f,
                provider = LocationManager.GPS_PROVIDER,
            ),
        )
        assertNotNull(firstFix)

        val rejectedFix = engine.updateFromLocation(
            sample(
                latitude = 40.425000,
                longitude = -3.703800,
                capturedAtMillis = 4_000L,
                accuracyMeters = 35f,
                provider = LocationManager.NETWORK_PROVIDER,
            ),
        )

        assertNull(rejectedFix)
    }

    @Test
    fun `smooths noisy gnss samples along a straight path`() {
        val engine = LocationFusionEngine()
        val first = engine.updateFromLocation(
            sample(
                latitude = 40.416800,
                longitude = -3.703800,
                capturedAtMillis = 1_000L,
                accuracyMeters = 6f,
            ),
        )
        val second = engine.updateFromLocation(
            sample(
                latitude = 40.416890,
                longitude = -3.703770,
                capturedAtMillis = 3_000L,
                accuracyMeters = 6f,
                speedMetersPerSecond = 4.2,
                bearingDegrees = 8.0,
            ),
        )
        val third = engine.updateFromLocation(
            sample(
                latitude = 40.416980,
                longitude = -3.703840,
                capturedAtMillis = 5_000L,
                accuracyMeters = 6f,
                speedMetersPerSecond = 4.1,
                bearingDegrees = 5.0,
            ),
        )

        assertNotNull(first)
        assertNotNull(second)
        assertNotNull(third)

        val fusedLongitude = third?.point?.longitude ?: 0.0
        assertTrue(fusedLongitude > -3.70384)
        assertTrue(fusedLongitude < -3.70377)
    }

    @Test
    fun `bridges short gnss gaps with imu predictions`() {
        val engine = LocationFusionEngine()
        val firstFix = engine.updateFromLocation(
            sample(
                latitude = 40.416800,
                longitude = -3.703800,
                capturedAtMillis = 1_000L,
                accuracyMeters = 5f,
                speedMetersPerSecond = 4.0,
                bearingDegrees = 0.0,
            ),
        )
        val secondFix = engine.updateFromLocation(
            sample(
                latitude = 40.416836,
                longitude = -3.703800,
                capturedAtMillis = 2_000L,
                accuracyMeters = 5f,
                speedMetersPerSecond = 4.0,
                bearingDegrees = 0.0,
            ),
        )
        engine.updateOrientation(
            OrientationSample(
                timestampNanos = 2_200_000_000L,
                headingDegrees = 0.0,
            ),
        )

        val imuPrediction = engine.updateFromImu(
            ImuMotionSample(
                timestampNanos = 2_350_000_000L,
                accelerationEastMetersPerSecondSquared = 0.0,
                accelerationNorthMetersPerSecondSquared = 0.0,
            ),
        )

        assertNotNull(firstFix)
        assertNotNull(secondFix)
        assertNotNull(imuPrediction)
        assertTrue(imuPrediction?.source == FusedLocationSource.ImuBridge)
        assertTrue((imuPrediction?.point?.latitude ?: 0.0) > (secondFix?.point?.latitude ?: 0.0))
    }

    @Test
    fun `reacquires tracking after a prolonged gps gap`() {
        val engine = LocationFusionEngine()
        val firstFix = engine.updateFromLocation(
            sample(
                latitude = 40.416800,
                longitude = -3.703800,
                capturedAtMillis = 1_000L,
                accuracyMeters = 5f,
                speedMetersPerSecond = 4.0,
                bearingDegrees = 0.0,
            ),
        )

        val recoveredFix = engine.updateFromLocation(
            sample(
                latitude = 40.420400,
                longitude = -3.703800,
                capturedAtMillis = 61_000L,
                accuracyMeters = 6f,
                speedMetersPerSecond = 6.0,
                bearingDegrees = 0.0,
            ),
        )

        assertNotNull(firstFix)
        assertNotNull(recoveredFix)
        assertTrue((recoveredFix?.point?.latitude ?: 0.0) > 40.418)
    }

    private fun sample(
        latitude: Double,
        longitude: Double,
        capturedAtMillis: Long,
        accuracyMeters: Float,
        provider: String = LocationManager.GPS_PROVIDER,
        speedMetersPerSecond: Double? = null,
        bearingDegrees: Double? = null,
    ): LocationSample {
        return LocationSample(
            latitude = latitude,
            longitude = longitude,
            capturedAtMillis = capturedAtMillis,
            receivedAtMillis = capturedAtMillis + 150L,
            elapsedRealtimeNanos = capturedAtMillis * 1_000_000L,
            provider = provider,
            accuracyMeters = accuracyMeters,
            speedMetersPerSecond = speedMetersPerSecond,
            speedAccuracyMetersPerSecond = speedMetersPerSecond?.let { 0.6 },
            bearingDegrees = bearingDegrees,
            bearingAccuracyDegrees = bearingDegrees?.let { 12.0 },
        )
    }
}
