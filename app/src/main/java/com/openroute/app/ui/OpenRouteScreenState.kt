package com.openroute.app.ui

import com.openroute.app.data.MapRenderState
import com.openroute.app.data.MapRouteOverlay
import com.openroute.app.data.MapFocusState
import com.openroute.app.data.Navigation3DRenderState
import com.openroute.app.data.RouteNavigationProgress
import com.openroute.app.data.RouteSource
import com.openroute.app.data.RouteTrack
import com.openroute.app.data.RouteTravelDirection
import com.openroute.app.data.effectiveDurationMillis
import kotlin.math.roundToInt

enum class OpenRouteScreenMode {
    Browse,
    Detail,
    Navigation3D,
}

data class HeaderState(
    val title: String = "OpenRoute",
    val subtitle: String = "GPX local, mapa OSM y grabación de rutas",
)

data class ActionBarState(
    val importLabel: String = "Import GPX",
    val trackLabel: String = "Start recording",
    val isImportEnabled: Boolean = true,
    val showsImportProgress: Boolean = false,
    val isTracking: Boolean = false,
)

data class BrowseActionState(
    val canHideSelected: Boolean = false,
    val canOpenDetail: Boolean = false,
    val hideLabel: String = "Ocultar seleccionada",
    val openDetailLabel: String = "Ver detalle",
)

data class SummaryState(
    val routesLabel: String = "Routes",
    val routesValue: String = "0",
    val liveTrackLabel: String = "Live track",
    val liveTrackValue: String = "off",
    val selectedLabel: String = "Selected",
    val selectedValue: String = "-",
)

enum class RouteBadge(val label: String) {
    Imported("GPX"),
    Recording("REC"),
}

data class RouteListItemState(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: RouteBadge,
    val isSelected: Boolean,
    val showsNewBadge: Boolean = false,
)

data class RouteListState(
    val emptyMessage: String = "Todavía no hay rutas. Importa un GPX o empieza a grabar.",
    val items: List<RouteListItemState> = emptyList(),
)

data class RouteDetailState(
    val routeId: String,
    val title: String,
    val subtitle: String,
    val distanceLabel: String,
    val durationLabel: String,
    val pointsLabel: String,
    val sourceLabel: String,
    val fileLabel: String? = null,
    val canRename: Boolean = false,
    val renameLabel: String = "Renombrar",
    val renameDialog: RouteRenameDialogState? = null,
    val backLabel: String = "Volver",
    val navigationState: RouteDetailNavigationState = RouteDetailNavigationState(),
)

data class RouteRenameDialogState(
    val title: String = "Renombrar ruta",
    val name: String,
    val confirmLabel: String = "Guardar",
    val dismissLabel: String = "Cancelar",
    val isConfirmEnabled: Boolean = true,
)

data class RouteDetailNavigationState(
    val isNavigating: Boolean = false,
    val actionLabel: String = "Iniciar navegación",
    val secondaryActionLabel: String? = null,
    val statusLabel: String = "Navegación inactiva",
    val progressLabel: String = "0%",
    val remainingLabel: String = "-",
    val etaLabel: String = "-",
    val distanceToRouteLabel: String = "-",
    val showsOffRouteAlert: Boolean = false,
)

data class Navigation3DState(
    val routeId: String,
    val title: String,
    val subtitle: String,
    val backLabel: String = "Volver al detalle",
    val stopLabel: String = "Detener navegación",
    val statusLabel: String,
    val progressLabel: String,
    val remainingLabel: String,
    val etaLabel: String,
    val distanceToRouteLabel: String,
    val showsOffRouteAlert: Boolean = false,
    val renderState: Navigation3DRenderState = Navigation3DRenderState(),
)

data class OpenRouteScreenState(
    val mode: OpenRouteScreenMode = OpenRouteScreenMode.Browse,
    val header: HeaderState = HeaderState(),
    val actionBar: ActionBarState = ActionBarState(),
    val isLoading: Boolean = true,
    val isSyncingDownloads: Boolean = false,
    val mapState: MapRenderState = MapRenderState(),
    val summary: SummaryState = SummaryState(),
    val browseAction: BrowseActionState = BrowseActionState(),
    val routeList: RouteListState = RouteListState(),
    val detailState: RouteDetailState? = null,
    val navigation3DState: Navigation3DState? = null,
    val snackbarMessage: String? = null,
)

