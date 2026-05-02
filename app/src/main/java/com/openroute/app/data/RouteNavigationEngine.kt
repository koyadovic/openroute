package com.openroute.app.data

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.roundToLong
import kotlin.math.sin

enum class RouteTravelDirection {
    Forward,
    Backward,
}

data class RouteNavigationProgress(
    val nearestRoutePointIndex: Int,
    val nearestSegmentStartIndex: Int,
    val distanceAlongRouteMeters: Double,
    val completedDistanceMeters: Double,
    val remainingDistanceMeters: Double,
    val completionRatio: Double,
    val distanceToRouteMeters: Double,
    val estimatedRemainingSeconds: Long? = null,
    val currentSpeedMetersPerSecond: Double? = null,
    val displayLocation: LatLngPoint? = null,
    val isLocationSnappedToRoute: Boolean = false,
    val headingDegrees: Double = 0.0,
    val travelDirection: RouteTravelDirection = RouteTravelDirection.Forward,
)

internal data class RouteNavigationGeometry(
    val route: RouteTrack,
    val cumulativeDistances: List<Double>,
    val isLoopRoute: Boolean,
)

object RouteNavigationEngine {
    internal fun prepare(route: RouteTrack): RouteNavigationGeometry {
        return RouteNavigationGeometry(
            route = route,
            cumulativeDistances = route.points.cumulativeDistances(),
            isLoopRoute = route.points.isClosedLoop(),
        )
    }

    fun calculate(
        route: RouteTrack,
        currentLocation: LatLngPoint,
        recentLocations: List<LatLngPoint>,
    ): RouteNavigationProgress {
        return calculate(
            geometry = prepare(route),
            currentLocation = currentLocation,
            recentLocations = recentLocations,
        )
    }

