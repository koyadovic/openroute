package com.openroute.app.data

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

data class RouteNavigationProgress(
    val nearestRoutePointIndex: Int,
    val completedDistanceMeters: Double,
    val remainingDistanceMeters: Double,
    val completionRatio: Double,
    val distanceToRouteMeters: Double,
    val estimatedRemainingSeconds: Long? = null,
    val currentSpeedMetersPerSecond: Double? = null,
)

object RouteNavigationEngine {
    fun calculate(
        route: RouteTrack,
        currentLocation: LatLngPoint,
        recentLocations: List<LatLngPoint>,
    ): RouteNavigationProgress {
        if (route.points.isEmpty()) {
            return RouteNavigationProgress(
                nearestRoutePointIndex = 0,
                completedDistanceMeters = 0.0,
                remainingDistanceMeters = 0.0,
                completionRatio = 0.0,
                distanceToRouteMeters = 0.0,
            )
        }

        val cumulativeDistances = route.points.cumulativeDistances()
        val totalDistance = route.distanceMeters.takeIf { it > 0.0 }
            ?: cumulativeDistances.lastOrNull()
            ?: 0.0

        val nearestIndex = route.points.indices.minByOrNull { index ->
            currentLocation.distanceTo(route.points[index])
        } ?: 0

        val completedDistance = cumulativeDistances.getOrElse(nearestIndex) { 0.0 }
            .coerceIn(0.0, totalDistance)
        val remainingDistance = (totalDistance - completedDistance).coerceAtLeast(0.0)
        val currentSpeed = recentLocations.estimateSpeedMetersPerSecond()

        return RouteNavigationProgress(
            nearestRoutePointIndex = nearestIndex,
            completedDistanceMeters = completedDistance,
            remainingDistanceMeters = remainingDistance,
            completionRatio = if (totalDistance > 0.0) {
                (completedDistance / totalDistance).coerceIn(0.0, 1.0)
            } else {
                0.0
            },
            distanceToRouteMeters = currentLocation.distanceTo(route.points[nearestIndex]),
            estimatedRemainingSeconds = currentSpeed
                ?.takeIf { it >= MIN_MOVING_SPEED_METERS_PER_SECOND }
                ?.let { speed -> (remainingDistance / speed).roundToLong() },
            currentSpeedMetersPerSecond = currentSpeed,
        )
    }
}

private fun List<LatLngPoint>.cumulativeDistances(): List<Double> {
    if (isEmpty()) {
        return emptyList()
    }

    val distances = MutableList(size) { 0.0 }
    for (index in 1 until size) {
        distances[index] = distances[index - 1] + this[index - 1].distanceTo(this[index])
    }
    return distances
}

private fun List<LatLngPoint>.estimateSpeedMetersPerSecond(): Double? {
    val samples = filter { it.timestampMillis != null }
    if (samples.size < 2) {
        return null
    }

    val window = samples.takeLast(MAX_SPEED_SAMPLES)
    val first = window.first()
    val last = window.last()
    val startTime = first.timestampMillis ?: return null
    val endTime = last.timestampMillis ?: return null
    val elapsedSeconds = (endTime - startTime) / 1000.0

    if (elapsedSeconds <= 0.0) {
        return null
    }

    val distanceMeters = window.zipWithNext { start, end -> start.distanceTo(end) }.sum()
    return distanceMeters / elapsedSeconds
}

private fun LatLngPoint.distanceTo(other: LatLngPoint): Double {
    val earthRadiusMeters = 6_371_000.0
    val latDistance = (other.latitude - latitude).toRadians()
    val lonDistance = (other.longitude - longitude).toRadians()

    val startLat = latitude.toRadians()
    val endLat = other.latitude.toRadians()

    val a = sin(latDistance / 2).pow(2) +
        cos(startLat) * cos(endLat) * sin(lonDistance / 2).pow(2)

    return 2 * earthRadiusMeters * asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
}

private fun Double.toRadians(): Double = this * PI / 180.0

private const val MAX_SPEED_SAMPLES = 6
private const val MIN_MOVING_SPEED_METERS_PER_SECOND = 0.6