enum class DownloadsAccessPresentation {
    Granted,
    NeedsPermission,
    NeedsAllFilesAccess,
}

data class DownloadsBannerState(
    val title: String,
    val message: String,
    val actionLabel: String? = null,
    val isLoading: Boolean = false,
)

internal fun OpenRouteUiState.toScreenState(): OpenRouteScreenState {
    val visibleRoutes = visibleRoutes
    val selectedRoute = selectedRoute
    val detailRoute = detailRoute
    val navigation3DRoute = navigation3DRoute
    val effectiveCurrentLocation = navigationState.currentLocation ?: currentLocation
    val effectiveLiveTrack = if (navigationState.isNavigating) navigationState.visitedPoints else liveTrack
    val mapRoutes = if (detailRoute != null) {
        visibleRoutes.filter { it.id == detailRoute.id }
    } else {
        visibleRoutes
    }
    val mapFocus = when {
        detailRoute != null -> MapFocusState(
            routeId = detailRoute.id,
            includeCurrentLocation = true,
        )

        effectiveLiveTrack.isNotEmpty() -> MapFocusState(
            includeCurrentLocation = true,
        )

        effectiveCurrentLocation != null -> MapFocusState(
            includeCurrentLocation = true,
            preferCurrentLocationZoom = true,
        )

        selectedRoute != null -> MapFocusState(routeId = selectedRoute.id)
        else -> MapFocusState()
    }
    val navigationProgress = navigation3DRoute?.let { route ->
        navigationState.progressFor(route.id)
    }
    val navigation3DState = navigation3DRoute
        ?.takeIf { navigationState.isActiveFor(it.id) }
        ?.toNavigation3DState(
            progress = navigationProgress,
            currentLocation = navigationState.currentLocation,
        )

    return OpenRouteScreenState(
        mode = when {
            navigation3DState != null -> OpenRouteScreenMode.Navigation3D
            detailRoute != null -> OpenRouteScreenMode.Detail
            else -> OpenRouteScreenMode.Browse
        },
        header = when {
            navigation3DState != null -> HeaderState(
                title = navigation3DState.title,
                subtitle = navigation3DState.subtitle,
            )

            detailRoute != null -> HeaderState(
                title = detailRoute.name,
                subtitle = detailRoute.metricsSubtitle(),
            )

            else -> HeaderState()
        },
        actionBar = ActionBarState(
            isImportEnabled = !isImporting,
            showsImportProgress = isImporting,
            isTracking = isTracking,
            trackLabel = if (isTracking) "Stop recording" else "Start recording",
        ),
        isLoading = isLoading,
        isSyncingDownloads = isSyncingDownloads,
        mapState = MapRenderState(
            routes = mapRoutes.map { route ->
                MapRouteOverlay(
                    id = route.id,
                    name = route.name,
                    color = if (route.id == selectedRouteId) "#0B6E4F" else route.source.defaultColor,
                    selected = route.id == selectedRouteId,
                    points = route.points,
                )
            },
            liveTrack = effectiveLiveTrack,
            currentLocation = effectiveCurrentLocation,
            focus = mapFocus,
        ),
        summary = SummaryState(
            routesValue = visibleRoutes.size.toString(),
            liveTrackValue = if (isTracking || navigationState.isNavigating) effectiveLiveTrack.size.toString() else "off",
            selectedValue = selectedRoute?.distanceMeters.toDistanceLabel(),
        ),
        browseAction = BrowseActionState(
            canHideSelected = selectedRoute != null,
            canOpenDetail = selectedRoute != null,
        ),
        routeList = RouteListState(
            items = visibleRoutes.map { route ->
                RouteListItemState(
                    id = route.id,
                    title = route.name,
                    subtitle = route.metricsSubtitle(),
                    badge = if (route.source == RouteSource.RECORDED) RouteBadge.Recording else RouteBadge.Imported,
                    isSelected = route.id == selectedRouteId,
                    showsNewBadge = route.source == RouteSource.IMPORTED_GPX && route.isNew,
                )
            },
        ),
        detailState = detailRoute?.toDetailState(
            isNavigating = navigationState.isActiveFor(detailRoute.id),
            progress = navigationState.progressFor(detailRoute.id),
            renameDraft = renameDraft.takeIf { renameRouteId == detailRoute.id },
        ),
        navigation3DState = navigation3DState,
        snackbarMessage = message,
    )
}

