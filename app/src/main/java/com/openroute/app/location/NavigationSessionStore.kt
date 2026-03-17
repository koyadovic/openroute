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

    fun updateLocation(point: LatLngPoint) {
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
                val visitedPoints = (current.visitedPoints + displayLocation).takeLast(MAX_VISITED_POINTS)
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

    private const val MAX_VISITED_POINTS = 500
}
