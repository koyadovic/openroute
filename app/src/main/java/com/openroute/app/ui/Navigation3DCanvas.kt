package com.openroute.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openroute.app.R
import com.openroute.app.data.LatLngPoint
import com.openroute.app.data.Navigation3DRenderState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun Navigation3DCanvas(
    state: Navigation3DRenderState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val nearBandWidth = with(density) { 100.dp.toPx() }
    val farBandWidth = with(density) { 3.dp.toPx() }
    val bandShadowOffsetY = with(density) { 8.dp.toPx() }
    val triangleStyle = remember(density) {
        NavigationTriangleStyle(
            shadowTipOffset = with(density) { 50.dp.toPx() },
            shadowSideOffset = with(density) { 29.dp.toPx() },
            shadowTailOffset = with(density) { 24.dp.toPx() },
            shadowInnerTailOffset = with(density) { 11.dp.toPx() },
            tipOffset = with(density) { 42.dp.toPx() },
            sideOffset = with(density) { 24.dp.toPx() },
            tailOffset = with(density) { 20.dp.toPx() },
            innerTailOffset = with(density) { 9.dp.toPx() },
        )
    }
    val animatedLatitude = remember {
        Animatable(state.currentLocation?.latitude?.toFloat() ?: 0f)
    }
    val animatedLongitude = remember {
        Animatable(state.currentLocation?.longitude?.toFloat() ?: 0f)
    }
    val animatedHeading = remember {
        Animatable(state.headingDegrees.toFloat())
    }
    var isAnimationPrimed by remember { mutableStateOf(false) }
    var previousTargetLocation by remember { mutableStateOf<LatLngPoint?>(null) }

    LaunchedEffect(
        state.currentLocation?.latitude,
        state.currentLocation?.longitude,
        state.headingDegrees,
    ) {
        val targetLocation = state.currentLocation
        if (targetLocation == null) {
            isAnimationPrimed = false
            previousTargetLocation = null
            return@LaunchedEffect
        }

        val currentAnimatedLocation = LatLngPoint(
            latitude = animatedLatitude.value.toDouble(),
            longitude = animatedLongitude.value.toDouble(),
        )
        val distanceMeters = currentAnimatedLocation.distanceTo(targetLocation)
        val headingDelta = shortestAngleDeltaDegrees(
            fromDegrees = animatedHeading.value.toDouble(),
            toDegrees = state.headingDegrees,
        )
        val predictionDistanceMeters = if (isAnimationPrimed && !state.isOffRoute) {
            predictedDistanceMeters(
                previousLocation = previousTargetLocation,
                targetLocation = targetLocation,
            )
        } else {
            0.0
        }
        val animationTargetLocation = if (predictionDistanceMeters > 0.0) {
            targetLocation.projectedForward(
                headingDegrees = state.headingDegrees,
                distanceMeters = predictionDistanceMeters,
            )
        } else {
            targetLocation
        }
        val animationDurationMillis = transitionDurationMillis(
            distanceMeters = currentAnimatedLocation.distanceTo(animationTargetLocation),
            headingDeltaDegrees = headingDelta,
        )

        if (!isAnimationPrimed || distanceMeters > MAX_ANIMATED_STEP_DISTANCE_METERS) {
            animatedLatitude.snapTo(targetLocation.latitude.toFloat())
            animatedLongitude.snapTo(targetLocation.longitude.toFloat())
            animatedHeading.snapTo(state.headingDegrees.toFloat())
            previousTargetLocation = targetLocation
            isAnimationPrimed = true
            return@LaunchedEffect
        }
        previousTargetLocation = targetLocation

        coroutineScope {
            launch {
                animatedLatitude.animateTo(
                    targetValue = animationTargetLocation.latitude.toFloat(),
                    animationSpec = tween(
                        durationMillis = animationDurationMillis,
                        easing = LinearEasing,
                    ),
                )
            }
            launch {
                animatedLongitude.animateTo(
                    targetValue = animationTargetLocation.longitude.toFloat(),
                    animationSpec = tween(
                        durationMillis = animationDurationMillis,
                        easing = LinearEasing,
                    ),
                )
            }
            launch {
                animatedHeading.animateTo(
                    targetValue = animatedHeading.value + headingDelta.toFloat(),
                    animationSpec = tween(
                        durationMillis = animationDurationMillis,
                        easing = LinearEasing,
                    ),
                )
            }
        }
        isAnimationPrimed = true
    }

    val animatedCurrentLocation = state.currentLocation?.copy(
        latitude = animatedLatitude.value.toDouble(),
        longitude = animatedLongitude.value.toDouble(),
    )
    val animatedHeadingDegrees = animatedHeading.value.toDouble().normalizeHeadingDegrees()
    val waitingLocationMessage = stringResource(R.string.navigation_waiting_location)
    val notEnoughRouteMessage = stringResource(R.string.navigation_3d_not_enough_route)
    val message = remember(state, waitingLocationMessage, notEnoughRouteMessage) {
        when {
            state.currentLocation == null -> waitingLocationMessage
            state.routePoints.size < 2 -> notEnoughRouteMessage
            else -> null
        }
    }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = if (state.isOffRoute) {
                        listOf(Color(0xFF5F1D1D), Color(0xFF261417))
                    } else {
                        listOf(Color(0xFF173443), Color(0xFF0D1B22))
                    },
                ),
                size = size,
            )

            drawPerspectiveGrid(
                size = size,
                color = if (state.isOffRoute) Color(0x26FFD2D2) else Color(0x14FFFFFF),
            )

            val currentLocation = animatedCurrentLocation
            if (message == null && currentLocation != null) {
                val projectedRoute = state.routePoints.mapNotNull { point ->
                    point.projectToScene(
                        origin = currentLocation,
                        headingDegrees = animatedHeadingDegrees,
                        viewport = size,
                    )
                }.removeNearDuplicates()

                val currentProjection = projectWorldPoint(
                    worldPoint = WorldPoint(0.0, 0.0, 0.0),
                    viewport = size,
                )

                if (currentProjection != null && projectedRoute.size >= 2) {
                    val anchor = Offset(size.width / 2f, size.height * 0.82f)
                    val translation = Offset(
                        x = anchor.x - currentProjection.x,
                        y = anchor.y - currentProjection.y,
                    )
                    val translatedRoute = projectedRoute.map { it + translation }
                    val bandColor = if (state.isOffRoute) Color(0xFFFF8A8A) else Color(0xFF4FA3FF)
                    val bandShadowColor = if (state.isOffRoute) Color(0x66261117) else Color(0x6612243C)

                    drawBand(
                        points = translatedRoute.map { point ->
                            Offset(point.x, point.y + bandShadowOffsetY)
                        },
                        color = bandShadowColor,
                        nearWidth = nearBandWidth,
                        farWidth = farBandWidth,
                        viewport = size,
                    )
                    drawBand(
                        points = translatedRoute,
                        color = bandColor,
                        nearWidth = nearBandWidth,
                        farWidth = farBandWidth,
                        viewport = size,
                    )
                    drawNavigationTriangle(
                        center = anchor,
                        fillColor = Color(0xFFE63946),
                        style = triangleStyle,
                    )
                }
            }
        }

        message?.let {
            Text(
                text = it,
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPerspectiveGrid(
    size: Size,
    color: Color,
) {
    val horizonY = size.height * 0.30f
    repeat(8) { index ->
        val t = index / 7f
        val y = horizonY + ((size.height - horizonY) * t * t)
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBand(
    points: List<Offset>,
    color: Color,
    nearWidth: Float,
    farWidth: Float,
    viewport: Size,
) {
    val ribbon = buildRibbon(points, viewport, nearWidth, farWidth) ?: return
    if (ribbon.left.size < 2 || ribbon.right.size < 2) {
        return
    }

    val path = Path().apply {
        moveTo(ribbon.left.first().x, ribbon.left.first().y)
        ribbon.left.drop(1).forEach { point ->
            lineTo(point.x, point.y)
        }
        ribbon.right.asReversed().forEach { point ->
            lineTo(point.x, point.y)
        }
        close()
    }
    drawPath(path = path, color = color)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNavigationTriangle(
    center: Offset,
    fillColor: Color,
    style: NavigationTriangleStyle,
) {
    val shadowPath = Path().apply {
        moveTo(center.x, center.y - style.shadowTipOffset)
        lineTo(center.x + style.shadowSideOffset, center.y + style.shadowTailOffset)
        lineTo(center.x, center.y + style.shadowInnerTailOffset)
        lineTo(center.x - style.shadowSideOffset, center.y + style.shadowTailOffset)
        close()
    }
    drawPath(
        path = shadowPath,
        color = Color.Black.copy(alpha = 0.18f),
    )

    val trianglePath = Path().apply {
        moveTo(center.x, center.y - style.tipOffset)
        lineTo(center.x + style.sideOffset, center.y + style.tailOffset)
        lineTo(center.x, center.y + style.innerTailOffset)
        lineTo(center.x - style.sideOffset, center.y + style.tailOffset)
        close()
    }
    drawPath(path = trianglePath, color = fillColor)
}

private data class NavigationTriangleStyle(
    val shadowTipOffset: Float,
    val shadowSideOffset: Float,
    val shadowTailOffset: Float,
    val shadowInnerTailOffset: Float,
    val tipOffset: Float,
    val sideOffset: Float,
    val tailOffset: Float,
    val innerTailOffset: Float,
)

private data class RibbonOutline(
    val left: List<Offset>,
    val right: List<Offset>,
)

private fun buildRibbon(
    points: List<Offset>,
    viewport: Size,
    nearWidth: Float,
    farWidth: Float,
): RibbonOutline? {
    val sanitizedPoints = points.removeNearDuplicates()
    if (sanitizedPoints.size < 2) {
        return null
    }

    val leftPoints = mutableListOf<Offset>()
    val rightPoints = mutableListOf<Offset>()
    for (index in sanitizedPoints.indices) {
        val center = sanitizedPoints[index]
        val halfWidth = perspectiveWidthFor(
            point = center,
            viewport = viewport,
            nearWidth = nearWidth,
            farWidth = farWidth,
        ) / 2f
        val previousDirection = directionBetween(
            from = sanitizedPoints.getOrNull(index - 1),
            to = center,
        )
        val nextDirection = directionBetween(
            from = center,
            to = sanitizedPoints.getOrNull(index + 1),
        )
        val previousNormal = previousDirection?.leftNormal()
        val nextNormal = nextDirection?.leftNormal()
        val baseNormal = nextNormal ?: previousNormal ?: continue
        val miterNormal = when {
            previousNormal != null && nextNormal != null ->
                (previousNormal + nextNormal).normalizedOrNull() ?: baseNormal

            else -> baseNormal
        }
        val alignment = abs(miterNormal dot baseNormal).coerceAtLeast(0.52f)
        val miterLength = min(halfWidth / alignment, halfWidth * 1.65f)
        val offset = miterNormal * miterLength
        leftPoints += center + offset
        rightPoints += center - offset
    }

    return RibbonOutline(
        left = leftPoints,
        right = rightPoints,
    )
}

private fun perspectiveWidthFor(
    point: Offset,
    viewport: Size,
    nearWidth: Float,
    farWidth: Float,
): Float {
    val horizonY = viewport.height * 0.28f
    val normalizedDepth = ((point.y - horizonY) / (viewport.height - horizonY))
        .coerceIn(0f, 1f)
    val depthWeight = normalizedDepth.toDouble().pow(2.35).toFloat()
    return farWidth + ((nearWidth - farWidth) * depthWeight)
}

private fun LatLngPoint.projectToScene(
    origin: LatLngPoint,
    headingDegrees: Double,
    viewport: Size,
): Offset? {
    val local = toLocalMeters(origin)
    val aligned = rotateAroundY(
        x = local.first,
        z = local.second,
        angleRadians = Math.toRadians(headingDegrees),
    )
    return projectWorldPoint(
        worldPoint = WorldPoint(aligned.first, 0.0, aligned.second),
        viewport = viewport,
    )
}

private fun LatLngPoint.toLocalMeters(origin: LatLngPoint): Pair<Double, Double> {
    val latFactor = 111_320.0
    val lonFactor = cos(Math.toRadians(origin.latitude)) * 111_320.0
    return Pair(
        (longitude - origin.longitude) * lonFactor,
        (latitude - origin.latitude) * latFactor,
    )
}

private fun rotateAroundY(x: Double, z: Double, angleRadians: Double): Pair<Double, Double> {
    val cos = cos(angleRadians)
    val sin = sin(angleRadians)
    return Pair(
        (x * cos) - (z * sin),
        (x * sin) + (z * cos),
    )
}

private fun transitionDurationMillis(
    distanceMeters: Double,
    headingDeltaDegrees: Double,
): Int {
    val distanceDuration = (320 + (distanceMeters * 12)).roundToInt()
        .coerceIn(320, 1_900)
    val headingDuration = (180 + (headingDeltaDegrees * 4)).roundToInt()
        .coerceIn(180, 900)
    return max(distanceDuration, headingDuration)
}

private fun predictedDistanceMeters(
    previousLocation: LatLngPoint?,
    targetLocation: LatLngPoint,
): Double {
    val previous = previousLocation ?: return 0.0
    val previousTimestamp = previous.timestampMillis ?: return 0.0
    val targetTimestamp = targetLocation.timestampMillis ?: return 0.0
    val elapsedSeconds = ((targetTimestamp - previousTimestamp) / 1000.0)
        .takeIf { seconds -> seconds in 0.4..8.0 }
        ?: return 0.0
    val speedMetersPerSecond = previous.distanceTo(targetLocation) / elapsedSeconds
    return (speedMetersPerSecond * PREDICTION_SECONDS)
        .coerceIn(0.0, MAX_PREDICTION_DISTANCE_METERS)
}

private fun LatLngPoint.projectedForward(
    headingDegrees: Double,
    distanceMeters: Double,
): LatLngPoint {
    if (distanceMeters <= 0.0) {
        return this
    }

    val headingRadians = Math.toRadians(headingDegrees)
    val latitudeDelta = (cos(headingRadians) * distanceMeters) / METERS_PER_DEGREE_LATITUDE
    val longitudeDelta = (sin(headingRadians) * distanceMeters) /
        (METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))

    return copy(
        latitude = latitude + latitudeDelta,
        longitude = longitude + longitudeDelta,
    )
}

private fun shortestAngleDeltaDegrees(fromDegrees: Double, toDegrees: Double): Double {
    val normalizedFrom = fromDegrees.normalizeHeadingDegrees()
    val normalizedTo = toDegrees.normalizeHeadingDegrees()
    val rawDelta = normalizedTo - normalizedFrom
    return when {
        rawDelta > 180.0 -> rawDelta - 360.0
        rawDelta < -180.0 -> rawDelta + 360.0
        else -> rawDelta
    }
}

private fun Double.normalizeHeadingDegrees(): Double {
    return ((this % 360.0) + 360.0) % 360.0
}

private fun LatLngPoint.distanceTo(other: LatLngPoint): Double {
    val earthRadiusMeters = 6_371_000.0
    val latDistance = Math.toRadians(other.latitude - latitude)
    val lonDistance = Math.toRadians(other.longitude - longitude)
    val startLat = Math.toRadians(latitude)
    val endLat = Math.toRadians(other.latitude)
    val a = sin(latDistance / 2).let { it * it } +
        cos(startLat) * cos(endLat) * sin(lonDistance / 2).let { it * it }

    return 2 * earthRadiusMeters * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
}

private const val MAX_ANIMATED_STEP_DISTANCE_METERS = 90.0
private const val PREDICTION_SECONDS = 1.2
private const val MAX_PREDICTION_DISTANCE_METERS = 12.0
private const val METERS_PER_DEGREE_LATITUDE = 111_320.0

private fun projectWorldPoint(worldPoint: WorldPoint, viewport: Size): Offset? {
    val camera = WorldPoint(0.0, 12.0, -38.0)
    val target = WorldPoint(0.0, -3.0, 48.0)
    val forward = normalize(target - camera)
    val worldUp = WorldPoint(0.0, 1.0, 0.0)
    val right = normalize(cross(worldUp, forward))
    val up = cross(forward, right)
    val relative = worldPoint - camera
    val cameraX = dot(relative, right)
    val cameraY = dot(relative, up)
    val cameraZ = dot(relative, forward)

    if (cameraZ <= 0.5) {
        return null
    }

    val focal = viewport.height * 0.88
    return Offset(
        x = (viewport.width / 2f) + ((cameraX * focal) / cameraZ).toFloat(),
        y = (viewport.height * 0.72f) - ((cameraY * focal) / cameraZ).toFloat(),
    )
}

private data class WorldPoint(
    val x: Double,
    val y: Double,
    val z: Double,
)

private operator fun WorldPoint.minus(other: WorldPoint): WorldPoint {
    return WorldPoint(x - other.x, y - other.y, z - other.z)
}

private fun dot(a: WorldPoint, b: WorldPoint): Double {
    return (a.x * b.x) + (a.y * b.y) + (a.z * b.z)
}

private fun cross(a: WorldPoint, b: WorldPoint): WorldPoint {
    return WorldPoint(
        x = (a.y * b.z) - (a.z * b.y),
        y = (a.z * b.x) - (a.x * b.z),
        z = (a.x * b.y) - (a.y * b.x),
    )
}

private fun normalize(vector: WorldPoint): WorldPoint {
    val length = max(1e-6, hypot(hypot(vector.x, vector.y), vector.z))
    return WorldPoint(
        x = vector.x / length,
        y = vector.y / length,
        z = vector.z / length,
    )
}

private fun directionBetween(
    from: Offset?,
    to: Offset?,
): Offset? {
    if (from == null || to == null) {
        return null
    }
    return (to - from).normalizedOrNull()
}

private fun List<Offset>.removeNearDuplicates(): List<Offset> {
    if (size < 2) {
        return this
    }

    return buildList(size) {
        for (point in this@removeNearDuplicates) {
            val previous = lastOrNull()
            if (previous == null || previous.distanceTo(point) > 0.5f) {
                add(point)
            }
        }
    }
}

private fun Offset.distanceTo(other: Offset): Float {
    return sqrt(((other.x - x) * (other.x - x)) + ((other.y - y) * (other.y - y)))
}

private fun Offset.normalizedOrNull(): Offset? {
    val length = sqrt((x * x) + (y * y))
    if (length <= 0.0001f) {
        return null
    }
    return Offset(x / length, y / length)
}

private fun Offset.leftNormal(): Offset {
    return Offset(-y, x)
}

private operator fun Offset.times(scale: Float): Offset {
    return Offset(x * scale, y * scale)
}

private infix fun Offset.dot(other: Offset): Float {
    return (x * other.x) + (y * other.y)
}