internal fun resolveDownloadsBannerState(
    isSyncingDownloads: Boolean,
    accessPresentation: DownloadsAccessPresentation,
): DownloadsBannerState? {
    return when {
        isSyncingDownloads -> DownloadsBannerState(
            title = "Descargas",
            message = "Buscando archivos GPX nuevos en Descargas...",
            isLoading = true,
        )

        accessPresentation == DownloadsAccessPresentation.NeedsAllFilesAccess -> DownloadsBannerState(
            title = "Autoimportar Descargas",
            message = "Permite acceso a Descargas para importar GPX automaticamente al abrir la app.",
            actionLabel = "Permitir acceso",
        )

        accessPresentation == DownloadsAccessPresentation.NeedsPermission -> DownloadsBannerState(
            title = "Autoimportar Descargas",
            message = "Permite lectura de almacenamiento para importar GPX descargados al abrir la app.",
            actionLabel = "Dar permiso",
        )

        else -> null
    }
}

private fun RouteTrack.toDetailState(
    isNavigating: Boolean,
    progress: RouteNavigationProgress?,
    renameDraft: String?,
): RouteDetailState {
    return RouteDetailState(
        routeId = id,
        title = name,
        subtitle = if (source == RouteSource.RECORDED) "Ruta grabada localmente" else "Ruta importada desde GPX",
        distanceLabel = distanceMeters.toDistanceLabel(),
        durationLabel = effectiveDurationMillis.toDurationLabel(),
        pointsLabel = points.size.toString(),
        sourceLabel = if (source == RouteSource.RECORDED) "REC" else "GPX",
        fileLabel = originalFileName,
        canRename = source == RouteSource.RECORDED,
        renameDialog = renameDraft?.takeIf { source == RouteSource.RECORDED }?.let { draft ->
            RouteRenameDialogState(
                name = draft,
                isConfirmEnabled = draft.trim().isNotEmpty(),
            )
        },
        navigationState = RouteDetailNavigationState(
            isNavigating = isNavigating,
            actionLabel = if (isNavigating) "Abrir guía 3D" else "Iniciar navegación",
            secondaryActionLabel = if (isNavigating) "Detener navegación" else null,
            statusLabel = when {
                isNavigating && progress == null -> "Esperando posición..."
                progress == null -> "Navegación inactiva"
                progress.distanceToRouteMeters >= OFF_ROUTE_ALERT_DISTANCE_METERS ->
                    "Fuera de ruta (${progress.distanceToRouteMeters.toDistanceLabel()})"
                else -> "Siguiendo ruta"
            },
            progressLabel = progress?.completionRatio?.toPercentLabel() ?: "0%",
            remainingLabel = progress?.remainingDistanceMeters.toDistanceLabel(),
            etaLabel = progress?.estimatedRemainingSeconds.toEtaLabel(),
            distanceToRouteLabel = progress?.distanceToRouteMeters.toDistanceLabel(),
            showsOffRouteAlert = (progress?.distanceToRouteMeters ?: 0.0) >= OFF_ROUTE_ALERT_DISTANCE_METERS,
        ),
    )
}

