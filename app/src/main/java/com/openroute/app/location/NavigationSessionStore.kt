package com.openroute.app.location

import com.openroute.app.data.LatLngPoint
import com.openroute.app.data.RouteNavigationEngine
import com.openroute.app.data.RouteNavigationProgress
import com.openroute.app.data.RouteTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NavigationState(
    val isNavigating: Boolean = false,
    val route: RouteTrack? = null,
    val currentLocation: LatLngPoint? = null,
    val visitedPoints: List<LatLngPoint> = emptyList(),
    val progress: RouteNavigationProgress? = null,
)

object NavigationSessionStore {
    private val _state = MutableStateFlow(NavigationState())
    val state = _state.asStateFlow()

    fun startSession(route: RouteTrack) {
        _state.value = NavigationState(
            isNavigating = true,
            route = route,
        )
    }

    fun updateLocation(
        point: LatLngPoint,
        appendVisited: Boolean = true,
    ) {
        _state.update { current ->
            val route = current.route
            if (!current.isNavigating || route == null) {
                current
            } else {
                val progress = RouteNavigationEngine.calculate(
                    route = route,
                    currentLocation = point,
                    recentLocations = current.visitedPoints,
                )
                val displayLocation = progress.displayLocation ?: point
                val visitedPoints = if (appendVisited && shouldAppendVisitedPoint(current.visitedPoints.lastOrNull(), displayLocation)) {
                    (current.visitedPoints + displayLocation).takeLast(MAX_VISITED_POINTS)
                } else {
                    current.visitedPoints
                }
                current.copy(
                    currentLocation = displayLocation,
                    visitedPoints = visitedPoints,
                    progress = progress,
                )
            }
        }
    }

    fun finishSession() {
        _state.value = NavigationState()
    }

    fun refreshRoute(route: RouteTrack) {
        _state.update { current ->
            if (current.route?.id == route.id) {
                current.copy(route = route)
            } else {
                current
            }
        }
    }

    private const val MAX_VISITED_POINTS = 500
    private const val MIN_VISITED_POINT_DISTANCE_METERS = 1.2

    private fun shouldAppendVisitedPoint(
        previousPoint: LatLngPoint?,
        candidatePoint: LatLngPoint,
    ): Boolean {
        if (previousPoint == null) {
            return true
        }

        return previousPoint.distanceTo(candidatePoint) >= MIN_VISITED_POINT_DISTANCE_METERS
    }
}

private fun LatLngPoint.distanceTo(other: LatLngPoint): Double {
    val earthRadiusMeters = 6_371_000.0
    val latitudeDeltaRadians = Math.toRadians(other.latitude - latitude)
    val longitudeDeltaRadians = Math.toRadians(other.longitude - longitude)
    val startLatitudeRadians = Math.toRadians(latitude)
    val endLatitudeRadians = Math.toRadians(other.latitude)
    val a = kotlin.math.sin(latitudeDeltaRadians / 2).let { it * it } +
        kotlin.math.cos(startLatitudeRadians) *
        kotlin.math.cos(endLatitudeRadians) *
        kotlin.math.sin(longitudeDeltaRadians / 2).let { it * it }

    return 2 * earthRadiusMeters * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
}
