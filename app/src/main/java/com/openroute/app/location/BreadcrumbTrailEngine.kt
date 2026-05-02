package com.openroute.app.location

import com.openroute.app.data.LatLngPoint
import com.openroute.app.data.RouteNavigationEngine
import com.openroute.app.data.RouteNavigationProgress
import com.openroute.app.data.RouteSource
import com.openroute.app.data.RouteTrack
import com.openroute.app.data.RouteTravelDirection
import com.openroute.app.data.distanceMeters
import kotlin.math.max

enum class BreadcrumbMode {
    Seeding,
    Returning,
}

data class BreadcrumbState(
    val isActive: Boolean = false,
    val mode: BreadcrumbMode = BreadcrumbMode.Seeding,
    val startedAtMillis: Long = 0L,
    val points: List<LatLngPoint> = emptyList(),
    val currentLocation: LatLngPoint? = null,
    val recentLocations: List<LatLngPoint> = emptyList(),
    val progress: RouteNavigationProgress? = null,
    val maxDistanceAlongRouteMeters: Double = 0.0,
) {
    val isReturning: Boolean
        get() = isActive && mode == BreadcrumbMode.Returning

    val route: RouteTrack?
        get() = points.toBreadcrumbRoute(startedAtMillis)
}

internal object BreadcrumbTrailEngine {
    fun start(startedAtMillis: Long = System.currentTimeMillis()): BreadcrumbState {
        return BreadcrumbState(
            isActive = true,
            startedAtMillis = startedAtMillis,
        )
    }

    fun stop(): BreadcrumbState = BreadcrumbState()

    fun update(
        state: BreadcrumbState,
        point: LatLngPoint,
        appendBreadcrumb: Boolean,
    ): BreadcrumbState {
        if (!state.isActive) {
            return state
        }

        val recentLocations = (state.recentLocations + point).takeLast(MAX_RECENT_LOCATIONS)
        if (state.points.isEmpty()) {
            return state.copy(
                points = if (appendBreadcrumb) listOf(point) else emptyList(),
                currentLocation = point,
                recentLocations = recentLocations,
            )
        }

        return when (state.mode) {
            BreadcrumbMode.Seeding -> updateSeeding(
                state = state,
                point = point,
                appendBreadcrumb = appendBreadcrumb,
                recentLocations = recentLocations,
            )

            BreadcrumbMode.Returning -> updateReturning(
                state = state,
                point = point,
                appendBreadcrumb = appendBreadcrumb,
                recentLocations = recentLocations,
            )
        }
    }

    private fun updateSeeding(
        state: BreadcrumbState,
        point: LatLngPoint,
        appendBreadcrumb: Boolean,
        recentLocations: List<LatLngPoint>,
    ): BreadcrumbState {
        val route = state.route
        val progress = route?.let { currentRoute ->
            RouteNavigationEngine.calculate(
                route = currentRoute,
                currentLocation = point,
                recentLocations = recentLocations,
            )
        }
        val furthestDistance = max(
            state.maxDistanceAlongRouteMeters,
            progress?.distanceAlongRouteMeters ?: state.points.distanceMeters(),
        )

        if (
            progress != null &&
            shouldSwitchToReturning(route = route, progress = progress, furthestDistance = furthestDistance)
        ) {
            val trimmedPoints = trimTrailToProgress(state.points, progress)
            val trimmedRoute = trimmedPoints.toBreadcrumbRoute(state.startedAtMillis)
            val trimmedProgress = trimmedRoute?.let { currentRoute ->
                RouteNavigationEngine.calculate(
                    route = currentRoute,
                    currentLocation = point,
                    recentLocations = recentLocations,
                )
            }
            val returnProgress = (trimmedProgress ?: progress).asReturnProgress(trimmedRoute ?: route)

            return state.copy(
                mode = BreadcrumbMode.Returning,
                points = trimmedPoints,
                currentLocation = returnProgress.displayLocation ?: point,
                recentLocations = recentLocations,
                progress = returnProgress,
                maxDistanceAlongRouteMeters = trimmedRoute?.distanceMeters ?: route.distanceMeters,
            )
        }

        val points = if (appendBreadcrumb) {
            appendPoint(state.points, point)
        } else {
            state.points
        }
        val updatedRoute = points.toBreadcrumbRoute(state.startedAtMillis)
        val updatedProgress = updatedRoute?.let { currentRoute ->
            RouteNavigationEngine.calculate(
                route = currentRoute,
                currentLocation = point,
                recentLocations = recentLocations,
            )
        }

        return state.copy(
            points = points,
            currentLocation = point,
            recentLocations = recentLocations,
            progress = updatedProgress,
            maxDistanceAlongRouteMeters = max(
                furthestDistance,
                updatedProgress?.distanceAlongRouteMeters ?: points.distanceMeters(),
            ),
        )
    }

