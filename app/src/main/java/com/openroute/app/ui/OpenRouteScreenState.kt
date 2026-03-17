package com.openroute.app.ui

import com.openroute.app.data.MapRenderState
import com.openroute.app.data.MapRouteOverlay
import com.openroute.app.data.MapFocusState
import com.openroute.app.data.RouteNavigationProgress
import com.openroute.app.data.RouteSource
import com.openroute.app.data.RouteTrack
import com.openroute.app.data.effectiveDurationMillis

enum class OpenRouteScreenMode {
    Browse,
    Detail,
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
    val backLabel: String = "Volver",
    val navigationState: RouteDetailNavigationState = RouteDetailNavigationState(),
)

data class RouteDetailNavigationState(
    val isNavigating: Boolean = false,
    val actionLabel: String = "Iniciar navegación",
    val statusLabel: String = "Navegación inactiva",
    val progressLabel: String = "0%",
    val remainingLabel: String = "-",
    val etaLabel: String = "-",
    val distanceToRouteLabel: String = "-",
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

    return OpenRouteScreenState(
        mode = if (detailRoute != null) OpenRouteScreenMode.Detail else OpenRouteScreenMode.Browse,
        header = if (detailRoute != null) {
            HeaderState(
                title = detailRoute.name,
                subtitle = detailRoute.metricsSubtitle(),
            )
        } else {
            HeaderState()
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
        ),
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
        navigationState = RouteDetailNavigationState(
            isNavigating = isNavigating,
            actionLabel = if (isNavigating) "Detener navegación" else "Iniciar navegación",
            statusLabel = when {
                isNavigating && progress == null -> "Esperando posición..."
                progress == null -> "Navegación inactiva"
                progress.distanceToRouteMeters > 40 -> "Fuera de ruta (${progress.distanceToRouteMeters.toDistanceLabel()})"
                else -> "Siguiendo ruta"
            },
            progressLabel = progress?.completionRatio?.toPercentLabel() ?: "0%",
            remainingLabel = progress?.remainingDistanceMeters.toDistanceLabel(),
            etaLabel = progress?.estimatedRemainingSeconds.toEtaLabel(),
            distanceToRouteLabel = progress?.distanceToRouteMeters.toDistanceLabel(),
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
    return "${(this * 100).toInt().coerceIn(0, 100)}%"
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
