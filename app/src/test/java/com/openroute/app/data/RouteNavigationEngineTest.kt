package com.openroute.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteNavigationEngineTest {
    @Test
    fun `calculates progress and eta from route and recent samples`() {
        val route = RouteTrack(
            id = "route-1",
            name = "Loop",
            source = RouteSource.IMPORTED_GPX,
            createdAtMillis = 1L,
            distanceMeters = 0.0,
            points = listOf(
                LatLngPoint(40.0, -3.0),
                LatLngPoint(40.001, -3.0),
                LatLngPoint(40.002, -3.0),
            ),
        )
        val recentLocations = listOf(
            LatLngPoint(40.0, -3.0, timestampMillis = 0L),
            LatLngPoint(40.0005, -3.0, timestampMillis = 30_000L),
            LatLngPoint(40.001, -3.0, timestampMillis = 60_000L),
        )

        val progress = RouteNavigationEngine.calculate(
            route = route,
            currentLocation = recentLocations.last(),
            recentLocations = recentLocations,
        )

        assertEquals(1, progress.nearestRoutePointIndex)
        assertTrue(progress.nearestSegmentStartIndex in 0..1)
        assertTrue(progress.completedDistanceMeters > 0.0)
        assertTrue(progress.remainingDistanceMeters > 0.0)
        assertTrue(progress.completionRatio in 0.3..0.7)
        assertTrue(progress.distanceToRouteMeters < 5.0)
        assertTrue(progress.estimatedRemainingSeconds != null)
        assertEquals(RouteTravelDirection.Forward, progress.travelDirection)
        assertTrue(progress.headingDegrees <= 15.0 || progress.headingDegrees >= 345.0)
    }

    @Test
    fun `snaps displayed location to nearest point on route when close enough`() {
        val route = RouteTrack(
            id = "route-2",
            name = "Straight",
            source = RouteSource.IMPORTED_GPX,
            createdAtMillis = 1L,
            distanceMeters = 0.0,
            points = listOf(
                LatLngPoint(40.0, -3.0),
                LatLngPoint(40.002, -3.0),
            ),
        )
        val rawLocation = LatLngPoint(40.001, -2.99985, timestampMillis = 30_000L)

        val progress = RouteNavigationEngine.calculate(
            route = route,
            currentLocation = rawLocation,
            recentLocations = listOf(
                LatLngPoint(40.0005, -3.0, timestampMillis = 0L),
            ),
        )

        assertTrue(progress.isLocationSnappedToRoute)
        assertTrue(progress.distanceToRouteMeters in 5.0..20.0)
        assertEquals(-3.0, progress.displayLocation?.longitude ?: 0.0, 0.00001)
        assertEquals(rawLocation.latitude, progress.displayLocation?.latitude ?: 0.0, 0.00005)
    }

    @Test
    fun `keeps raw displayed location when user is far from route`() {
        val route = RouteTrack(
            id = "route-3",
            name = "Straight",
            source = RouteSource.IMPORTED_GPX,
            createdAtMillis = 1L,
            distanceMeters = 0.0,
            points = listOf(
                LatLngPoint(40.0, -3.0),
                LatLngPoint(40.002, -3.0),
            ),
        )
        val rawLocation = LatLngPoint(40.001, -2.9992, timestampMillis = 30_000L)

        val progress = RouteNavigationEngine.calculate(
            route = route,
            currentLocation = rawLocation,
            recentLocations = emptyList(),
        )

        assertFalse(progress.isLocationSnappedToRoute)
        assertTrue(progress.distanceToRouteMeters > 30.0)
        assertEquals(rawLocation.longitude, progress.displayLocation?.longitude ?: 0.0, 0.00001)
        assertEquals(rawLocation.latitude, progress.displayLocation?.latitude ?: 0.0, 0.00001)
    }

    @Test
    fun `prefers recent movement to orient navigation and detects backward travel`() {
        val route = RouteTrack(
            id = "route-4",
            name = "Straight",
            source = RouteSource.IMPORTED_GPX,
            createdAtMillis = 1L,
            distanceMeters = 0.0,
            points = listOf(
                LatLngPoint(40.0, -3.0),
                LatLngPoint(40.001, -3.0),
                LatLngPoint(40.002, -3.0),
            ),
        )
        val recentLocations = listOf(
            LatLngPoint(40.0018, -3.0, timestampMillis = 0L),
            LatLngPoint(40.0015, -3.0, timestampMillis = 30_000L),
            LatLngPoint(40.0012, -3.0, timestampMillis = 60_000L),
        )

        val progress = RouteNavigationEngine.calculate(
            route = route,
            currentLocation = recentLocations.last(),
            recentLocations = recentLocations.dropLast(1),
        )

        assertEquals(RouteTravelDirection.Backward, progress.travelDirection)
        assertTrue(progress.headingDegrees in 150.0..210.0)
        assertTrue(progress.completedDistanceMeters < progress.remainingDistanceMeters)
    }

    @Test
    fun `falls back to measured bearing when recent movement is not available`() {
        val route = RouteTrack(
            id = "route-5",
            name = "Pin",
            source = RouteSource.IMPORTED_GPX,
            createdAtMillis = 1L,
            distanceMeters = 0.0,
            points = listOf(
                LatLngPoint(40.0, -3.0),
            ),
        )

        val progress = RouteNavigationEngine.calculate(
            route = route,
            currentLocation = LatLngPoint(
                40.0,
                -3.0,
                timestampMillis = 30_000L,
                bearingDegrees = 92.0,
            ),
            recentLocations = emptyList(),
        )

        assertEquals(92.0, progress.headingDegrees, 0.001)
    }

    @Test
    fun `uses current segment heading and reverse progress when traversing route backwards near start`() {
        val route = RouteTrack(
            id = "route-6",
            name = "Block",
            source = RouteSource.IMPORTED_GPX,
            createdAtMillis = 1L,
            distanceMeters = 0.0,
            points = listOf(
                LatLngPoint(40.0000, -3.0000),
                LatLngPoint(40.0010, -3.0000),
                LatLngPoint(40.0020, -3.0000),
            ),
        )
        val recentLocations = listOf(
            LatLngPoint(40.00055, -3.0, timestampMillis = 0L),
            LatLngPoint(40.00035, -3.0, timestampMillis = 20_000L),
            LatLngPoint(40.00015, -3.0, timestampMillis = 40_000L),
        )

        val progress = RouteNavigationEngine.calculate(
            route = route,
            currentLocation = recentLocations.last(),
            recentLocations = recentLocations.dropLast(1),
        )

        assertEquals(RouteTravelDirection.Backward, progress.travelDirection)
        assertTrue(progress.completionRatio > 0.8)
        assertTrue(progress.headingDegrees in 170.0..190.0)
        assertEquals(progress.displayLocation?.longitude ?: 0.0, -3.0, 0.00001)
    }
}