    private fun updateReturning(
        state: BreadcrumbState,
        point: LatLngPoint,
        appendBreadcrumb: Boolean,
        recentLocations: List<LatLngPoint>,
    ): BreadcrumbState {
        val route = state.route
        val progress = route?.let { currentRoute ->
            RouteNavigationEngine.calculate(
                route = currentRoute,
                currentLocation = point,
                recentLocations = recentLocations,
            )
        }

        if (
            appendBreadcrumb &&
            progress != null &&
            progress.distanceToRouteMeters >= BRANCH_OFF_ROUTE_DISTANCE_METERS &&
            shouldAppendPoint(state.points.lastOrNull(), point)
        ) {
            val points = appendPoint(state.points, point)
            val updatedRoute = points.toBreadcrumbRoute(state.startedAtMillis)
            val updatedProgress = updatedRoute?.let { currentRoute ->
                RouteNavigationEngine.calculate(
                    route = currentRoute,
                    currentLocation = point,
                    recentLocations = recentLocations,
                )
            }

            return state.copy(
                mode = BreadcrumbMode.Seeding,
                points = points,
                currentLocation = point,
                recentLocations = recentLocations,
                progress = updatedProgress,
                maxDistanceAlongRouteMeters = updatedRoute?.distanceMeters ?: points.distanceMeters(),
            )
        }

        val points = if (
            appendBreadcrumb &&
            progress != null &&
            progress.distanceToRouteMeters <= RETURNING_ROUTE_SNAP_DISTANCE_METERS
        ) {
            trimTrailToProgress(state.points, progress)
        } else {
            state.points
        }
        val updatedRoute = points.toBreadcrumbRoute(state.startedAtMillis)
        val updatedRawProgress = updatedRoute?.let { currentRoute ->
            RouteNavigationEngine.calculate(
                route = currentRoute,
                currentLocation = point,
                recentLocations = recentLocations,
            )
        } ?: progress
        val updatedProgress = updatedRawProgress?.let { currentProgress ->
            updatedRoute?.let { currentRoute ->
                currentProgress.asReturnProgress(currentRoute)
            } ?: currentProgress
        }

        return state.copy(
            points = points,
            currentLocation = updatedProgress?.displayLocation ?: point,
            recentLocations = recentLocations,
            progress = updatedProgress,
            maxDistanceAlongRouteMeters = updatedRoute?.distanceMeters ?: points.distanceMeters(),
        )
    }

    private fun shouldSwitchToReturning(
        route: RouteTrack,
        progress: RouteNavigationProgress,
        furthestDistance: Double,
    ): Boolean {
        return route.distanceMeters >= MIN_ROUTE_DISTANCE_BEFORE_RETURN_METERS &&
            progress.distanceToRouteMeters <= RETURN_DETECTION_ROUTE_DISTANCE_METERS &&
            furthestDistance - progress.distanceAlongRouteMeters >= TURN_BACK_DISTANCE_METERS
    }

    private fun RouteNavigationProgress.asReturnProgress(route: RouteTrack): RouteNavigationProgress {
        val totalDistance = route.distanceMeters
        val remainingToOrigin = distanceAlongRouteMeters.coerceIn(0.0, totalDistance)
        val completedReturnDistance = (totalDistance - remainingToOrigin).coerceIn(0.0, totalDistance)
        val returnHeading = when (travelDirection) {
            RouteTravelDirection.Forward -> (headingDegrees + 180.0) % 360.0
            RouteTravelDirection.Backward -> headingDegrees
        }

        return copy(
            completedDistanceMeters = completedReturnDistance,
            remainingDistanceMeters = remainingToOrigin,
            completionRatio = if (totalDistance > 0.0) {
                (completedReturnDistance / totalDistance).coerceIn(0.0, 1.0)
            } else {
                0.0
            },
            headingDegrees = returnHeading,
            travelDirection = RouteTravelDirection.Backward,
        )
    }

    private fun trimTrailToProgress(
        points: List<LatLngPoint>,
        progress: RouteNavigationProgress,
    ): List<LatLngPoint> {
        if (points.isEmpty()) {
            return emptyList()
        }

        val anchor = progress.displayLocation ?: points.getOrNull(progress.nearestRoutePointIndex) ?: points.last()
        val keptPointCount = (progress.nearestSegmentStartIndex + 1).coerceIn(1, points.size)
        return appendPoint(
            points = points.take(keptPointCount),
            point = anchor,
            force = true,
        )
    }

    private fun appendPoint(
        points: List<LatLngPoint>,
        point: LatLngPoint,
        force: Boolean = false,
    ): List<LatLngPoint> {
        if (!force && !shouldAppendPoint(points.lastOrNull(), point)) {
            return points
        }

        return (points + point).takeLast(MAX_BREADCRUMB_POINTS)
    }

    private fun shouldAppendPoint(
        previousPoint: LatLngPoint?,
        candidatePoint: LatLngPoint,
    ): Boolean {
        if (previousPoint == null) {
            return true
        }

        return previousPoint.distanceTo(candidatePoint) >= MIN_BREADCRUMB_POINT_DISTANCE_METERS
    }

    private const val MAX_RECENT_LOCATIONS = 8
    private const val MAX_BREADCRUMB_POINTS = 10_000
    private const val MIN_BREADCRUMB_POINT_DISTANCE_METERS = 2.0
    private const val MIN_ROUTE_DISTANCE_BEFORE_RETURN_METERS = 35.0
    private const val RETURN_DETECTION_ROUTE_DISTANCE_METERS = 18.0
    private const val RETURNING_ROUTE_SNAP_DISTANCE_METERS = 25.0
    private const val TURN_BACK_DISTANCE_METERS = 18.0
    private const val BRANCH_OFF_ROUTE_DISTANCE_METERS = 35.0
}

private fun List<LatLngPoint>.toBreadcrumbRoute(startedAtMillis: Long): RouteTrack? {
    if (size < 2) {
        return null
    }

    return RouteTrack(
        id = BREADCRUMB_ROUTE_ID,
        name = "Breadcrumbs",
        source = RouteSource.RECORDED,
        createdAtMillis = startedAtMillis,
        distanceMeters = distanceMeters(),
        points = this,
    )
}

private fun LatLngPoint.distanceTo(other: LatLngPoint): Double {
    return listOf(this, other).distanceMeters()
}

private const val BREADCRUMB_ROUTE_ID = "breadcrumb-route"