private fun RouteTrack.toNavigation3DState(
    progress: RouteNavigationProgress?,
    currentLocation: com.openroute.app.data.LatLngPoint?,
): Navigation3DState {
    val nearestIndex = progress?.nearestRoutePointIndex ?: 0
    val segmentStartIndex = progress?.nearestSegmentStartIndex ?: nearestIndex.coerceIn(0, (points.lastIndex - 1).coerceAtLeast(0))
    val travelDirection = progress?.travelDirection ?: RouteTravelDirection.Forward
    return Navigation3DState(
        routeId = id,
        title = name,
        subtitle = "Guía 3D aproximada",
        statusLabel = when {
            progress == null -> "Esperando posición..."
            progress.distanceToRouteMeters >= OFF_ROUTE_ALERT_DISTANCE_METERS ->
                "Fuera de ruta (${progress.distanceToRouteMeters.toDistanceLabel()})"
            else -> "Siguiendo ruta"
        },
        progressLabel = progress?.completionRatio?.toPercentLabel() ?: "0%",
        remainingLabel = progress?.remainingDistanceMeters.toDistanceLabel(),
        etaLabel = progress?.estimatedRemainingSeconds.toEtaLabel(),
        distanceToRouteLabel = progress?.distanceToRouteMeters.toDistanceLabel(),
        showsOffRouteAlert = (progress?.distanceToRouteMeters ?: 0.0) >= OFF_ROUTE_ALERT_DISTANCE_METERS,
        renderState = Navigation3DRenderState(
            routePoints = routeWindowAround(
                segmentStartIndex = segmentStartIndex,
                anchorLocation = currentLocation,
                travelDirection = travelDirection,
            ),
            visitedPoints = emptyList(),
            currentLocation = currentLocation,
            headingDegrees = progress?.headingDegrees ?: headingDegreesAround(
                segmentStartIndex = segmentStartIndex,
                travelDirection = travelDirection,
            ),
            isOffRoute = (progress?.distanceToRouteMeters ?: 0.0) >= OFF_ROUTE_ALERT_DISTANCE_METERS,
        ),
    )
}

private fun com.openroute.app.location.NavigationState.progressFor(routeId: String): RouteNavigationProgress? {
    return takeIf { isNavigating && route?.id == routeId }?.progress
}

private fun com.openroute.app.location.NavigationState.isActiveFor(routeId: String): Boolean {
    return isNavigating && route?.id == routeId
}

private val RouteSource.defaultColor: String
    get() = when (this) {
        RouteSource.IMPORTED_GPX -> "#1D3557"
        RouteSource.RECORDED -> "#D95D39"
    }

private fun RouteTrack.metricsSubtitle(): String {
    return buildList {
        add(distanceMeters.toDistanceLabel())
        effectiveDurationMillis?.let { add(it.toDurationLabel()) }
        add("${points.size} puntos")
    }.joinToString(" · ")
}

private fun RouteTrack.routeWindowAround(
    segmentStartIndex: Int,
    anchorLocation: com.openroute.app.data.LatLngPoint?,
    travelDirection: RouteTravelDirection,
): List<com.openroute.app.data.LatLngPoint> {
    if (points.isEmpty()) {
        return emptyList()
    }

    val wrapAround = isClosedLoop()
    val step = when (travelDirection) {
        RouteTravelDirection.Forward -> 1
        RouteTravelDirection.Backward -> -1
    }
    val previousIndex = when (travelDirection) {
        RouteTravelDirection.Forward -> segmentStartIndex
        RouteTravelDirection.Backward -> segmentStartIndex + 1
    }
    val nextIndex = when (travelDirection) {
        RouteTravelDirection.Forward -> segmentStartIndex + 1
        RouteTravelDirection.Backward -> segmentStartIndex
    }
    val behindPoints = collectDirectionalWindow(
        startIndex = previousIndex - ((NAVIGATION_3D_POINTS_BEHIND - 1) * step),
        step = step,
        size = NAVIGATION_3D_POINTS_BEHIND,
        wrapAround = wrapAround,
    ).simplifyFor3D()
    val aheadPoints = collectDirectionalWindow(
        startIndex = nextIndex,
        step = step,
        size = NAVIGATION_3D_POINTS_AHEAD,
        wrapAround = wrapAround,
    ).simplifyFor3D()

    return (behindPoints + listOfNotNull(anchorLocation) + aheadPoints).removeNearDuplicates()
}

