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
        assertTrue(progress.completedDistanceMeters > 0.0)
        assertTrue(progress.remainingDistanceMeters > 0.0)
        assertTrue(progress.completionRatio in 0.3..0.7)
        assertTrue(progress.distanceToRouteMeters < 5.0)
        assertTrue(progress.estimatedRemainingSeconds != null)
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
}
