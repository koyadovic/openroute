package com.openroute.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openroute.app.R
import com.openroute.app.data.DownloadsGpxAutoImporter
import com.openroute.app.data.GpxImporter
import com.openroute.app.data.RouteRepository
import com.openroute.app.data.RouteSource
import com.openroute.app.data.RouteTrack
import com.openroute.app.data.distanceMeters
import com.openroute.app.data.sortedByDistanceTo
import com.openroute.app.location.BreadcrumbService
import com.openroute.app.location.BreadcrumbSessionStore
import com.openroute.app.location.BreadcrumbState
import com.openroute.app.location.LastKnownLocationReader
import com.openroute.app.location.NavigationService
import com.openroute.app.location.NavigationSessionStore
import com.openroute.app.location.NavigationState
import com.openroute.app.location.TrackingService
import com.openroute.app.location.TrackingSessionStore
import com.openroute.app.location.TrackingState
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class OpenRouteUiState(
    val isLoading: Boolean = true,
    val isImporting: Boolean = false,
    val isSyncingDownloads: Boolean = false,
    val isTracking: Boolean = false,
    val trackingStartedAtMillis: Long? = null,
    val clockNowMillis: Long = System.currentTimeMillis(),
    val routes: List<RouteTrack> = emptyList(),
    val mainSection: OpenRouteMainSection = OpenRouteMainSection.Routes,
    val selectedRouteId: String? = null,
    val detailRouteId: String? = null,
    val navigation3DRouteId: String? = null,
    val liveTrack: List<com.openroute.app.data.LatLngPoint> = emptyList(),
    val currentLocation: com.openroute.app.data.LatLngPoint? = null,
    val navigationState: NavigationState = NavigationState(),
    val breadcrumbState: BreadcrumbState = BreadcrumbState(),
    val showsHiddenRoutes: Boolean = false,
    val deleteRouteId: String? = null,
    val renameRouteId: String? = null,
    val renameDraft: String = "",
    val message: String? = null,
) {
    val visibleRoutes: List<RouteTrack>
        get() = routes
            .filterNot(RouteTrack::isHidden)
            .sortedByDistanceTo(navigationState.currentLocation ?: breadcrumbState.currentLocation ?: currentLocation)

    val hiddenRoutes: List<RouteTrack>
        get() = routes
            .filter(RouteTrack::isHidden)
            .sortedByDistanceTo(navigationState.currentLocation ?: breadcrumbState.currentLocation ?: currentLocation)

    val selectedRoute: RouteTrack?
        get() = visibleRoutes.firstOrNull { it.id == selectedRouteId } ?: visibleRoutes.firstOrNull()

    val detailRoute: RouteTrack?
        get() = routes.firstOrNull { it.id == detailRouteId }

    val navigation3DRoute: RouteTrack?
        get() = visibleRoutes.firstOrNull { it.id == navigation3DRouteId }

    val routePendingDeletion: RouteTrack?
        get() = routes.firstOrNull { it.id == deleteRouteId }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repository = RouteRepository(application)
    private val textProvider = AndroidOpenRouteTextProvider(application)
    private val importer = GpxImporter(application.contentResolver, application)
    private val downloadsAutoImporter = DownloadsGpxAutoImporter(importer)
    private val lastKnownLocationReader = LastKnownLocationReader(application)

    private val _uiState = MutableStateFlow(OpenRouteUiState())
    val screenState = _uiState
        .map { state -> state.toScreenState(textProvider) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OpenRouteUiState().toScreenState(textProvider),
        )

    init {
        refreshRoutes()

        viewModelScope.launch {
            TrackingSessionStore.state.collect { trackingState ->
                _uiState.update { current ->
                    current.copy(
                        isTracking = trackingState.isRecording,
                        trackingStartedAtMillis = trackingState.startedAtMillis,
                        clockNowMillis = System.currentTimeMillis(),
                        liveTrack = current.resolveLiveTrack(trackingState = trackingState),
                        currentLocation = current.navigationState.currentLocation
                            ?: current.breadcrumbState.currentLocation
                            ?: trackingState.currentLocation
                            ?: current.currentLocation,
                    )
                }
            }
        }

        viewModelScope.launch {
            NavigationSessionStore.state.collect { navigationState ->
                _uiState.update { current ->
                    current.copy(
                        navigationState = navigationState,
                        liveTrack = current.copy(navigationState = navigationState)
                            .resolveLiveTrack(trackingState = TrackingSessionStore.state.value),
                        navigation3DRouteId = if (navigationState.isNavigating) current.navigation3DRouteId else null,
                        currentLocation = navigationState.currentLocation
                            ?: current.breadcrumbState.currentLocation
                            ?: if (current.isTracking) {
                                TrackingSessionStore.state.value.currentLocation
                            } else {
                                current.currentLocation
                            },
                    )
                }
            }
        }

        viewModelScope.launch {
            BreadcrumbSessionStore.state.collect { breadcrumbState ->
                _uiState.update { current ->
                    current.copy(
                        breadcrumbState = breadcrumbState,
                        clockNowMillis = System.currentTimeMillis(),
                        liveTrack = current.copy(breadcrumbState = breadcrumbState)
                            .resolveLiveTrack(trackingState = TrackingSessionStore.state.value),
                        currentLocation = current.navigationState.currentLocation
                            ?: breadcrumbState.currentLocation
                            ?: if (current.isTracking) {
                                TrackingSessionStore.state.value.currentLocation
                            } else {
                                current.currentLocation
                            },
                    )
                }
            }
        }

        viewModelScope.launch {
            TrackingSessionStore.savedRoutes.collect { route ->
                _uiState.update { current ->
                    current.copy(
                        routes = listOf(route) + current.routes.filterNot { it.id == route.id },
                        selectedRouteId = route.id,
                        message = app.getString(R.string.message_route_saved, route.name),
                    )
                }
            }
        }

        viewModelScope.launch {
            while (true) {
                delay(ACTIVE_DURATION_TICK_MILLIS)
                _uiState.update { current ->
                    if (current.isTracking || current.breadcrumbState.isActive) {
                        current.copy(clockNowMillis = System.currentTimeMillis())
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun refreshRoutes() {
        viewModelScope.launch {
            val routes = repository.loadRoutes()
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    routes = routes,
                    selectedRouteId = current.resolveNextSelectedRouteId(routes),
                    detailRouteId = current.detailRouteId
                        ?.takeIf { detailId -> routes.any { route -> route.id == detailId } },
                    navigation3DRouteId = current.navigation3DRouteId
                        ?.takeIf { sceneId -> routes.any { route -> route.id == sceneId && !route.isHidden } },
                )
            }
        }
    }

    fun importGpx(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, message = null) }

            runCatching {
                val imported = importer.import(uri)
                val route = RouteTrack(
                    id = UUID.randomUUID().toString(),
                    name = imported.name,
                    source = RouteSource.IMPORTED_GPX,
                    createdAtMillis = System.currentTimeMillis(),
                    distanceMeters = imported.points.distanceMeters(),
                    points = imported.points,
                    isNew = true,
                )
                repository.addRoute(route)
            }.onSuccess { route ->
                _uiState.update { current ->
                    current.copy(
                        isImporting = false,
                        routes = listOf(route) + current.routes.filterNot { it.id == route.id },
                        selectedRouteId = route.id,
                        detailRouteId = current.detailRouteId,
                        navigation3DRouteId = current.navigation3DRouteId,
                        message = app.getString(R.string.message_route_imported, route.name),
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        message = error.message ?: app.getString(R.string.message_import_failed),
                    )
                }
            }
        }
    }

    fun openMainSection(section: OpenRouteMainSection) {
        _uiState.update { current ->
            current.copy(
                mainSection = section,
                selectedRouteId = if (section == OpenRouteMainSection.Routes) {
                    current.resolveNextSelectedRouteId(current.routes)
                } else {
                    current.selectedRouteId
                },
                detailRouteId = null,
                navigation3DRouteId = null,
                showsHiddenRoutes = false,
                deleteRouteId = null,
                renameRouteId = null,
                renameDraft = "",
            )
        }
    }

    fun openRouteDetail(routeId: String) {
        val route = _uiState.value.routes.firstOrNull { it.id == routeId } ?: return
        val shouldClearNewBadge = route.source == RouteSource.IMPORTED_GPX && route.isNew

        _uiState.update { current ->
            current.copy(
                routes = if (shouldClearNewBadge) {
                    current.routes.map { currentRoute ->
                        if (currentRoute.id == route.id) {
                            currentRoute.copy(isNew = false)
                        } else {
                            currentRoute
                        }
                    }
                } else {
                    current.routes
                },
                selectedRouteId = if (route.isHidden) current.selectedRouteId else route.id,
                detailRouteId = route.id,
            )
        }

        if (!shouldClearNewBadge) {
            return
        }

        viewModelScope.launch {
            runCatching {
                repository.markRouteAsSeen(route.id)
            }
        }
    }

    fun closeRouteDetail() {
        _uiState.update { it.copy(detailRouteId = null, renameRouteId = null, renameDraft = "") }
    }

    fun syncDownloadedGpxFiles() {
        if (_uiState.value.isSyncingDownloads) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingDownloads = true) }

            val existingReferences = repository.loadRoutes()
                .mapNotNull(RouteTrack::importReference)
                .toSet()

            val importResult = downloadsAutoImporter.importNewFiles(existingReferences)
            repository.addRoutes(importResult.importedRoutes)
            val routes = repository.loadRoutes()

            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    isSyncingDownloads = false,
                    routes = routes,
                    selectedRouteId = current.resolveSelectionAfterImports(routes, importResult.importedRoutes),
                    detailRouteId = current.detailRouteId
                        ?.takeIf { detailId -> routes.any { route -> route.id == detailId } },
                    navigation3DRouteId = current.navigation3DRouteId
                        ?.takeIf { sceneId -> routes.any { route -> route.id == sceneId && !route.isHidden } },
                    message = importResult.toMessage(app),
                )
            }
        }
    }

    fun refreshCurrentLocation() {
        viewModelScope.launch {
            val location = lastKnownLocationReader.read() ?: return@launch
            _uiState.update { current ->
                current.copy(
                    currentLocation = when {
                        current.navigationState.currentLocation != null -> current.navigationState.currentLocation
                        current.breadcrumbState.currentLocation != null -> current.breadcrumbState.currentLocation
                        current.isTracking -> TrackingSessionStore.state.value.currentLocation ?: location
                        else -> location
                    },
                )
            }
        }
    }

    fun hideDetailRoute() {
        val routeId = _uiState.value.detailRouteId ?: return
        hideRoute(routeId)
    }

    private fun hideRoute(routeId: String) {
        viewModelScope.launch {
            val hiddenRoute = repository.hideRoute(routeId) ?: return@launch
            val routes = repository.loadRoutes()

            _uiState.update { current ->
                current.copy(
                    routes = routes,
                    selectedRouteId = current.resolveNextSelectedRouteId(routes),
                    detailRouteId = current.detailRouteId
                        ?.takeIf { detailId -> detailId != routeId && routes.any { route -> route.id == detailId && !route.isHidden } },
                    navigation3DRouteId = current.navigation3DRouteId
                        ?.takeIf { sceneId -> sceneId != routeId && routes.any { route -> route.id == sceneId && !route.isHidden } },
                    message = app.getString(R.string.message_route_hidden, hiddenRoute.name),
                )
            }
        }
    }

    fun toggleHiddenRoutesVisibility() {
        _uiState.update { current ->
            current.copy(
                showsHiddenRoutes = if (current.hiddenRoutes.isEmpty()) {
                    false
                } else {
                    !current.showsHiddenRoutes
                },
            )
        }
    }

    fun requestDeleteRoute(routeId: String) {
        val route = _uiState.value.routes.firstOrNull { it.id == routeId } ?: return
        _uiState.update { current ->
            current.copy(deleteRouteId = route.id)
        }
    }

    fun requestDeleteDetailRoute() {
        val routeId = _uiState.value.detailRouteId ?: return
        requestDeleteRoute(routeId)
    }

    fun dismissDeleteRoute() {
        _uiState.update { it.copy(deleteRouteId = null) }
    }

    fun confirmDeleteRoute() {
        val routeId = _uiState.value.deleteRouteId ?: return

        viewModelScope.launch {
            val deletedRoute = repository.deleteRoute(routeId) ?: return@launch
            val routes = repository.loadRoutes()

            _uiState.update { current ->
                current.copy(
                    routes = routes,
                    selectedRouteId = current.resolveNextSelectedRouteId(routes),
                    detailRouteId = current.detailRouteId
                        ?.takeIf { detailId -> routes.any { route -> route.id == detailId } },
                    navigation3DRouteId = current.navigation3DRouteId
                        ?.takeIf { sceneId -> routes.any { route -> route.id == sceneId && !route.isHidden } },
                    showsHiddenRoutes = current.showsHiddenRoutes && routes.any(RouteTrack::isHidden),
                    deleteRouteId = null,
                    message = app.getString(R.string.message_route_deleted, deletedRoute.name),
                )
            }
        }
    }

    fun startNavigation(context: Context) {
        val route = _uiState.value.detailRoute ?: _uiState.value.selectedRoute ?: return
        if (_uiState.value.breadcrumbState.isActive) {
            BreadcrumbService.stop(context)
        }
        NavigationSessionStore.startSession(route)
        _uiState.update { current ->
            current.copy(
                selectedRouteId = route.id,
                detailRouteId = route.id,
                navigation3DRouteId = route.id,
            )
        }
        NavigationService.start(context)
    }

    fun openNavigation3D() {
        val routeId = _uiState.value.navigationState.route?.id ?: _uiState.value.detailRoute?.id ?: return
        _uiState.update { current ->
            current.copy(
                detailRouteId = routeId,
                navigation3DRouteId = routeId,
            )
        }
    }

    fun closeNavigation3D() {
        _uiState.update { current ->
            current.copy(navigation3DRouteId = null)
        }
    }

    fun stopNavigation(context: Context) {
        _uiState.update { current ->
            current.copy(navigation3DRouteId = null)
        }
        NavigationService.stop(context)
    }

    fun startRecording(context: Context) {
        if (_uiState.value.breadcrumbState.isActive) {
            BreadcrumbService.stop(context)
        }
        _uiState.update { it.copy(mainSection = OpenRouteMainSection.Recording) }
        TrackingService.start(context)
    }

    fun stopRecording(context: Context) {
        TrackingService.stop(context)
    }

    fun startBreadcrumbs(context: Context) {
        if (_uiState.value.isTracking) {
            TrackingService.stop(context)
        }
        if (_uiState.value.navigationState.isNavigating) {
            NavigationService.stop(context)
        }
        _uiState.update { current ->
            current.copy(
                mainSection = OpenRouteMainSection.Breadcrumbs,
                navigation3DRouteId = null,
            )
        }
        BreadcrumbService.start(context)
    }

    fun stopBreadcrumbs(context: Context) {
        BreadcrumbService.stop(context)
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun openRenameRoute() {
        val route = _uiState.value.detailRoute?.takeIf { it.source == RouteSource.RECORDED } ?: return
        _uiState.update { current ->
            current.copy(
                renameRouteId = route.id,
                renameDraft = route.name,
            )
        }
    }

    fun updateRenameDraft(name: String) {
        _uiState.update { current ->
            if (current.renameRouteId == null) {
                current
            } else {
                current.copy(renameDraft = name)
            }
        }
    }

    fun dismissRenameRoute() {
        _uiState.update { it.copy(renameRouteId = null, renameDraft = "") }
    }

    fun confirmRenameRoute() {
        val currentState = _uiState.value
        val routeId = currentState.renameRouteId ?: return
        val newName = currentState.renameDraft.trim()
        if (newName.isEmpty()) {
            showMessage(getApplication<Application>().getString(R.string.route_rename_empty))
            return
        }

        viewModelScope.launch {
            val renamedRoute = repository.renameRoute(routeId, newName) ?: return@launch
            NavigationSessionStore.refreshRoute(renamedRoute)
            _uiState.update { current ->
                current.copy(
                    routes = current.routes.map { route ->
                        if (route.id == routeId) {
                            renamedRoute
                        } else {
                            route
                        }
                    },
                    renameRouteId = null,
                    renameDraft = "",
                    message = getApplication<Application>().getString(R.string.message_route_renamed, renamedRoute.name),
                )
            }
        }
    }
}