private fun RouteTrack.collectDirectionalWindow(
    startIndex: Int,
    step: Int,
    size: Int,
    wrapAround: Boolean,
): List<com.openroute.app.data.LatLngPoint> {
    if (points.isEmpty()) {
        return emptyList()
    }

    val windowSize = if (wrapAround) {
        size.coerceAtMost(points.size)
    } else {
        size
    }

    return buildList(windowSize) {
        repeat(windowSize) { offset ->
            val rawIndex = startIndex + (offset * step)
            val index = if (wrapAround) {
                rawIndex.floorMod(points.size)
            } else {
                rawIndex
            }
            points.getOrNull(index)?.let(::add)
        }
    }
}

private fun List<com.openroute.app.data.LatLngPoint>.simplifyFor3D(): List<com.openroute.app.data.LatLngPoint> {
    if (size <= 3) {
        return this
    }

    val simplified = douglasPeucker(
        points = this,
        toleranceMeters = NAVIGATION_3D_SIMPLIFICATION_TOLERANCE_METERS,
    )
    return if (simplified.size >= 2) simplified else this
}

private fun douglasPeucker(
    points: List<com.openroute.app.data.LatLngPoint>,
    toleranceMeters: Double,
): List<com.openroute.app.data.LatLngPoint> {
    if (points.size <= 2) {
        return points
    }

    var maxDistance = 0.0
    var splitIndex = -1
    for (index in 1 until points.lastIndex) {
        val distance = perpendicularDistanceMeters(
            point = points[index],
            segmentStart = points.first(),
            segmentEnd = points.last(),
        )
        if (distance > maxDistance) {
            maxDistance = distance
            splitIndex = index
        }
    }

    if (maxDistance <= toleranceMeters || splitIndex == -1) {
        return listOf(points.first(), points.last())
    }

    val left = douglasPeucker(points.subList(0, splitIndex + 1), toleranceMeters)
    val right = douglasPeucker(points.subList(splitIndex, points.size), toleranceMeters)
    return left.dropLast(1) + right
}

private fun perpendicularDistanceMeters(
    point: com.openroute.app.data.LatLngPoint,
    segmentStart: com.openroute.app.data.LatLngPoint,
    segmentEnd: com.openroute.app.data.LatLngPoint,
): Double {
    val referenceLatitude = Math.toRadians(
        (point.latitude + segmentStart.latitude + segmentEnd.latitude) / 3.0,
    )
    val metersPerDegreeLatitude = 111_320.0
    val metersPerDegreeLongitude = kotlin.math.cos(referenceLatitude) * metersPerDegreeLatitude

    val startX = segmentStart.longitude * metersPerDegreeLongitude
    val startY = segmentStart.latitude * metersPerDegreeLatitude
    val endX = segmentEnd.longitude * metersPerDegreeLongitude
    val endY = segmentEnd.latitude * metersPerDegreeLatitude
    val pointX = point.longitude * metersPerDegreeLongitude
    val pointY = point.latitude * metersPerDegreeLatitude

    val segmentDeltaX = endX - startX
    val segmentDeltaY = endY - startY
    val segmentLengthSquared = (segmentDeltaX * segmentDeltaX) + (segmentDeltaY * segmentDeltaY)
    if (segmentLengthSquared <= 1e-6) {
        return kotlin.math.hypot(pointX - startX, pointY - startY)
    }

    val t = (((pointX - startX) * segmentDeltaX) + ((pointY - startY) * segmentDeltaY)) /
        segmentLengthSquared
    val clampedT = t.coerceIn(0.0, 1.0)
    val projectionX = startX + (segmentDeltaX * clampedT)
    val projectionY = startY + (segmentDeltaY * clampedT)

    return kotlin.math.hypot(pointX - projectionX, pointY - projectionY)
}

private fun RouteTrack.headingDegreesAround(
    segmentStartIndex: Int,
    travelDirection: RouteTravelDirection,
): Double {
    if (points.size < 2) {
        return 0.0
    }

    val forwardHeading = headingDegreesForSegment(segmentStartIndex) ?: return 0.0
    return when (travelDirection) {
        RouteTravelDirection.Forward -> forwardHeading
        RouteTravelDirection.Backward -> (forwardHeading + 180.0) % 360.0
    }
}