    internal fun calculate(
        geometry: RouteNavigationGeometry,
        currentLocation: LatLngPoint,
        recentLocations: List<LatLngPoint>,
    ): RouteNavigationProgress {
        val route = geometry.route
        if (route.points.isEmpty()) {
            return RouteNavigationProgress(
                nearestRoutePointIndex = 0,
                nearestSegmentStartIndex = 0,
                distanceAlongRouteMeters = 0.0,
                completedDistanceMeters = 0.0,
                remainingDistanceMeters = 0.0,
                completionRatio = 0.0,
                distanceToRouteMeters = 0.0,
                displayLocation = currentLocation,
            )
        }

        val cumulativeDistances = geometry.cumulativeDistances
        val totalDistance = route.distanceMeters.takeIf { it > 0.0 }
            ?: cumulativeDistances.lastOrNull()
            ?: 0.0

        val nearestProjection = route.points.closestProjectionTo(currentLocation)
        val nearestIndex = nearestProjection.nearestRoutePointIndex
        val isLoopRoute = geometry.isLoopRoute

        val forwardDistanceAlongRoute = (cumulativeDistances.getOrElse(nearestProjection.segmentStartIndex) { 0.0 } +
            nearestProjection.distanceAlongSegmentMeters)
            .coerceIn(0.0, totalDistance)
        val isSnappedToRoute = nearestProjection.distanceToRouteMeters <= MAX_ROUTE_SNAP_DISTANCE_METERS
        val displayLocation = if (isSnappedToRoute) {
            nearestProjection.projectedPoint
        } else {
            currentLocation
        }
        val currentSpeed = recentLocations.estimateSpeedMetersPerSecond(displayLocation)
        val routeHeadingForward = route.points.headingDegreesForSegment(nearestProjection.segmentStartIndex)
        val routeHeadingBackward = routeHeadingForward?.let(::reverseHeadingDegrees)
        val movementHeading = recentLocations.movementHeadingDegrees(displayLocation)
        val sensorHeading = currentLocation.bearingDegrees?.normalizeHeadingDegrees()
        val recentRouteDistances = buildList(MAX_ROUTE_PROGRESS_SAMPLES + 1) {
            val startIndex = (recentLocations.size - MAX_ROUTE_PROGRESS_SAMPLES).coerceAtLeast(0)
            for (index in startIndex until recentLocations.size) {
                add(route.points.closestProjectionTo(recentLocations[index]).distanceAlongRouteMeters(cumulativeDistances))
            }
            add(forwardDistanceAlongRoute)
        }
        val travelDirection = resolveTravelDirection(
            recentRouteDistances = recentRouteDistances,
            totalDistance = totalDistance,
            isLoopRoute = isLoopRoute,
            movementHeading = movementHeading,
            sensorHeading = sensorHeading,
            routeHeadingForward = routeHeadingForward,
        )
        val routeHeading = when (travelDirection) {
            RouteTravelDirection.Forward -> routeHeadingForward ?: routeHeadingBackward
            RouteTravelDirection.Backward -> routeHeadingBackward ?: routeHeadingForward
        }
        val completedDistance = when (travelDirection) {
            RouteTravelDirection.Forward -> forwardDistanceAlongRoute
            RouteTravelDirection.Backward -> (totalDistance - forwardDistanceAlongRoute).coerceIn(0.0, totalDistance)
        }
        val remainingDistance = (totalDistance - completedDistance).coerceAtLeast(0.0)
        val headingDegrees = if (isSnappedToRoute && routeHeading != null) {
            routeHeading
        } else {
            resolveHeadingDegrees(
                movementHeading = movementHeading,
                sensorHeading = sensorHeading,
                routeHeading = routeHeading,
            )
        }

        return RouteNavigationProgress(
            nearestRoutePointIndex = nearestIndex,
            nearestSegmentStartIndex = nearestProjection.segmentStartIndex,
            distanceAlongRouteMeters = forwardDistanceAlongRoute,
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
            isLocationSnappedToRoute = isSnappedToRoute,
            headingDegrees = headingDegrees,
            travelDirection = travelDirection,
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

private fun RouteProjection.distanceAlongRouteMeters(cumulativeDistances: List<Double>): Double {
    return (cumulativeDistances.getOrElse(segmentStartIndex) { 0.0 } + distanceAlongSegmentMeters)
        .coerceAtLeast(0.0)
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

    var closestProjection: RouteProjection? = null
    for (segmentStartIndex in 0 until lastIndex) {
        val projection = projectPointOntoSegment(
            referencePoint = referencePoint,
            segmentStart = get(segmentStartIndex),
            segmentEnd = get(segmentStartIndex + 1),
            segmentStartIndex = segmentStartIndex,
        )
        if (
            closestProjection == null ||
            projection.distanceToRouteMeters < closestProjection.distanceToRouteMeters
        ) {
            closestProjection = projection
        }
    }

    return closestProjection ?: RouteProjection(
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
    val segmentLengthSquared = (segmentVectorX * segmentVectorX) + (segmentVectorY * segmentVectorY)

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
        bearingDegrees = referencePoint.bearingDegrees,
    )
    val segmentLengthMeters = sqrt(segmentLengthSquared)

    return RouteProjection(
        segmentStartIndex = segmentStartIndex,
        nearestRoutePointIndex = if (t < 0.5) segmentStartIndex else segmentStartIndex + 1,
        projectedPoint = projectedPoint,
        distanceAlongSegmentMeters = segmentLengthMeters * t,
        distanceToRouteMeters = sqrt(
            ((point.x - projectedX) * (point.x - projectedX)) +
                ((point.y - projectedY) * (point.y - projectedY)),
        ),
    )
}

private fun List<LatLngPoint>.estimateSpeedMetersPerSecond(latestPoint: LatLngPoint): Double? {
    val samples = ArrayList<LatLngPoint>(MAX_SPEED_SAMPLES)
    var inspectedSamples = 0

    fun collectSample(point: LatLngPoint): Boolean {
        inspectedSamples += 1
        if (point.timestampMillis != null) {
            samples.add(point)
        }
        return inspectedSamples < MAX_SPEED_SAMPLES
    }

    if (collectSample(latestPoint)) {
        for (index in lastIndex downTo 0) {
            if (!collectSample(this[index])) {
                break
            }
        }
    }

    if (samples.size < 2) {
        return null
    }

    samples.reverse()
    val first = samples.first()
    val last = samples.last()
    val startTime = first.timestampMillis ?: return null
    val endTime = last.timestampMillis ?: return null
    val elapsedSeconds = (endTime - startTime) / 1000.0

    if (elapsedSeconds <= 0.0) {
        return null
    }

    var distanceMeters = 0.0
    for (index in 1 until samples.size) {
        distanceMeters += samples[index - 1].distanceTo(samples[index])
    }
    return distanceMeters / elapsedSeconds
}

private fun List<LatLngPoint>.movementHeadingDegrees(latestPoint: LatLngPoint): Double? {
    if (isEmpty()) {
        return null
    }

    var anchorPoint: LatLngPoint? = null
    val oldestIndex = (size - (MAX_HEADING_SAMPLES - 1)).coerceAtLeast(0)
    for (index in lastIndex downTo oldestIndex) {
        val candidate = this[index]
        if (candidate.distanceTo(latestPoint) >= MIN_HEADING_DISTANCE_METERS) {
            anchorPoint = candidate
            break
        }
    }

    anchorPoint ?: return null

    return anchorPoint.headingDegreesTo(latestPoint)
}

private fun List<LatLngPoint>.headingDegreesForSegment(segmentStartIndex: Int): Double? {
    if (size < 2) {
        return null
    }

    val start = getOrNull(segmentStartIndex) ?: return null
    val end = getOrNull(segmentStartIndex + 1) ?: return null
    return start.headingDegreesTo(end)
}

private fun LatLngPoint.distanceTo(other: LatLngPoint): Double {
    val earthRadiusMeters = 6_371_000.0
    val latDistance = (other.latitude - latitude).toRadians()
    val lonDistance = (other.longitude - longitude).toRadians()

    val startLat = latitude.toRadians()
    val endLat = other.latitude.toRadians()

    val sinHalfLat = sin(latDistance / 2)
    val sinHalfLon = sin(lonDistance / 2)
    val a = (sinHalfLat * sinHalfLat) +
        cos(startLat) * cos(endLat) * (sinHalfLon * sinHalfLon)

    return 2 * earthRadiusMeters * asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
}

private fun LatLngPoint.headingDegreesTo(other: LatLngPoint): Double {
    val averageLatitudeRadians = ((latitude + other.latitude) / 2.0).toRadians()
    val longitudeDelta = (other.longitude - longitude) * cos(averageLatitudeRadians)
    val latitudeDelta = other.latitude - latitude
    return Math.toDegrees(kotlin.math.atan2(longitudeDelta, latitudeDelta)).normalizeHeadingDegrees()
}

private fun resolveTravelDirection(
    recentRouteDistances: List<Double>,
    totalDistance: Double,
    isLoopRoute: Boolean,
    movementHeading: Double?,
    sensorHeading: Double?,
    routeHeadingForward: Double?,
): RouteTravelDirection {
    resolveRouteDistanceDelta(
        routeDistances = recentRouteDistances,
        totalDistance = totalDistance,
        isLoopRoute = isLoopRoute,
    )?.let { routeDistanceDelta ->
        return if (routeDistanceDelta >= 0.0) {
            RouteTravelDirection.Forward
        } else {
            RouteTravelDirection.Backward
        }
    }

    val headingToCompare = movementHeading ?: sensorHeading ?: return RouteTravelDirection.Forward
    val forwardHeading = routeHeadingForward ?: return RouteTravelDirection.Forward

    return if (headingDifferenceDegrees(headingToCompare, forwardHeading) <= 90.0) {
        RouteTravelDirection.Forward
    } else {
        RouteTravelDirection.Backward
    }
}

private fun resolveHeadingDegrees(
    movementHeading: Double?,
    sensorHeading: Double?,
    routeHeading: Double?,
): Double {
    return when {
        movementHeading != null && routeHeading != null ->
            blendHeadingDegrees(
                movementHeading to 0.8,
                routeHeading to 0.2,
            )

        movementHeading != null && sensorHeading != null ->
            blendHeadingDegrees(
                movementHeading to 0.85,
                sensorHeading to 0.15,
            )

        movementHeading != null -> movementHeading
        routeHeading != null && sensorHeading != null ->
            blendHeadingDegrees(
                routeHeading to 0.75,
                sensorHeading to 0.25,
            )

        routeHeading != null -> routeHeading
        sensorHeading != null -> sensorHeading
        else -> 0.0
    }
}

private fun blendHeadingDegrees(vararg headings: Pair<Double, Double>): Double {
    val totalWeight = headings.sumOf { it.second }.takeIf { it > 0.0 } ?: return 0.0
    val weightedSin = headings.sumOf { (heading, weight) ->
        sin(heading.toRadians()) * weight
    }
    val weightedCos = headings.sumOf { (heading, weight) ->
        cos(heading.toRadians()) * weight
    }
    if (weightedSin == 0.0 && weightedCos == 0.0) {
        return headings.first().first.normalizeHeadingDegrees()
    }

    return Math.toDegrees(kotlin.math.atan2(weightedSin / totalWeight, weightedCos / totalWeight))
        .normalizeHeadingDegrees()
}

private fun reverseHeadingDegrees(headingDegrees: Double): Double {
    return (headingDegrees + 180.0).normalizeHeadingDegrees()
}

private fun headingDifferenceDegrees(first: Double, second: Double): Double {
    val difference = kotlin.math.abs(first.normalizeHeadingDegrees() - second.normalizeHeadingDegrees())
    return if (difference > 180.0) 360.0 - difference else difference
}

private fun resolveRouteDistanceDelta(
    routeDistances: List<Double>,
    totalDistance: Double,
    isLoopRoute: Boolean,
): Double? {
    if (routeDistances.size < 2 || totalDistance <= 0.0) {
        return null
    }

    val latestDistance = routeDistances.last()
    for (index in routeDistances.lastIndex - 1 downTo 0) {
        val candidateDistance = routeDistances[index]
        val routeDistanceDelta = if (isLoopRoute) {
            circularDistanceDelta(
                fromDistance = candidateDistance,
                toDistance = latestDistance,
                totalDistance = totalDistance,
            )
        } else {
            latestDistance - candidateDistance
        }
        if (kotlin.math.abs(routeDistanceDelta) >= MIN_ROUTE_DISTANCE_PROGRESS_METERS) {
            return routeDistanceDelta
        }
    }
    return null
}

private fun circularDistanceDelta(
    fromDistance: Double,
    toDistance: Double,
    totalDistance: Double,
): Double {
    val rawDelta = toDistance - fromDistance
    val halfDistance = totalDistance / 2.0
    return when {
        rawDelta > halfDistance -> rawDelta - totalDistance
        rawDelta < -halfDistance -> rawDelta + totalDistance
        else -> rawDelta
    }
}

private fun Double.normalizeHeadingDegrees(): Double {
    return ((this % 360.0) + 360.0) % 360.0
}

private fun Double.toRadians(): Double = this * PI / 180.0

private fun List<LatLngPoint>.isClosedLoop(): Boolean {
    return size >= 4 && first().distanceTo(last()) <= CLOSED_LOOP_ROUTE_THRESHOLD_METERS
}

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

private fun LocalPoint.toLatLngPoint(
    reference: LocalReference,
    timestampMillis: Long?,
    bearingDegrees: Double?,
): LatLngPoint {
    val metersPerDegreeLatitude = EARTH_RADIUS_METERS * PI / 180.0
    val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(reference.latitude.toRadians())

    return LatLngPoint(
        latitude = reference.latitude + (y / metersPerDegreeLatitude),
        longitude = reference.longitude + (x / metersPerDegreeLongitude),
        timestampMillis = timestampMillis,
        bearingDegrees = bearingDegrees,
    )
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val MAX_SPEED_SAMPLES = 6
private const val MAX_HEADING_SAMPLES = 5
private const val MAX_ROUTE_PROGRESS_SAMPLES = 6
private const val MIN_MOVING_SPEED_METERS_PER_SECOND = 0.6
private const val MIN_HEADING_DISTANCE_METERS = 8.0
private const val MIN_ROUTE_DISTANCE_PROGRESS_METERS = 6.0
private const val MAX_ROUTE_SNAP_DISTANCE_METERS = 30.0
private const val CLOSED_LOOP_ROUTE_THRESHOLD_METERS = 35.0
