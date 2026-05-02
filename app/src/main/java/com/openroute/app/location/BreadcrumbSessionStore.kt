package com.openroute.app.location

import com.openroute.app.data.LatLngPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object BreadcrumbSessionStore {
    private val _state = MutableStateFlow(BreadcrumbState())
    val state = _state.asStateFlow()

    fun startSession() {
        _state.value = BreadcrumbTrailEngine.start()
    }

    fun updateLocation(
        point: LatLngPoint,
        appendBreadcrumb: Boolean,
    ) {
        _state.update { current ->
            BreadcrumbTrailEngine.update(
                state = current,
                point = point,
                appendBreadcrumb = appendBreadcrumb,
            )
        }
    }

    fun finishSession() {
        _state.value = BreadcrumbTrailEngine.stop()
    }
}
