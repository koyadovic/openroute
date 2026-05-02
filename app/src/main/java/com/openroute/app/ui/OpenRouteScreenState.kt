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
import com.openroute.app.location.BreadcrumbState
import kotlin.math.roundToInt

enum class OpenRouteScreenMode {
    Routes,
    Recording,
    Breadcrumbs,
    Detail,
    Navigation3D,
}

enum class OpenRouteMainSection {
    Routes,
    Recording,
    Breadcrumbs,
}

data class HeaderState(
    val title: String = "OpenRoute",
    val subtitle: String = "GPX local, mapa OSM y grabación de rutas",
)

data class ActionBarState(
    val importLabel: String = "Import GPX",
    val trackLabel: String = "Start recording",
    val breadcrumbLabel: String = "Migas de pan",
    val isImportEnabled: Boolean = true,
    val showsImportProgress: Boolean = false,
    val isTracking: Boolean = false,
    val isBreadcrumbing: Boolean = false,
)

data class SummaryState(
    val routesLabel: String = "Routes",
    val routesValue: String = "0",
    val liveTrackLabel: String = "Live track",
    val liveTrackValue: String = "off",
    val selectedLabel: String = "Selected",
    val selectedValue: String = "-",
    val activeDurationLabel: String? = null,
    val activeDurationValue: String? = null,
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

data class HiddenRouteListItemState(
    val id: String,
    val title: String,
    val subtitle: String,
    val badge: RouteBadge,
)

data class HiddenRouteDeleteDialogState(
    val title: String,
    val message: String,
    val confirmLabel: String = "Eliminar",
    val dismissLabel: String = "Cancelar",
)

data class HiddenRoutesState(
    val countLabel: String,
    val toggleLabel: String,
    val isExpanded: Boolean,
    val items: List<HiddenRouteListItemState> = emptyList(),
)

data class RouteListState(
    val emptyMessage: String = "Todavía no hay rutas. Importa un GPX o empieza a grabar.",
    val items: List<RouteListItemState> = emptyList(),
    val hiddenRoutes: HiddenRoutesState? = null,
    val deleteDialog: HiddenRouteDeleteDialogState? = null,
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
    val canHide: Boolean = true,
    val canDelete: Boolean = true,
    val hideLabel: String = "Ocultar ruta",
    val deleteLabel: String = "Eliminar ruta",
    val canRename: Boolean = false,
    val renameLabel: String = "Renombrar",
    val renameDialog: RouteRenameDialogState? = null,
    val deleteDialog: HiddenRouteDeleteDialogState? = null,
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
    val isBreadcrumb: Boolean = false,
    val activeDurationLabel: String? = null,
    val activeDurationValue: String? = null,
)

data class OpenRouteScreenState(
    val mode: OpenRouteScreenMode = OpenRouteScreenMode.Routes,
    val header: HeaderState = HeaderState(),
    val actionBar: ActionBarState = ActionBarState(),
    val isLoading: Boolean = true,
    val isSyncingDownloads: Boolean = false,
    val mapState: MapRenderState = MapRenderState(),
    val summary: SummaryState = SummaryState(),
    val routeList: RouteListState = RouteListState(),
    val detailState: RouteDetailState? = null,
    val navigation3DState: Navigation3DState? = null,
    val drawerItems: List<DrawerItemState> = emptyList(),
    val snackbarMessage: String? = null,
)

data class DrawerItemState(
    val section: OpenRouteMainSection,
    val title: String,
    val subtitle: String,
    val isSelected: Boolean,
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
    val hiddenRoutes = hiddenRoutes
    val selectedRoute = selectedRoute
    val detailRoute = detailRoute
    val navigation3DRoute = navigation3DRoute
    val effectiveCurrentLocation = navigationState.currentLocation ?: breadcrumbState.currentLocation ?: currentLocation
    val effectiveLiveTrack = when {
        navigationState.isNavigating -> navigationState.visitedPoints
        mainSection == OpenRouteMainSection.Breadcrumbs && breadcrumbState.isActive -> breadcrumbState.points
        mainSection == OpenRouteMainSection.Recording && isTracking -> liveTrack
        else -> emptyList()
    }
    val mapRoutes = when {
        detailRoute != null -> listOf(detailRoute)
        mainSection == OpenRouteMainSection.Routes -> visibleRoutes
        else -> emptyList()
    }
    val mapFocus = when {
        detailRoute != null -> MapFocusState(
            routeId = detailRoute.id,
            includeCurrentLocation = true,
        )

        mainSection == OpenRouteMainSection.Routes && selectedRoute != null -> MapFocusState(
            routeId = selectedRoute.id,
            includeCurrentLocation = effectiveCurrentLocation != null,
        )

        effectiveLiveTrack.isNotEmpty() -> MapFocusState(
            includeCurrentLocation = true,
        )

        effectiveCurrentLocation != null -> MapFocusState(
            includeCurrentLocation = true,
            preferCurrentLocationZoom = true,
        )

        else -> MapFocusState()
    }
    val navigationProgress = navigation3DRoute?.let { route ->
        navigationState.progressFor(route.id)
    }
    val routeNavigation3DState = navigation3DRoute
        ?.takeIf { navigationState.isActiveFor(it.id) }
        ?.toNavigation3DState(
            progress = navigationProgress,
            currentLocation = navigationState.currentLocation,
        )
    val activeDurationValue = activeDurationMillis()?.toActiveDurationLabel()
    val activeDurationLabel = activeDurationLabel().takeIf { activeDurationValue != null }
    val navigation3DState = breadcrumbState.toNavigation3DState(
        activeDurationLabel = activeDurationLabel.takeIf { breadcrumbState.isActive },
        activeDurationValue = activeDurationValue.takeIf { breadcrumbState.isActive },
    ) ?: routeNavigation3DState

    return OpenRouteScreenState(
        mode = when {
            navigation3DState != null -> OpenRouteScreenMode.Navigation3D
            detailRoute != null -> OpenRouteScreenMode.Detail
            mainSection == OpenRouteMainSection.Recording -> OpenRouteScreenMode.Recording
            mainSection == OpenRouteMainSection.Breadcrumbs -> OpenRouteScreenMode.Breadcrumbs
            else -> OpenRouteScreenMode.Routes
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

            mainSection == OpenRouteMainSection.Routes -> HeaderState(
                title = "Rutas",
                subtitle = "${visibleRoutes.size} visibles · ${hiddenRoutes.size} ocultas",
            )

            mainSection == OpenRouteMainSection.Recording -> HeaderState(
                title = "Grabar ruta",
                subtitle = if (isTracking) "Grabando recorrido local" else "Registra una ruta nueva",
            )

            mainSection == OpenRouteMainSection.Breadcrumbs -> HeaderState(
                title = "Migas de pan",
                subtitle = when {
                    breadcrumbState.isReturning -> "Volviendo al inicio"
                    breadcrumbState.isActive -> "Sembrando rastro"
                    else -> "Deja un rastro para volver"
                },
            )

            else -> HeaderState()
        },
        actionBar = ActionBarState(
            isImportEnabled = !isImporting,
            showsImportProgress = isImporting,
            isTracking = isTracking,
            trackLabel = if (isTracking) "Stop recording" else "Start recording",
            isBreadcrumbing = breadcrumbState.isActive,
            breadcrumbLabel = when {
                breadcrumbState.isReturning -> "Volviendo"
                breadcrumbState.isActive -> "Parar migas"
                else -> "Migas de pan"
            },
        ),
        isLoading = isLoading,
        isSyncingDownloads = isSyncingDownloads,
        mapState = MapRenderState(
            routes = mapRoutes.map { route ->
                MapRouteOverlay(
                    id = route.id,
                    name = route.name,
                    color = if (route.id == selectedRouteId) "#073B67" else route.source.defaultColor,
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
            liveTrackValue = if (navigationState.isNavigating || effectiveLiveTrack.isNotEmpty()) {
                effectiveLiveTrack.size.toString()
            } else {
                "off"
            },
            selectedValue = selectedRoute?.distanceMeters.toDistanceLabel(),
            activeDurationLabel = activeDurationLabel,
            activeDurationValue = activeDurationValue,
        ),
        routeList = RouteListState(
            emptyMessage = if (hiddenRoutes.isNotEmpty()) {
                "No hay rutas visibles. Puedes revisar las ${hiddenRoutes.size} ocultas."
            } else {
                "Todavía no hay rutas. Importa un GPX o empieza a grabar."
            },
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
            hiddenRoutes = hiddenRoutes.takeIf { it.isNotEmpty() }?.let { routes ->
                HiddenRoutesState(
                    countLabel = "${routes.size}",
                    toggleLabel = if (showsHiddenRoutes) {
                        "Ocultar ocultas"
                    } else {
                        "Mostrar ocultas"
                    },
                    isExpanded = showsHiddenRoutes,
                    items = if (showsHiddenRoutes) {
                        routes.map { route ->
                            HiddenRouteListItemState(
                                id = route.id,
                                title = route.name,
                                subtitle = route.metricsSubtitle(),
                                badge = if (route.source == RouteSource.RECORDED) {
                                    RouteBadge.Recording
                                } else {
                                    RouteBadge.Imported
                                },
                            )
                        }
                    } else {
                        emptyList()
                    },
                )
            },
            deleteDialog = routePendingDeletion?.let { route ->
                HiddenRouteDeleteDialogState(
                    title = if (route.isHidden) "Eliminar ruta oculta" else "Eliminar ruta",
                    message = "Se borrará \"${route.name}\" de OpenRoute.",
                )
            },
        ),
        detailState = detailRoute?.toDetailState(
            isNavigating = navigationState.isActiveFor(detailRoute.id),
            progress = navigationState.progressFor(detailRoute.id),
            renameDraft = renameDraft.takeIf { renameRouteId == detailRoute.id },
            routePendingDeletion = routePendingDeletion,
        ),
        navigation3DState = navigation3DState,
        drawerItems = mainSection.toDrawerItems(),
        snackbarMessage = message,
    )
}

private fun OpenRouteMainSection.toDrawerItems(): List<DrawerItemState> {
    return listOf(
        DrawerItemState(
            section = OpenRouteMainSection.Routes,
            title = "Rutas",
            subtitle = "Visualiza, importa y gestiona rutas",
            isSelected = this == OpenRouteMainSection.Routes,
        ),
        DrawerItemState(
            section = OpenRouteMainSection.Recording,
            title = "Grabar ruta",
            subtitle = "Registra una nueva salida",
            isSelected = this == OpenRouteMainSection.Recording,
        ),
        DrawerItemState(
            section = OpenRouteMainSection.Breadcrumbs,
            title = "Migas de pan",
            subtitle = "Deja rastro para volver",
            isSelected = this == OpenRouteMainSection.Breadcrumbs,
        ),
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
    routePendingDeletion: RouteTrack?,
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
        canHide = !isHidden,
        canDelete = true,
        canRename = source == RouteSource.RECORDED,
        renameDialog = renameDraft?.takeIf { source == RouteSource.RECORDED }?.let { draft ->
            RouteRenameDialogState(
                name = draft,
                isConfirmEnabled = draft.trim().isNotEmpty(),
            )
        },
        deleteDialog = routePendingDeletion?.takeIf { it.id == id }?.let { route ->
            HiddenRouteDeleteDialogState(
                title = if (route.isHidden) "Eliminar ruta oculta" else "Eliminar ruta",
                message = "Se borrará \"${route.name}\" de OpenRoute.",
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

private fun BreadcrumbState.toNavigation3DState(
    activeDurationLabel: String?,
    activeDurationValue: String?,
): Navigation3DState? {
    if (!isReturning) {
        return null
    }

    val breadcrumbRoute = route ?: return null
    val current = progress?.displayLocation ?: currentLocation
    return breadcrumbRoute.toNavigation3DState(
        progress = progress,
        currentLocation = current,
    ).copy(
        title = "Migas de pan",
        subtitle = "Volviendo al inicio",
        backLabel = "Detener migas",
        stopLabel = "Detener migas",
        statusLabel = when {
            progress == null -> "Esperando posición..."
            progress.distanceToRouteMeters >= OFF_ROUTE_ALERT_DISTANCE_METERS ->
                "Fuera del rastro (${progress.distanceToRouteMeters.toDistanceLabel()})"
            else -> "Volviendo por tus migas"
        },
        isBreadcrumb = true,
        activeDurationLabel = activeDurationLabel,
        activeDurationValue = activeDurationValue,
    )
}

private fun OpenRouteUiState.activeDurationLabel(): String? {
    return when {
        isTracking -> "Tiempo grabando"
        breadcrumbState.isActive -> "Tiempo migas"
        else -> null
    }
}

private fun OpenRouteUiState.activeDurationMillis(): Long? {
    val startedAtMillis = when {
        isTracking -> trackingStartedAtMillis
        breadcrumbState.isActive -> breadcrumbState.startedAtMillis.takeIf { it > 0L }
        else -> null
    } ?: return null

    return (clockNowMillis - startedAtMillis).coerceAtLeast(0L)
}

private fun com.openroute.app.location.NavigationState.progressFor(routeId: String): RouteNavigationProgress? {
    return takeIf { isNavigating && route?.id == routeId }?.progress
}

private fun com.openroute.app.location.NavigationState.isActiveFor(routeId: String): Boolean {
    return isNavigating && route?.id == routeId
}

private val RouteSource.defaultColor: String
    get() = when (this) {
        RouteSource.IMPORTED_GPX -> "#073B67"
        RouteSource.RECORDED -> "#697380"
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
    val distantBehindPoints = collectDirectionalWindow(
        startIndex = previousIndex - ((NAVIGATION_3D_POINTS_BEHIND + 1) * step),
        step = step,
        size = NAVIGATION_3D_POINTS_BEHIND,
        wrapAround = wrapAround,
    ).simplifyFor3D()
    val coreBehindPoints = collectDirectionalWindow(
        startIndex = previousIndex - step,
        step = step,
        size = 2,
        wrapAround = wrapAround,
    )
    val coreAheadPoints = collectDirectionalWindow(
        startIndex = nextIndex,
        step = step,
        size = 2,
        wrapAround = wrapAround,
    )
    val distantAheadPoints = collectDirectionalWindow(
        startIndex = nextIndex + (2 * step),
        step = step,
        size = NAVIGATION_3D_POINTS_AHEAD,
        wrapAround = wrapAround,
    ).simplifyFor3D()

    return (
        distantBehindPoints +
            coreBehindPoints +
            listOfNotNull(anchorLocation) +
            coreAheadPoints +
            distantAheadPoints
        ).removeNearDuplicates()
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

private fun Long.toActiveDurationLabel(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
        minutes > 0 -> "${minutes}m ${seconds.toString().padStart(2, '0')}s"
        else -> "${seconds}s"
    }
}

private const val OFF_ROUTE_ALERT_DISTANCE_METERS = 50.0
private const val NAVIGATION_3D_POINTS_BEHIND = 8
private const val NAVIGATION_3D_POINTS_AHEAD = 28
private const val CLOSED_LOOP_ROUTE_THRESHOLD_METERS = 35.0
private const val NAVIGATION_3D_SIMPLIFICATION_TOLERANCE_METERS = 4.0
private const val MIN_RENDER_POINT_DISTANCE_METERS = 1.0