private fun OpenRouteUiState.resolveSelectionAfterImports(
    routes: List<RouteTrack>,
    importedRoutes: List<RouteTrack>,
): String? {
    val selectedVisibleRouteId = selectedRouteId
        ?.takeIf { selectedId -> routes.any { route -> route.id == selectedId && !route.isHidden } }
    if (selectedVisibleRouteId != null) {
        return selectedVisibleRouteId
    }

    return importedRoutes.firstOrNull { !it.isHidden }?.id
        ?: resolveNextSelectedRouteId(routes)
}

private fun OpenRouteUiState.resolveNextSelectedRouteId(routes: List<RouteTrack>): String? {
    return selectedRouteId
        ?.takeIf { selectedId -> routes.any { route -> route.id == selectedId && !route.isHidden } }
        ?: routes.firstOrNull { !it.isHidden }?.id
}

private fun OpenRouteUiState.resolveLiveTrack(trackingState: TrackingState): List<com.openroute.app.data.LatLngPoint> {
    return when {
        navigationState.isNavigating -> navigationState.visitedPoints
        breadcrumbState.isActive -> breadcrumbState.points
        trackingState.isRecording -> trackingState.points
        else -> emptyList()
    }
}

private fun com.openroute.app.data.DownloadsImportResult.toMessage(context: Context): String? {
    return when {
        importedRoutes.isNotEmpty() && failedFiles > 0 ->
            context.getString(R.string.message_downloads_imported_with_failures, importedRoutes.size, failedFiles)

        importedRoutes.isNotEmpty() ->
            context.getString(R.string.message_downloads_imported, importedRoutes.size)

        failedFiles > 0 ->
            context.getString(R.string.message_downloads_failed, failedFiles)

        else -> null
    }
}

private const val ACTIVE_DURATION_TICK_MILLIS = 1_000L
