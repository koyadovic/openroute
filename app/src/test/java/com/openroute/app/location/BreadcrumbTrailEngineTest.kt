package com.openroute.app.location

import com.openroute.app.data.LatLngPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreadcrumbTrailEngineTest {
    @Test
    fun `switches to returning when user turns back over existing breadcrumbs`() {
        var state = BreadcrumbTrailEngine.start(startedAtMillis = 1_000L)
        listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0).forEach { meters ->
            state = BreadcrumbTrailEngine.update(
                state = state,
                point = pointNorth(meters),
                appendBreadcrumb = true,
            )
        }

        state = BreadcrumbTrailEngine.update(
            state = state,
            point = pointNorth(40.0),
            appendBreadcrumb = true,
        )

        assertEquals(BreadcrumbMode.Returning, state.mode)
        assertTrue(state.isReturning)
        assertTrue((state.route?.distanceMeters ?: 0.0) < 60.0)
        assertTrue((state.progress?.remainingDistanceMeters ?: 0.0) > 30.0)
    }

    @Test
    fun `returns to seeding when user leaves known trail during return`() {
        var state = BreadcrumbTrailEngine.start(startedAtMillis = 1_000L)
        listOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0).forEach { meters ->
            state = BreadcrumbTrailEngine.update(
                state = state,
                point = pointNorth(meters),
                appendBreadcrumb = true,
            )
        }
        state = BreadcrumbTrailEngine.update(
            state = state,
            point = pointNorth(40.0),
            appendBreadcrumb = true,
        )

        state = BreadcrumbTrailEngine.update(
            state = state,
            point = pointNorth(metersNorth = 40.0, metersEast = 45.0),
            appendBreadcrumb = true,
        )

        assertEquals(BreadcrumbMode.Seeding, state.mode)
        assertTrue(!state.isReturning)
        assertEquals(pointNorth(metersNorth = 40.0, metersEast = 45.0), state.points.last())
    }
}

private fun pointNorth(
    metersNorth: Double,
    metersEast: Double = 0.0,
): LatLngPoint {
    val latitude = BASE_LATITUDE + metersNorth / METERS_PER_DEGREE
    val longitude = BASE_LONGITUDE + metersEast / (METERS_PER_DEGREE * kotlin.math.cos(Math.toRadians(BASE_LATITUDE)))
    return LatLngPoint(
        latitude = latitude,
        longitude = longitude,
        timestampMillis = 1_000L + metersNorth.toLong(),
    )
}

private const val BASE_LATITUDE = 40.0
private const val BASE_LONGITUDE = -3.0
private const val METERS_PER_DEGREE = 111_320.0
