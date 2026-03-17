package com.openroute.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.openroute.app.data.LatLngPoint
import com.openroute.app.data.Navigation3DRenderState
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

@Composable
fun Navigation3DCanvas(
    state: Navigation3DRenderState,
    modifier: Modifier = Modifier,
) {
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

            val currentLocation = state.currentLocation
            if (message == null && currentLocation != null) {
                val projectedRoute = state.routePoints.mapNotNull {
                    it.projectToScene(
                        origin = currentLocation,
                        headingDegrees = state.headingDegrees,
                        viewport = size,
                    )
                }
                val projectedVisited = state.visitedPoints.mapNotNull {
                    it.projectToScene(
                        origin = currentLocation,
                        headingDegrees = state.headingDegrees,
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
                    val translatedVisited = projectedVisited.map { it + translation }

                    drawPolyline(
                        points = translatedRoute,
                        color = if (state.isOffRoute) Color(0xFFFFB3B3) else Color(0xFF8EE8C6),
                        strokeWidth = 6f,
                    )
                    drawPolyline(
                        points = translatedVisited,
                        color = Color(0xFFF4A261),
                        strokeWidth = 5f,
                    )
                    drawCircle(
                        color = if (state.isOffRoute) Color(0xFFFF6B6B) else Color(0xFFFFD166),
                        radius = 8f,
                        center = anchor,
                    )
                    drawCircle(
                        color = Color(0xFF102027),
                        radius = 8f,
                        center = anchor,
                        style = Stroke(width = 3f),
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolyline(
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
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
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
        angleRadians = Math.toRadians(-headingDegrees),
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

private fun projectWorldPoint(worldPoint: WorldPoint, viewport: Size): Offset? {
    val camera = WorldPoint(0.0, 14.0, -18.0)
    val target = WorldPoint(0.0, 0.0, 38.0)
    val forward = normalize(target - camera)
    val worldUp = WorldPoint(0.0, 1.0, 0.0)
    val right = normalize(cross(forward, worldUp))
    val up = cross(right, forward)
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
