package com.openroute.app.location

import com.openroute.app.data.LatLngPoint
import com.openroute.app.data.RouteNavigationEngine
import com.openroute.app.data.RouteNavigationProgress
import com.openroute.app.data.RouteSource
import com.openroute.app.data.RouteTrack
import com.openroute.app.data.RouteTravelDirection
import com.openroute.app.data.distanceMeters
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
    val maxDistanceToStartMeters: Double = 0.0,
    val lastDistanceToStartMeters: Double? = null,
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
        val returnCandidate = progress
            ?.takeIf { currentProgress ->
                currentProgress.distanceToRouteMeters <= RETURN_DETECTION_ROUTE_DISTANCE_METERS
            }
            ?.let { currentProgress ->
                shortestRouteToStart(
                    points = state.points,
                    progress = currentProgress,
                )
            }
        val currentDistanceToStart = returnCandidate?.distanceMeters
            ?: progress?.distanceAlongRouteMeters
            ?: state.points.distanceMeters()
        val furthestDistance = max(
            state.maxDistanceToStartMeters,
            currentDistanceToStart,
        )

        if (
            progress != null &&
            shouldSwitchToReturning(
                route = route,
                progress = progress,
                currentDistanceToStart = currentDistanceToStart,
                furthestDistance = furthestDistance,
                previousDistanceToStart = state.lastDistanceToStartMeters,
            )
        ) {
            val returnRoute = returnCandidate ?: shortestRouteToStart(state.points, progress)
            if (returnRoute == null) {
                return state
            }
            val trimmedPoints = returnRoute.points
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
                maxDistanceToStartMeters = returnRoute.distanceMeters,
                lastDistanceToStartMeters = returnRoute.distanceMeters,
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
        val updatedDistanceToStart = updatedProgress
            ?.let { currentProgress ->
                distanceToStart(
                    points = points,
                    progress = currentProgress,
                )
            }
            ?: points.distanceMeters()

        return state.copy(
            points = points,
            currentLocation = point,
            recentLocations = recentLocations,
            progress = updatedProgress,
            maxDistanceToStartMeters = max(
                furthestDistance,
                updatedDistanceToStart,
            ),
            lastDistanceToStartMeters = updatedDistanceToStart,
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
        val returnProgress = progress?.let { currentProgress ->
            route?.let { currentRoute -> currentProgress.asReturnProgress(currentRoute) }
        }
        if (
            returnProgress != null &&
            returnProgress.distanceToRouteMeters <= RETURNING_ROUTE_SNAP_DISTANCE_METERS &&
            returnProgress.remainingDistanceMeters <= RETURN_ARRIVAL_DISTANCE_METERS
        ) {
            return stop()
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
                maxDistanceToStartMeters = updatedProgress
                    ?.let { currentProgress ->
                        distanceToStart(points, currentProgress)
                    }
                    ?: updatedRoute?.distanceMeters
                    ?: points.distanceMeters(),
                lastDistanceToStartMeters = updatedProgress
                    ?.let { currentProgress ->
                        distanceToStart(points, currentProgress)
                    },
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
            maxDistanceToStartMeters = updatedRoute?.distanceMeters ?: points.distanceMeters(),
            lastDistanceToStartMeters = updatedProgress?.remainingDistanceMeters,
        )
    }

    private fun shouldSwitchToReturning(
        route: RouteTrack,
        progress: RouteNavigationProgress,
        currentDistanceToStart: Double,
        furthestDistance: Double,
        previousDistanceToStart: Double?,
    ): Boolean {
        val isMovingTowardStart = previousDistanceToStart?.let { previousDistance ->
            currentDistanceToStart < previousDistance - RETURN_DETECTION_DISTANCE_DROP_METERS
        } == true

        return route.distanceMeters >= MIN_ROUTE_DISTANCE_BEFORE_RETURN_METERS &&
            progress.distanceToRouteMeters <= RETURN_DETECTION_ROUTE_DISTANCE_METERS &&
            furthestDistance - currentDistanceToStart >= TURN_BACK_DISTANCE_METERS &&
            isMovingTowardStart
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

    private fun distanceToStart(
        points: List<LatLngPoint>,
        progress: RouteNavigationProgress,
    ): Double {
        return shortestRouteToStart(points, progress)?.distanceMeters ?: progress.distanceAlongRouteMeters
    }

    private fun shortestRouteToStart(
        points: List<LatLngPoint>,
        progress: RouteNavigationProgress,
    ): BreadcrumbReturnRoute? {
        if (points.size < 2) {
            return null
        }

        val anchor = progress.displayLocation ?: points.getOrNull(progress.nearestRoutePointIndex) ?: points.last()
        val anchorSegmentIndex = progress.nearestSegmentStartIndex.coerceIn(0, points.lastIndex - 1)

        return BreadcrumbTrailGraph(
            points = points,
            anchor = anchor,
            anchorSegmentIndex = anchorSegmentIndex,
        ).shortestPathToStart()?.takeIf { route -> route.points.size >= 2 }
            ?: BreadcrumbReturnRoute(
                points = trimTrailToProgress(points, progress),
                distanceMeters = progress.distanceAlongRouteMeters,
            )
    }

    private data class BreadcrumbReturnRoute(
        val points: List<LatLngPoint>,
        val distanceMeters: Double,
    ) {
        fun toRouteTrack(startedAtMillis: Long): RouteTrack? {
            return points.toBreadcrumbRoute(startedAtMillis)
        }
    }

    private class BreadcrumbTrailGraph(
        private val points: List<LatLngPoint>,
        private val anchor: LatLngPoint,
        private val anchorSegmentIndex: Int,
    ) {
        private val reference = GraphReference(
            latitude = points.first().latitude,
            longitude = points.first().longitude,
        )
        private val localPoints = points.map { point -> point.toGraphPoint(reference) }
        private val nodes = mutableListOf<GraphNode>()
        private val originalNodeIds = IntArray(points.size)
        private val segmentSplits = MutableList(points.lastIndex) { mutableListOf<GraphSplit>() }
        private val segments = List(points.lastIndex) { index ->
            GraphSegment(
                index = index,
                start = localPoints[index],
                end = localPoints[index + 1],
            )
        }
        private val anchorNodeId: Int

        init {
            for (index in points.indices) {
                originalNodeIds[index] = addNode(
                    point = localPoints[index],
                    latLngPoint = points[index],
                )
            }

            for (index in segmentSplits.indices) {
                addSegmentSplit(index, t = 0.0, nodeId = originalNodeIds[index])
                addSegmentSplit(index, t = 1.0, nodeId = originalNodeIds[index + 1])
            }

            val anchorLocalPoint = anchor.toGraphPoint(reference)
            val anchorT = anchorLocalPoint.projectedTOn(segments[anchorSegmentIndex])
            anchorNodeId = endpointNodeId(anchorSegmentIndex, anchorT) ?: addNode(
                point = anchorLocalPoint,
                latLngPoint = anchor,
            )
            addSegmentSplit(anchorSegmentIndex, anchorT, anchorNodeId)

            addKnownIntersections()
        }

        fun shortestPathToStart(): BreadcrumbReturnRoute? {
            val adjacency = buildAdjacency()
            val originNodeId = originalNodeIds.first()
            val path = shortestPathNodeIds(
                adjacency = adjacency,
                startNodeId = anchorNodeId,
                endNodeId = originNodeId,
            )

            if (path.nodeIds.isEmpty()) {
                return null
            }

            val routePoints = path.nodeIds
                .map { nodeId -> nodes[nodeId].latLngPoint }
                .withoutNearDuplicates()

            return BreadcrumbReturnRoute(
                points = routePoints,
                distanceMeters = path.distanceMeters,
            )
        }

        private fun addKnownIntersections() {
            val segmentIndex = mutableMapOf<GraphCell, MutableList<Int>>()
            val inspectedPairs = mutableSetOf<Long>()

            for (segment in segments) {
                for (cell in segment.cells()) {
                    val candidateSegmentIndexes = segmentIndex[cell].orEmpty()
                    for (candidateSegmentIndex in candidateSegmentIndexes) {
                        if (abs(segment.index - candidateSegmentIndex) <= 1) {
                            continue
                        }

                        val pairKey = segment.index.pairKey(candidateSegmentIndex)
                        if (!inspectedPairs.add(pairKey)) {
                            continue
                        }

                        val candidateSegment = segments[candidateSegmentIndex]
                        val intersection = segment.intersectionWith(candidateSegment) ?: continue
                        val commonNodeId = endpointNodeId(segment.index, intersection.firstT)
                            ?: endpointNodeId(candidateSegment.index, intersection.secondT)
                            ?: addNode(
                                point = intersection.point,
                                latLngPoint = intersection.point.toLatLngPoint(reference),
                            )

                        addSegmentSplit(segment.index, intersection.firstT, commonNodeId)
                        addSegmentSplit(candidateSegment.index, intersection.secondT, commonNodeId)
                    }
                }

                for (cell in segment.cells()) {
                    segmentIndex.getOrPut(cell) { mutableListOf() }.add(segment.index)
                }
            }
        }

        private fun buildAdjacency(): List<MutableList<GraphEdge>> {
            val adjacency = List(nodes.size) { mutableListOf<GraphEdge>() }
            for (splits in segmentSplits) {
                val orderedSplits = splits
                    .sortedBy(GraphSplit::t)
                    .distinctBy(GraphSplit::nodeId)
                for (index in 1 until orderedSplits.size) {
                    val fromNodeId = orderedSplits[index - 1].nodeId
                    val toNodeId = orderedSplits[index].nodeId
                    if (fromNodeId == toNodeId) {
                        continue
                    }

                    val distance = nodes[fromNodeId].point.distanceTo(nodes[toNodeId].point)
                    adjacency[fromNodeId].add(GraphEdge(nodeId = toNodeId, distance = distance))
                    adjacency[toNodeId].add(GraphEdge(nodeId = fromNodeId, distance = distance))
                }
            }
            return adjacency
        }

        private fun shortestPathNodeIds(
            adjacency: List<List<GraphEdge>>,
            startNodeId: Int,
            endNodeId: Int,
        ): GraphPath {
            val distances = DoubleArray(nodes.size) { Double.POSITIVE_INFINITY }
            val previous = IntArray(nodes.size) { NO_GRAPH_NODE }
            val queue = PriorityQueue<GraphQueueItem>(compareBy(GraphQueueItem::distance))
            distances[startNodeId] = 0.0
            queue.add(GraphQueueItem(nodeId = startNodeId, distance = 0.0))

            while (queue.isNotEmpty()) {
                val item = queue.poll() ?: break
                if (item.distance > distances[item.nodeId]) {
                    continue
                }
                if (item.nodeId == endNodeId) {
                    break
                }

                for (edge in adjacency[item.nodeId]) {
                    val candidateDistance = item.distance + edge.distance
                    if (candidateDistance < distances[edge.nodeId]) {
                        distances[edge.nodeId] = candidateDistance
                        previous[edge.nodeId] = item.nodeId
                        queue.add(GraphQueueItem(nodeId = edge.nodeId, distance = candidateDistance))
                    }
                }
            }

            if (distances[endNodeId].isInfinite()) {
                return GraphPath()
            }

            val route = mutableListOf<Int>()
            var nodeId = endNodeId
            while (nodeId != NO_GRAPH_NODE) {
                route.add(nodeId)
                if (nodeId == startNodeId) {
                    break
                }
                nodeId = previous[nodeId]
            }
            return GraphPath(
                nodeIds = route,
                distanceMeters = distances[endNodeId],
            )
        }

        private fun endpointNodeId(segmentIndex: Int, t: Double): Int? {
            return when {
                t <= GRAPH_ENDPOINT_TOLERANCE -> originalNodeIds[segmentIndex]
                t >= 1.0 - GRAPH_ENDPOINT_TOLERANCE -> originalNodeIds[segmentIndex + 1]
                else -> null
            }
        }

        private fun addNode(
            point: GraphPoint,
            latLngPoint: LatLngPoint,
        ): Int {
            val nodeId = nodes.size
            nodes.add(GraphNode(point = point, latLngPoint = latLngPoint))
            return nodeId
        }

        private fun addSegmentSplit(
            segmentIndex: Int,
            t: Double,
            nodeId: Int,
        ) {
            if (segmentIndex !in segmentSplits.indices) {
                return
            }

            val clampedT = t.coerceIn(0.0, 1.0)
            val splits = segmentSplits[segmentIndex]
            if (splits.any { split -> split.nodeId == nodeId }) {
                return
            }
            splits.add(GraphSplit(t = clampedT, nodeId = nodeId))
        }
    }

    private data class GraphNode(
        val point: GraphPoint,
        val latLngPoint: LatLngPoint,
    )

    private data class GraphSplit(
        val t: Double,
        val nodeId: Int,
    )

    private data class GraphEdge(
        val nodeId: Int,
        val distance: Double,
    )

    private data class GraphQueueItem(
        val nodeId: Int,
        val distance: Double,
    )

    private data class GraphPath(
        val nodeIds: List<Int> = emptyList(),
        val distanceMeters: Double = 0.0,
    )

    private data class GraphReference(
        val latitude: Double,
        val longitude: Double,
    )

    private data class GraphPoint(
        val x: Double,
        val y: Double,
    ) {
        fun distanceTo(other: GraphPoint): Double {
            return sqrt(((other.x - x) * (other.x - x)) + ((other.y - y) * (other.y - y)))
        }

        fun projectedTOn(segment: GraphSegment): Double {
            val vectorX = segment.end.x - segment.start.x
            val vectorY = segment.end.y - segment.start.y
            val lengthSquared = (vectorX * vectorX) + (vectorY * vectorY)
            if (lengthSquared == 0.0) {
                return 0.0
            }

            return ((((x - segment.start.x) * vectorX) + ((y - segment.start.y) * vectorY)) / lengthSquared)
                .coerceIn(0.0, 1.0)
        }

        fun interpolateTo(other: GraphPoint, t: Double): GraphPoint {
            return GraphPoint(
                x = x + ((other.x - x) * t),
                y = y + ((other.y - y) * t),
            )
        }

        fun toLatLngPoint(reference: GraphReference): LatLngPoint {
            return LatLngPoint(
                latitude = reference.latitude + (y / GRAPH_METERS_PER_DEGREE),
                longitude = reference.longitude +
                    (x / (GRAPH_METERS_PER_DEGREE * kotlin.math.cos(Math.toRadians(reference.latitude)))),
            )
        }
    }

    private data class GraphSegment(
        val index: Int,
        val start: GraphPoint,
        val end: GraphPoint,
    ) {
        fun cells(): List<GraphCell> {
            val minCellX = floor((min(start.x, end.x) - GRAPH_INTERSECTION_SNAP_METERS) / GRAPH_CELL_SIZE_METERS).toInt()
            val maxCellX = floor((max(start.x, end.x) + GRAPH_INTERSECTION_SNAP_METERS) / GRAPH_CELL_SIZE_METERS).toInt()
            val minCellY = floor((min(start.y, end.y) - GRAPH_INTERSECTION_SNAP_METERS) / GRAPH_CELL_SIZE_METERS).toInt()
            val maxCellY = floor((max(start.y, end.y) + GRAPH_INTERSECTION_SNAP_METERS) / GRAPH_CELL_SIZE_METERS).toInt()

            return buildList {
                for (cellX in minCellX..maxCellX) {
                    for (cellY in minCellY..maxCellY) {
                        add(GraphCell(cellX, cellY))
                    }
                }
            }
        }

        fun intersectionWith(other: GraphSegment): GraphIntersection? {
            exactIntersectionWith(other)?.let { intersection ->
                return intersection
            }

            val closest = closestApproachTo(other)
            return closest.takeIf { it.distance <= GRAPH_INTERSECTION_SNAP_METERS }?.let { approach ->
                GraphIntersection(
                    firstT = approach.firstT,
                    secondT = approach.secondT,
                    point = GraphPoint(
                        x = (approach.firstPoint.x + approach.secondPoint.x) / 2.0,
                        y = (approach.firstPoint.y + approach.secondPoint.y) / 2.0,
                    ),
                )
            }
        }

        private fun exactIntersectionWith(other: GraphSegment): GraphIntersection? {
            val rX = end.x - start.x
            val rY = end.y - start.y
            val sX = other.end.x - other.start.x
            val sY = other.end.y - other.start.y
            val denominator = cross(rX, rY, sX, sY)
            if (abs(denominator) <= GRAPH_LINE_EPSILON) {
                return null
            }

            val qMinusPX = other.start.x - start.x
            val qMinusPY = other.start.y - start.y
            val firstT = cross(qMinusPX, qMinusPY, sX, sY) / denominator
            val secondT = cross(qMinusPX, qMinusPY, rX, rY) / denominator
            if (
                firstT !in -GRAPH_ENDPOINT_TOLERANCE..(1.0 + GRAPH_ENDPOINT_TOLERANCE) ||
                secondT !in -GRAPH_ENDPOINT_TOLERANCE..(1.0 + GRAPH_ENDPOINT_TOLERANCE)
            ) {
                return null
            }

            val clampedFirstT = firstT.coerceIn(0.0, 1.0)
            return GraphIntersection(
                firstT = clampedFirstT,
                secondT = secondT.coerceIn(0.0, 1.0),
                point = start.interpolateTo(end, clampedFirstT),
            )
        }

        private fun closestApproachTo(other: GraphSegment): GraphClosestApproach {
            val candidates = listOf(
                closestFromPointToSegment(start, other).toApproach(firstT = 0.0),
                closestFromPointToSegment(end, other).toApproach(firstT = 1.0),
                closestFromPointToSegment(other.start, this).toReversedApproach(secondT = 0.0),
                closestFromPointToSegment(other.end, this).toReversedApproach(secondT = 1.0),
            )

            return candidates.minBy(GraphClosestApproach::distance)
        }
    }

    private data class GraphCell(
        val x: Int,
        val y: Int,
    )

    private data class GraphIntersection(
        val firstT: Double,
        val secondT: Double,
        val point: GraphPoint,
    )

    private data class GraphClosestProjection(
        val t: Double,
        val point: GraphPoint,
        val distance: Double,
        val sourcePoint: GraphPoint,
    ) {
        fun toApproach(firstT: Double): GraphClosestApproach {
            return GraphClosestApproach(
                firstT = firstT,
                secondT = t,
                firstPoint = sourcePoint,
                secondPoint = point,
                distance = distance,
            )
        }

        fun toReversedApproach(secondT: Double): GraphClosestApproach {
            return GraphClosestApproach(
                firstT = t,
                secondT = secondT,
                firstPoint = point,
                secondPoint = sourcePoint,
                distance = distance,
            )
        }
    }

    private data class GraphClosestApproach(
        val firstT: Double,
        val secondT: Double,
        val firstPoint: GraphPoint,
        val secondPoint: GraphPoint,
        val distance: Double,
    )

    private fun closestFromPointToSegment(
        point: GraphPoint,
        segment: GraphSegment,
    ): GraphClosestProjection {
        val t = point.projectedTOn(segment)
        val projectedPoint = segment.start.interpolateTo(segment.end, t)
        return GraphClosestProjection(
            t = t,
            point = projectedPoint,
            distance = point.distanceTo(projectedPoint),
            sourcePoint = point,
        )
    }

    private fun LatLngPoint.toGraphPoint(reference: GraphReference): GraphPoint {
        return GraphPoint(
            x = (longitude - reference.longitude) *
                GRAPH_METERS_PER_DEGREE *
                kotlin.math.cos(Math.toRadians(reference.latitude)),
            y = (latitude - reference.latitude) * GRAPH_METERS_PER_DEGREE,
        )
    }

    private fun List<LatLngPoint>.withoutNearDuplicates(): List<LatLngPoint> {
        return buildList {
            for (point in this@withoutNearDuplicates) {
                val previousPoint = lastOrNull()
                if (previousPoint == null || previousPoint.distanceTo(point) > GRAPH_DUPLICATE_POINT_METERS) {
                    add(point)
                }
            }
        }
    }

    private fun Int.pairKey(other: Int): Long {
        val first = min(this, other).toLong()
        val second = max(this, other).toLong()
        return (first shl 32) or second
    }

    private fun cross(
        firstX: Double,
        firstY: Double,
        secondX: Double,
        secondY: Double,
    ): Double {
        return (firstX * secondY) - (firstY * secondX)
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
    private const val RETURN_DETECTION_ROUTE_DISTANCE_METERS = 25.0
    private const val RETURN_DETECTION_DISTANCE_DROP_METERS = 1.0
    private const val RETURNING_ROUTE_SNAP_DISTANCE_METERS = 25.0
    private const val RETURN_ARRIVAL_DISTANCE_METERS = 8.0
    private const val TURN_BACK_DISTANCE_METERS = 18.0
    private const val BRANCH_OFF_ROUTE_DISTANCE_METERS = 35.0
    private const val GRAPH_METERS_PER_DEGREE = 111_320.0
    private const val GRAPH_CELL_SIZE_METERS = 30.0
    private const val GRAPH_INTERSECTION_SNAP_METERS = 12.0
    private const val GRAPH_ENDPOINT_TOLERANCE = 0.001
    private const val GRAPH_DUPLICATE_POINT_METERS = 0.5
    private const val GRAPH_LINE_EPSILON = 0.000001
    private const val NO_GRAPH_NODE = -1
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