private fun RouteTrack.normalizeRouteIndex(
    index: Int,
    wrapAround: Boolean,
): Int? {
    return if (wrapAround) {
        index.floorMod(points.size)
    } else {
        index.takeIf { it in points.indices }
    }
}

private fun RouteTrack.headingDegreesForSegment(segmentStartIndex: Int): Double? {
    if (points.size < 2) {
        return null
    }

    val wrapAround = isClosedLoop()
    val fromIndex = normalizeRouteIndex(segmentStartIndex, wrapAround) ?: return null
    val toIndex = normalizeRouteIndex(segmentStartIndex + 1, wrapAround) ?: return null
    if (fromIndex == toIndex) {
        return null
    }

    val from = points[fromIndex]
    val to = points[toIndex]
    val latitudeDelta = to.latitude - from.latitude
    val longitudeDelta = to.longitude - from.longitude
    return Math.toDegrees(kotlin.math.atan2(longitudeDelta, latitudeDelta))
}

private fun RouteTrack.isClosedLoop(): Boolean {
    return points.size >= 4 &&
        points.first().distanceTo(points.last()) <= CLOSED_LOOP_ROUTE_THRESHOLD_METERS
}

private fun List<com.openroute.app.data.LatLngPoint>.removeNearDuplicates(): List<com.openroute.app.data.LatLngPoint> {
    if (size < 2) {
        return this
    }

    return buildList(size) {
        for (point in this@removeNearDuplicates) {
            if (lastOrNull()?.distanceTo(point) ?: Double.MAX_VALUE > MIN_RENDER_POINT_DISTANCE_METERS) {
                add(point)
            }
        }
    }
}

private fun com.openroute.app.data.LatLngPoint.distanceTo(
    other: com.openroute.app.data.LatLngPoint,
): Double {
    val earthRadiusMeters = 6_371_000.0
    val latitudeDeltaRadians = Math.toRadians(other.latitude - latitude)
    val longitudeDeltaRadians = Math.toRadians(other.longitude - longitude)
    val startLatitudeRadians = Math.toRadians(latitude)
    val endLatitudeRadians = Math.toRadians(other.latitude)
    val a = kotlin.math.sin(latitudeDeltaRadians / 2).let { it * it } +
        kotlin.math.cos(startLatitudeRadians) *
        kotlin.math.cos(endLatitudeRadians) *
        kotlin.math.sin(longitudeDeltaRadians / 2).let { it * it }

    return 2 * earthRadiusMeters * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
}

private fun Int.floorMod(modulus: Int): Int {
    return ((this % modulus) + modulus) % modulus
}

internal fun Double?.toDistanceLabel(): String {
    val distance = this ?: return "-"

    return if (distance >= 1000) {
        String.format("%.1f km", distance / 1000.0)
    } else {
        String.format("%.0f m", distance)
    }
}

internal fun Long?.toDurationLabel(): String {
    val durationMillis = this ?: return "-"
    val totalSeconds = (durationMillis / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0L -> if (minutes > 0L) "${hours}h ${minutes}m" else "${hours}h"
        minutes > 0L -> "${minutes} min"
        else -> "${seconds}s"
    }
}

private fun Double.toPercentLabel(): String {
    return "${(this * 100).roundToInt().coerceIn(0, 100)}%"
}

private fun Long?.toEtaLabel(): String {
    val remainingSeconds = this ?: return "-"
    val totalMinutes = remainingSeconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return if (hours > 0) {
        "${hours}h ${minutes}m"
    } else {
        "${minutes} min"
    }
}

private const val OFF_ROUTE_ALERT_DISTANCE_METERS = 50.0
private const val NAVIGATION_3D_POINTS_BEHIND = 8
private const val NAVIGATION_3D_POINTS_AHEAD = 28
private const val CLOSED_LOOP_ROUTE_THRESHOLD_METERS = 35.0
private const val NAVIGATION_3D_SIMPLIFICATION_TOLERANCE_METERS = 4.0
private const val MIN_RENDER_POINT_DISTANCE_METERS = 1.0
