package com.openroute.app.data

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.serialization.Serializable

@Serializable
data class LatLngPoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long? = null,
)

@Serializable
enum class RouteSource {
    IMPORTED_GPX,
    RECORDED,
}

@Serializable
data class RouteTrack(
    val id: String,
    val name: String,
    val source: RouteSource,
    val createdAtMillis: Long,
    val distanceMeters: Double,
    val durationMillis: Long? = null,
    val points: List<LatLngPoint>,
    val isNew: Boolean = false,
    val isHidden: Boolean = false,
    val importReference: String? = null,
    val originalFileName: String? = null,
)

@Serializable
data class StoredRoutes(
    val routes: List<RouteTrack> = emptyList(),
)

@Serializable
data class MapRouteOverlay(
    val id: String,
    val name: String,
    val color: String,
    val selected: Boolean,
    val points: List<LatLngPoint>,
)

@Serializable
data class MapRenderState(
    val routes: List<MapRouteOverlay> = emptyList(),
    val liveTrack: List<LatLngPoint> = emptyList(),
    val currentLocation: LatLngPoint? = null,
    val focus: MapFocusState = MapFocusState(),
)

@Serializable
data class MapFocusState(
    val routeId: String? = null,
    val includeCurrentLocation: Boolean = false,
    val preferCurrentLocationZoom: Boolean = false,
)

val RouteTrack.effectiveDurationMillis: Long?
    get() = durationMillis ?: points.durationMillis()

fun List<RouteTrack>.sortedByDistanceTo(referencePoint: LatLngPoint?): List<RouteTrack> {
    if (referencePoint == null) {
        return this
    }

    return withIndex()
        .sortedWith(
            compareBy<IndexedValue<RouteTrack>>(
                { indexedRoute -> indexedRoute.value.closestDistanceMetersTo(referencePoint) ?: Double.MAX_VALUE },
                IndexedValue<RouteTrack>::index,
            ),
        )
        .map(IndexedValue<RouteTrack>::value)
}

fun List<LatLngPoint>.distanceMeters(): Double {
    if (size < 2) {
        return 0.0
    }

    return zipWithNext { start, end ->
        haversineMeters(
            startLatitude = start.latitude,
            startLongitude = start.longitude,
            endLatitude = end.latitude,
            endLongitude = end.longitude,
        )
    }.sum()
}

fun List<LatLngPoint>.durationMillis(): Long? {
    if (size < 2) {
        return null
    }

    val firstTimestamp = firstOrNull { it.timestampMillis != null }?.timestampMillis ?: return null
    val lastTimestamp = lastOrNull { it.timestampMillis != null }?.timestampMillis ?: return null
    val duration = lastTimestamp - firstTimestamp

    return duration.takeIf { it > 0L }
}

fun RouteTrack.closestDistanceMetersTo(referencePoint: LatLngPoint): Double? {
    return points.minOfOrNull { point ->
        haversineMeters(
            startLatitude = referencePoint.latitude,
            startLongitude = referencePoint.longitude,
            endLatitude = point.latitude,
            endLongitude = point.longitude,
        )
    }
}

private fun haversineMeters(
    startLatitude: Double,
    startLongitude: Double,
    endLatitude: Double,
    endLongitude: Double,
): Double {
    val earthRadiusMeters = 6_371_000.0
    val latDistance = (endLatitude - startLatitude).toRadians()
    val lonDistance = (endLongitude - startLongitude).toRadians()

    val startLat = startLatitude.toRadians()
    val endLat = endLatitude.toRadians()

    val a = sin(latDistance / 2).pow(2) +
        cos(startLat) * cos(endLat) * sin(lonDistance / 2).pow(2)

    return 2 * earthRadiusMeters * asin(a.coerceIn(0.0, 1.0).let { kotlin.math.sqrt(it) })
}

private fun Double.toRadians(): Double = this * PI / 180.0
