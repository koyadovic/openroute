package com.openroute.app.data

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
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
    val displayLocation: LatLngPoint? = null,
    val isLocationSnappedToRoute: Boolean = false,
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
                displayLocation = currentLocation,
            )
        }

        val cumulativeDistances = route.points.cumulativeDistances()
        val totalDistance = route.distanceMeters.takeIf { it > 0.0 }
            ?: cumulativeDistances.lastOrNull()
            ?: 0.0

        val nearestProjection = route.points.closestProjectionTo(currentLocation)
        val nearestIndex = nearestProjection.nearestRoutePointIndex

        val completedDistance = (cumulativeDistances.getOrElse(nearestProjection.segmentStartIndex) { 0.0 } +
            nearestProjection.distanceAlongSegmentMeters)
            .coerceIn(0.0, totalDistance)
        val remainingDistance = (totalDistance - completedDistance).coerceAtLeast(0.0)
        val displayLocation = if (nearestProjection.distanceToRouteMeters <= MAX_ROUTE_SNAP_DISTANCE_METERS) {
            nearestProjection.projectedPoint
        } else {
            currentLocation
        }
        val currentSpeed = (recentLocations + displayLocation)
            .takeLast(MAX_SPEED_SAMPLES)
            .estimateSpeedMetersPerSecond()

        return RouteNavigationProgress(
            nearestRoutePointIndex = nearestIndex,
            completedDistanceMeters = completedDistance,
            remainingDistanceMeters = remainingDistance,
            completionRatio = if (totalDistance > 0.0) {
                (completedDistance / totalDistance).coerceIn(0.0, 1.0)
            } else {
                0.0
            },
            distanceToRouteMeters = nearestProjection.distanceToRouteMeters,
            estimatedRemainingSeconds = currentSpeed
                ?.takeIf { it >= MIN_MOVING_SPEED_METERS_PER_SECOND }
                ?.let { speed -> (remainingDistance / speed).roundToLong() },
            currentSpeedMetersPerSecond = currentSpeed,
            displayLocation = displayLocation,
            isLocationSnappedToRoute = displayLocation != currentLocation,
        )
    }
}

private data class RouteProjection(
    val segmentStartIndex: Int,
    val nearestRoutePointIndex: Int,
    val projectedPoint: LatLngPoint,
    val distanceAlongSegmentMeters: Double,
    val distanceToRouteMeters: Double,
)

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

private fun List<LatLngPoint>.closestProjectionTo(referencePoint: LatLngPoint): RouteProjection {
    if (size == 1) {
        return RouteProjection(
            segmentStartIndex = 0,
            nearestRoutePointIndex = 0,
            projectedPoint = first(),
            distanceAlongSegmentMeters = 0.0,
            distanceToRouteMeters = referencePoint.distanceTo(first()),
        )
    }

    return (0 until lastIndex)
        .map { segmentStartIndex ->
            projectPointOntoSegment(
                referencePoint = referencePoint,
                segmentStart = get(segmentStartIndex),
                segmentEnd = get(segmentStartIndex + 1),
                segmentStartIndex = segmentStartIndex,
            )
        }
        .minByOrNull(RouteProjection::distanceToRouteMeters)
        ?: RouteProjection(
            segmentStartIndex = 0,
            nearestRoutePointIndex = 0,
            projectedPoint = first(),
            distanceAlongSegmentMeters = 0.0,
            distanceToRouteMeters = referencePoint.distanceTo(first()),
        )
}

private fun projectPointOntoSegment(
    referencePoint: LatLngPoint,
    segmentStart: LatLngPoint,
    segmentEnd: LatLngPoint,
    segmentStartIndex: Int,
): RouteProjection {
    val localReference = LocalReference(
        latitude = (segmentStart.latitude + segmentEnd.latitude) / 2.0,
        longitude = (segmentStart.longitude + segmentEnd.longitude) / 2.0,
    )
    val start = segmentStart.toLocalPoint(localReference)
    val end = segmentEnd.toLocalPoint(localReference)
    val point = referencePoint.toLocalPoint(localReference)

    val segmentVectorX = end.x - start.x
    val segmentVectorY = end.y - start.y
    val segmentLengthSquared = segmentVectorX.pow(2) + segmentVectorY.pow(2)

    if (segmentLengthSquared == 0.0) {
        return RouteProjection(
            segmentStartIndex = segmentStartIndex,
            nearestRoutePointIndex = segmentStartIndex,
            projectedPoint = segmentStart,
            distanceAlongSegmentMeters = 0.0,
            distanceToRouteMeters = referencePoint.distanceTo(segmentStart),
        )
    }

    val pointVectorX = point.x - start.x
    val pointVectorY = point.y - start.y
    val rawT = ((pointVectorX * segmentVectorX) + (pointVectorY * segmentVectorY)) / segmentLengthSquared
    val t = rawT.coerceIn(0.0, 1.0)

    val projectedX = start.x + (segmentVectorX * t)
    val projectedY = start.y + (segmentVectorY * t)
    val projectedPoint = LocalPoint(projectedX, projectedY).toLatLngPoint(
        reference = localReference,
        timestampMillis = referencePoint.timestampMillis,
    )
    val segmentLengthMeters = sqrt(segmentLengthSquared)

    return RouteProjection(
        segmentStartIndex = segmentStartIndex,
        nearestRoutePointIndex = if (t < 0.5) segmentStartIndex else segmentStartIndex + 1,
        projectedPoint = projectedPoint,
        distanceAlongSegmentMeters = segmentLengthMeters * t,
        distanceToRouteMeters = sqrt((point.x - projectedX).pow(2) + (point.y - projectedY).pow(2)),
    )
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

private data class LocalReference(
    val latitude: Double,
    val longitude: Double,
)

private data class LocalPoint(
    val x: Double,
    val y: Double,
)

private fun LatLngPoint.toLocalPoint(reference: LocalReference): LocalPoint {
    val metersPerDegreeLatitude = EARTH_RADIUS_METERS * PI / 180.0
    val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(reference.latitude.toRadians())

    return LocalPoint(
        x = (longitude - reference.longitude) * metersPerDegreeLongitude,
        y = (latitude - reference.latitude) * metersPerDegreeLatitude,
    )
}

private fun LocalPoint.toLatLngPoint(reference: LocalReference, timestampMillis: Long?): LatLngPoint {
    val metersPerDegreeLatitude = EARTH_RADIUS_METERS * PI / 180.0
    val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(reference.latitude.toRadians())

    return LatLngPoint(
        latitude = reference.latitude + (y / metersPerDegreeLatitude),
        longitude = reference.longitude + (x / metersPerDegreeLongitude),
        timestampMillis = timestampMillis,
    )
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val MAX_SPEED_SAMPLES = 6
private const val MIN_MOVING_SPEED_METERS_PER_SECOND = 0.6
private const val MAX_ROUTE_SNAP_DISTANCE_METERS = 30.0
