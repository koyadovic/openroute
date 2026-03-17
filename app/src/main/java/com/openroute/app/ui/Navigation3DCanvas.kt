package com.openroute.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import com.openroute.app.data.LatLngPoint
import com.openroute.app.data.Navigation3DRenderState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun Navigation3DCanvas(
    state: Navigation3DRenderState,
    modifier: Modifier = Modifier,
) {
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

    LaunchedEffect(
        state.currentLocation?.latitude,
        state.currentLocation?.longitude,
        state.headingDegrees,
    ) {
        val targetLocation = state.currentLocation
        if (targetLocation == null) {
            isAnimationPrimed = false
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
        val animationDurationMillis = transitionDurationMillis(
            distanceMeters = distanceMeters,
            headingDeltaDegrees = headingDelta,
        )

        if (!isAnimationPrimed || distanceMeters > MAX_ANIMATED_STEP_DISTANCE_METERS) {
            animatedLatitude.snapTo(targetLocation.latitude.toFloat())
            animatedLongitude.snapTo(targetLocation.longitude.toFloat())
            animatedHeading.snapTo(state.headingDegrees.toFloat())
            isAnimationPrimed = true
            return@LaunchedEffect
        }

        coroutineScope {
            launch {
                animatedLatitude.animateTo(
                    targetValue = targetLocation.latitude.toFloat(),
                    animationSpec = tween(
                        durationMillis = animationDurationMillis,
                        easing = LinearEasing,
                    ),
                )
            }
            launch {
                animatedLongitude.animateTo(
                    targetValue = targetLocation.longitude.toFloat(),
                    animationSpec = tween(
                        durationMillis = animationDurationMillis,
                        easing = LinearEasing,
                    ),
                )
            }
            launch {
                animatedHeading.animateTo(
                    targetValue = (animatedHeading.value + headingDelta.toFloat()),
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
    val message = remember(state) {
        when {
            state.currentLocation == null -> "Esperando posición..."
            state.routePoints.size < 2 -> "No hay suficiente ruta para guiado 3D."
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
                color = if (state.isOffRoute) Color(0x29FFD2D2) else Color(0x14FFFFFF),
            )

            val currentLocation = animatedCurrentLocation
            if (message == null && currentLocation != null) {
                val projectedRoute = state.routePoints.mapNotNull {
                    it.projectToScene(
                        origin = currentLocation,
                        headingDegrees = animatedHeadingDegrees,
                        viewport = size,
                    )
                }
                val currentProjection = projectWorldPoint(
                    worldPoint = WorldPoint(0.0, 0.0, 0.0),
                    viewport = size,
                )

                if (currentProjection != null) {
                    val anchor = Offset(size.width / 2f, size.height * 0.82f)
                    val translation = anchor - currentProjection
                    val translatedRoute = projectedRoute.map { it + translation }

                    drawBand(
                        points = translatedRoute,
                        color = if (state.isOffRoute) Color(0xFFFFB3B3) else Color(0xFF8EE8C6),
                        nearWidth = 24f,
                        farWidth = 4f,
                        viewport = size,
                    )
                    drawCenterLine(
                        points = translatedRoute,
                        color = if (state.isOffRoute) Color(0xFFFFE1E1) else Color(0xFFD6FFF0),
                        strokeWidth = 2.5f,
                    )
                    drawNavigationTriangle(
                        center = anchor,
                        fillColor = Color(0xFFE63946),
                        strokeColor = Color(0xFF102027),
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
    val horizonY = size.height * 0.36f
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
    if (points.size < 2) {
        return
    }

    points.zipWithNext().forEach { (start, end) ->
        val direction = end - start
        val length = direction.length()
        if (length <= 0.5f) {
            return@forEach
        }

        val normal = Offset(-direction.y / length, direction.x / length)
        val startHalfWidth = perspectiveWidthFor(
            point = start,
            viewport = viewport,
            nearWidth = nearWidth,
            farWidth = farWidth,
        ) / 2f
        val endHalfWidth = perspectiveWidthFor(
            point = end,
            viewport = viewport,
            nearWidth = nearWidth,
            farWidth = farWidth,
        ) / 2f

        val path = Path().apply {
            moveTo(start.x + normal.x * startHalfWidth, start.y + normal.y * startHalfWidth)
            lineTo(end.x + normal.x * endHalfWidth, end.y + normal.y * endHalfWidth)
            lineTo(end.x - normal.x * endHalfWidth, end.y - normal.y * endHalfWidth)
            lineTo(start.x - normal.x * startHalfWidth, start.y - normal.y * startHalfWidth)
            close()
        }
        drawPath(path = path, color = color)
    }

}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenterLine(
    points: List<Offset>,
    color: Color,
    strokeWidth: Float,
) {
    if (points.size < 2) {
        return
    }

    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { point ->
            lineTo(point.x, point.y)
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt, join = StrokeJoin.Miter),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNavigationTriangle(
    center: Offset,
    fillColor: Color,
    strokeColor: Color,
) {
    val shadowPath = Path().apply {
        moveTo(center.x, center.y - 34f)
        lineTo(center.x + 21f, center.y + 18f)
        lineTo(center.x, center.y + 10f)
        lineTo(center.x - 21f, center.y + 18f)
        close()
    }
    drawPath(
        path = shadowPath,
        color = Color.Black.copy(alpha = 0.24f),
    )

    val trianglePath = Path().apply {
        moveTo(center.x, center.y - 30f)
        lineTo(center.x + 18f, center.y + 16f)
        lineTo(center.x, center.y + 7f)
        lineTo(center.x - 18f, center.y + 16f)
        close()
    }
    drawPath(path = trianglePath, color = fillColor)
    drawPath(
        path = trianglePath,
        color = strokeColor.copy(alpha = 0.92f),
        style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun perspectiveWidthFor(
    point: Offset,
    viewport: Size,
    nearWidth: Float,
    farWidth: Float,
): Float {
    val horizonY = viewport.height * 0.36f
    val normalizedDepth = ((point.y - horizonY) / (viewport.height - horizonY))
        .coerceIn(0f, 1f)
    return farWidth + ((nearWidth - farWidth) * normalizedDepth)
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

private fun Offset.length(): Float {
    return sqrt((x * x) + (y * y))
}

private fun transitionDurationMillis(
    distanceMeters: Double,
    headingDeltaDegrees: Double,
): Int {
    val distanceDuration = (220 + (distanceMeters * 10)).roundToInt()
        .coerceIn(220, 1_400)
    val headingDuration = (180 + (headingDeltaDegrees * 4)).roundToInt()
        .coerceIn(180, 900)
    return max(distanceDuration, headingDuration)
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

private fun projectWorldPoint(worldPoint: WorldPoint, viewport: Size): Offset? {
    val camera = WorldPoint(0.0, 14.0, -18.0)
    val target = WorldPoint(0.0, 0.0, 38.0)
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

    val focal = viewport.height * 0.92
    return Offset(
        x = (viewport.width / 2f) + ((cameraX * focal) / cameraZ).toFloat(),
        y = (viewport.height * 0.68f) - ((cameraY * focal) / cameraZ).toFloat(),
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
