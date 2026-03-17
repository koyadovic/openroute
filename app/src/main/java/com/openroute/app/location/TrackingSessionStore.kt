package com.openroute.app.location

import com.openroute.app.data.LatLngPoint
import com.openroute.app.data.RouteTrack
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TrackingState(
    val isRecording: Boolean = false,
    val startedAtMillis: Long? = null,
    val points: List<LatLngPoint> = emptyList(),
    val currentLocation: LatLngPoint? = null,
)

data class TrackingSessionResult(
    val points: List<LatLngPoint>,
    val startedAtMillis: Long?,
    val finishedAtMillis: Long,
) {
    val durationMillis: Long?
        get() = startedAtMillis
            ?.let { finishedAtMillis - it }
            ?.takeIf { it > 0L }
}

object TrackingSessionStore {
    private val _state = MutableStateFlow(TrackingState())
    val state = _state.asStateFlow()

    private val _savedRoutes = MutableSharedFlow<RouteTrack>(extraBufferCapacity = 1)
    val savedRoutes = _savedRoutes.asSharedFlow()

    fun startSession() {
        _state.value = TrackingState(
            isRecording = true,
            startedAtMillis = System.currentTimeMillis(),
        )
    }

    fun addPoint(point: LatLngPoint) {
        _state.update { current ->
            if (!current.isRecording) {
                current
            } else {
                current.copy(
                    points = current.points + point,
                    currentLocation = point,
                )
            }
        }
    }

    fun updateCurrentLocation(point: LatLngPoint) {
        _state.update { current ->
            if (!current.isRecording) {
                current
            } else {
                current.copy(currentLocation = point)
            }
        }
    }

    fun finishSession(): TrackingSessionResult {
        val currentState = _state.value
        val result = TrackingSessionResult(
            points = currentState.points,
            startedAtMillis = currentState.startedAtMillis,
            finishedAtMillis = System.currentTimeMillis(),
        )
        _state.value = TrackingState()
        return result
    }

    fun publishSavedRoute(route: RouteTrack) {
        _savedRoutes.tryEmit(route)
    }
}
