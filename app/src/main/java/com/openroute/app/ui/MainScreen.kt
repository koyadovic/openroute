package com.openroute.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openroute.app.R

@Composable
fun MainRoute(viewModel: MainViewModel) {
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var downloadsAccessState by remember { mutableStateOf(context.resolveDownloadsAccessState()) }
    var trackingSetupDialogState by remember { mutableStateOf<TrackingSetupDialogState?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importGpx)
    }

    val locationPermissions = remember {
        listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    val pendingLocationAction = remember { mutableStateOf<LocationAction?>(null) }
    val startLocationAction = rememberUpdatedState(
        newValue = { action: LocationAction ->
            when (action) {
                LocationAction.Record -> viewModel.startRecording(context)
                LocationAction.Navigate -> viewModel.startNavigation(context)
                LocationAction.Breadcrumb -> viewModel.startBreadcrumbs(context)
            }
            pendingLocationAction.value = null
        },
    )
    val attemptPendingLocationAction = rememberUpdatedState(
        newValue = {
            val pendingAction = pendingLocationAction.value ?: return@rememberUpdatedState
            if (context.hasTrackingLocationPermission()) {
                if (!context.hasTrackingBatteryExemption()) {
                    viewModel.showMessage(
                        "Para pantalla bloqueada conviene también Batería > Sin restricciones.",
                    )
                }
                startLocationAction.value(pendingAction)
            }
        },
    )
    val promptBackgroundLocationAccess = rememberUpdatedState(
        newValue = { action: LocationAction ->
            pendingLocationAction.value = action
            trackingSetupDialogState = TrackingSetupDialogState(
                kind = TrackingSetupDialogKind.BackgroundLocation,
                action = action,
                title = "Falta \"Permitir siempre\"",
                message = when (action) {
                    LocationAction.Record ->
                        "Para seguir grabando con la pantalla bloqueada necesitas Ubicación > Permitir siempre."

                    LocationAction.Navigate ->
                        "Para seguir navegando con la pantalla bloqueada necesitas Ubicación > Permitir siempre."

                    LocationAction.Breadcrumb ->
                        "Para seguir sembrando migas con la pantalla bloqueada necesitas Ubicación > Permitir siempre."
                },
                confirmLabel = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    "Dar permiso"
                } else {
                    "Abrir ajustes"
                },
                dismissLabel = "Cancelar",
            )
        },
    )
    val maybePromptBatteryOptimization = rememberUpdatedState(
        newValue = { action: LocationAction ->
            if (context.hasTrackingBatteryExemption()) {
                startLocationAction.value(action)
            } else {
                trackingSetupDialogState = TrackingSetupDialogState(
                    kind = TrackingSetupDialogKind.BatteryOptimization,
                    action = action,
                    title = "Revisa la batería",
                    message = "La app puede empezar ya, pero para que no se corte al bloquear la pantalla conviene quitar la optimización de batería. En Samsung: Batería > Sin restricciones.",
                    confirmLabel = "Abrir batería",
                    dismissLabel = "Continuar",
                )
            }
        },
    )
    var hasRequestedStartupLocation by rememberSaveable { mutableStateOf(false) }
    val refreshCurrentLocation = rememberUpdatedState(
        newValue = {
            if (context.hasLocationPermission()) {
                viewModel.refreshCurrentLocation()
            }
        },
    )

    val backgroundLocationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted || context.hasBackgroundLocationPermission()) {
            attemptPendingLocationAction.value()
        } else if (pendingLocationAction.value != null) {
            viewModel.showMessage(
                "Falta Ubicación > Permitir siempre. Sin ello la ruta puede quedarse anclada al último punto.",
            )
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        val hasLocationPermission = context.hasLocationPermission()
        val hasPreciseLocationPermission = context.hasPreciseLocationPermission()

        if (hasLocationPermission) {
            refreshCurrentLocation.value()
            when {
                pendingLocationAction.value != null && hasPreciseLocationPermission && context.hasBackgroundLocationPermission() ->
                    attemptPendingLocationAction.value()

                pendingLocationAction.value != null && hasPreciseLocationPermission ->
                    pendingLocationAction.value?.let(promptBackgroundLocationAccess.value)

                pendingLocationAction.value == LocationAction.Record ->
                    run {
                        viewModel.showMessage("Necesito ubicación precisa para grabar la ruta.")
                        pendingLocationAction.value = null
                    }

                pendingLocationAction.value == LocationAction.Navigate ->
                    run {
                        viewModel.showMessage("Necesito ubicación precisa para navegar la ruta.")
                        pendingLocationAction.value = null
                    }

                pendingLocationAction.value == LocationAction.Breadcrumb ->
                    run {
                        viewModel.showMessage("Necesito ubicación precisa para sembrar migas.")
                        pendingLocationAction.value = null
                    }
            }
        } else if (pendingLocationAction.value != null) {
            viewModel.showMessage("Faltan permisos de localización.")
            pendingLocationAction.value = null
        }
    }

    val downloadsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        downloadsAccessState = context.resolveDownloadsAccessState()
        if (granted && downloadsAccessState == DownloadsAccessState.Granted) {
            viewModel.syncDownloadedGpxFiles()
        } else if (!granted) {
            viewModel.showMessage("Sin permiso no puedo revisar Descargas automaticamente.")
        }
    }

    val refreshDownloadsSync = rememberUpdatedState(
        newValue = {
            downloadsAccessState = context.resolveDownloadsAccessState()
            if (downloadsAccessState == DownloadsAccessState.Granted) {
                viewModel.syncDownloadedGpxFiles()
            }
        },
    )

    LaunchedEffect(Unit) {
        refreshDownloadsSync.value()
        if (context.hasLocationPermission()) {
            refreshCurrentLocation.value()
        } else if (!hasRequestedStartupLocation) {
            hasRequestedStartupLocation = true
            locationPermissionLauncher.launch(locationPermissions.toTypedArray())
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshDownloadsSync.value()
                refreshCurrentLocation.value()
                attemptPendingLocationAction.value()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val downloadsBannerState = remember(screenState.isSyncingDownloads, downloadsAccessState) {
        resolveDownloadsBannerState(
            isSyncingDownloads = screenState.isSyncingDownloads,
            accessPresentation = downloadsAccessState.toPresentation(),
        )
    }

    LaunchedEffect(screenState.snackbarMessage) {
        screenState.snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    BackHandler(enabled = screenState.mode != OpenRouteScreenMode.Browse) {
        when (screenState.mode) {
            OpenRouteScreenMode.Detail -> viewModel.closeRouteDetail()
            OpenRouteScreenMode.Navigation3D -> if (screenState.navigation3DState?.isBreadcrumb == true) {
                viewModel.stopBreadcrumbs(context)
            } else {
                viewModel.closeNavigation3D()
            }

            OpenRouteScreenMode.Browse -> Unit
        }
    }

    OpenRouteScreen(
        state = screenState,
        downloadsBannerState = downloadsBannerState,
        snackbarHostState = snackbarHostState,
        onImportClick = { importLauncher.launch(arrayOf("*/*")) },
        onTrackClick = {
            if (screenState.actionBar.isTracking) {
                viewModel.stopRecording(context)
            } else {
                context.withPreciseLocationPermission(
                    permissions = locationPermissions,
                    onGranted = {
                        if (context.hasBackgroundLocationPermission()) {
                            maybePromptBatteryOptimization.value(LocationAction.Record)
                        } else {
                            promptBackgroundLocationAccess.value(LocationAction.Record)
                        }
                    },
                    onMissingPermission = {
                        pendingLocationAction.value = LocationAction.Record
                        locationPermissionLauncher.launch(locationPermissions.toTypedArray())
                    },
                )
            }
        },
        onBreadcrumbClick = {
            if (screenState.actionBar.isBreadcrumbing) {
                viewModel.stopBreadcrumbs(context)
            } else {
                context.withPreciseLocationPermission(
                    permissions = locationPermissions,
                    onGranted = {
                        if (context.hasBackgroundLocationPermission()) {
                            maybePromptBatteryOptimization.value(LocationAction.Breadcrumb)
                        } else {
                            promptBackgroundLocationAccess.value(LocationAction.Breadcrumb)
                        }
                    },
                    onMissingPermission = {
                        pendingLocationAction.value = LocationAction.Breadcrumb
                        locationPermissionLauncher.launch(locationPermissions.toTypedArray())
                    },
                )
            }
        },
        onEnableDownloadsAutoImport = {
            when (downloadsAccessState) {
                DownloadsAccessState.Granted -> viewModel.syncDownloadedGpxFiles()
                DownloadsAccessState.NeedsPermission ->
                    downloadsPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)

                DownloadsAccessState.NeedsAllFilesAccess ->
                    context.openManageAllFilesAccessSettings()
            }
        },
        onHideSelectedClick = viewModel::hideSelectedRoute,
        onToggleHiddenRoutesClick = viewModel::toggleHiddenRoutesVisibility,
        onDeleteHiddenRouteClick = viewModel::requestDeleteRoute,
        onConfirmDeleteHiddenRouteClick = viewModel::confirmDeleteRoute,
        onDismissDeleteHiddenRouteClick = viewModel::dismissDeleteRoute,
        onOpenDetailClick = viewModel::openSelectedRouteDetail,
        onCloseDetailClick = viewModel::closeRouteDetail,
        onOpenRenameRouteClick = viewModel::openRenameRoute,
        onRenameDraftChange = viewModel::updateRenameDraft,
        onConfirmRenameRouteClick = viewModel::confirmRenameRoute,
        onDismissRenameRouteClick = viewModel::dismissRenameRoute,
        onNavigationClick = {
            val isNavigating = screenState.detailState?.navigationState?.isNavigating == true
            if (isNavigating) {
                viewModel.openNavigation3D()
            } else {
                context.withPreciseLocationPermission(
                    permissions = locationPermissions,
                    onGranted = {
                        if (context.hasBackgroundLocationPermission()) {
                            maybePromptBatteryOptimization.value(LocationAction.Navigate)
                        } else {
                            promptBackgroundLocationAccess.value(LocationAction.Navigate)
                        }
                    },
                    onMissingPermission = {
                        pendingLocationAction.value = LocationAction.Navigate
                        locationPermissionLauncher.launch(locationPermissions.toTypedArray())
                    },
                )
            }
        },
        onStopNavigationClick = {
            if (screenState.navigation3DState?.isBreadcrumb == true) {
                viewModel.stopBreadcrumbs(context)
            } else {
                viewModel.stopNavigation(context)
            }
        },
        onCloseNavigation3DClick = {
            if (screenState.navigation3DState?.isBreadcrumb == true) {
                viewModel.stopBreadcrumbs(context)
            } else {
                viewModel.closeNavigation3D()
            }
        },
        onRouteClick = viewModel::selectRoute,
    )

    trackingSetupDialogState?.let { dialogState ->
        TrackingSetupDialog(
            state = dialogState,
            onConfirm = {
                when (dialogState.kind) {
                    TrackingSetupDialogKind.BackgroundLocation -> {
                        pendingLocationAction.value = dialogState.action
                        context.requestBackgroundLocationAccess(
                            launcher = backgroundLocationPermissionLauncher,
                        )
                    }

                    TrackingSetupDialogKind.BatteryOptimization -> {
                        pendingLocationAction.value = dialogState.action
                        context.openTrackingBatterySettings()
                    }
                }
                trackingSetupDialogState = null
            },
            onDismiss = {
                when (dialogState.kind) {
                    TrackingSetupDialogKind.BackgroundLocation -> {
                        pendingLocationAction.value = null
                    }

                    TrackingSetupDialogKind.BatteryOptimization -> {
                        startLocationAction.value(dialogState.action)
                    }
                }
                trackingSetupDialogState = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenRouteScreen(
    state: OpenRouteScreenState,
    downloadsBannerState: DownloadsBannerState?,
    snackbarHostState: SnackbarHostState,
    onImportClick: () -> Unit,
    onTrackClick: () -> Unit,
    onBreadcrumbClick: () -> Unit,
    onEnableDownloadsAutoImport: () -> Unit,
    onHideSelectedClick: () -> Unit,
    onToggleHiddenRoutesClick: () -> Unit,
    onDeleteHiddenRouteClick: (String) -> Unit,
    onConfirmDeleteHiddenRouteClick: () -> Unit,
    onDismissDeleteHiddenRouteClick: () -> Unit,
    onOpenDetailClick: () -> Unit,
    onCloseDetailClick: () -> Unit,
    onOpenRenameRouteClick: () -> Unit,
    onRenameDraftChange: (String) -> Unit,
    onConfirmRenameRouteClick: () -> Unit,
    onDismissRenameRouteClick: () -> Unit,
    onNavigationClick: () -> Unit,
    onStopNavigationClick: () -> Unit,
    onCloseNavigation3DClick: () -> Unit,
    onRouteClick: (String) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_openroute_launcher),
                            contentDescription = null,
                            modifier = Modifier.size(42.dp),
                        )
                        Column {
                            Text(
                                text = state.header.title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = state.header.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        when (state.mode) {
            OpenRouteScreenMode.Browse -> BrowseScreen(
                state = state,
                downloadsBannerState = downloadsBannerState,
                onImportClick = onImportClick,
                onTrackClick = onTrackClick,
                onBreadcrumbClick = onBreadcrumbClick,
                onEnableDownloadsAutoImport = onEnableDownloadsAutoImport,
                onHideSelectedClick = onHideSelectedClick,
                onToggleHiddenRoutesClick = onToggleHiddenRoutesClick,
                onDeleteHiddenRouteClick = onDeleteHiddenRouteClick,
                onConfirmDeleteHiddenRouteClick = onConfirmDeleteHiddenRouteClick,
                onDismissDeleteHiddenRouteClick = onDismissDeleteHiddenRouteClick,
                onOpenDetailClick = onOpenDetailClick,
                onRouteClick = onRouteClick,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            OpenRouteScreenMode.Detail -> RouteDetailScreen(
                state = state.detailState,
                mapState = state.mapState,
                onCloseDetailClick = onCloseDetailClick,
                onOpenRenameRouteClick = onOpenRenameRouteClick,
                onRenameDraftChange = onRenameDraftChange,
                onConfirmRenameRouteClick = onConfirmRenameRouteClick,
                onDismissRenameRouteClick = onDismissRenameRouteClick,
                onNavigationClick = onNavigationClick,
                onStopNavigationClick = onStopNavigationClick,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            OpenRouteScreenMode.Navigation3D -> Navigation3DScreen(
                state = state.navigation3DState,
                onCloseClick = onCloseNavigation3DClick,
                onStopNavigationClick = onStopNavigationClick,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun BrowseScreen(
    state: OpenRouteScreenState,
    downloadsBannerState: DownloadsBannerState?,
    onImportClick: () -> Unit,
    onTrackClick: () -> Unit,
    onBreadcrumbClick: () -> Unit,
    onEnableDownloadsAutoImport: () -> Unit,
    onHideSelectedClick: () -> Unit,
    onToggleHiddenRoutesClick: () -> Unit,
    onDeleteHiddenRouteClick: (String) -> Unit,
    onConfirmDeleteHiddenRouteClick: () -> Unit,
    onDismissDeleteHiddenRouteClick: () -> Unit,
    onOpenDetailClick: () -> Unit,
    onRouteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ActionBar(
            state = state.actionBar,
            onImportClick = onImportClick,
            onTrackClick = onTrackClick,
            onBreadcrumbClick = onBreadcrumbClick,
        )

        downloadsBannerState?.let { bannerState ->
            DownloadsBanner(
                state = bannerState,
                onActionClick = onEnableDownloadsAutoImport,
            )
        }

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(28.dp),
        ) {
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                MapWebView(
                    state = state.mapState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        RouteSummary(state = state.summary)
        BrowseActions(
            state = state.browseAction,
            onHideSelectedClick = onHideSelectedClick,
            onOpenDetailClick = onOpenDetailClick,
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.9f),
            shape = RoundedCornerShape(24.dp),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.routeList.items.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = state.routeList.emptyMessage,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        }
                    }
                } else {
                    items(state.routeList.items, key = { it.id }) { item ->
                        RouteRow(
                            state = item,
                            onClick = { onRouteClick(item.id) },
                        )
                    }
                }

                state.routeList.hiddenRoutes?.let { hiddenState ->
                    item {
                        HiddenRoutesHeader(
                            state = hiddenState,
                            onToggleClick = onToggleHiddenRoutesClick,
                        )
                    }

                    if (hiddenState.isExpanded) {
                        items(hiddenState.items, key = { it.id }) { item ->
                            HiddenRouteRow(
                                state = item,
                                onDeleteClick = { onDeleteHiddenRouteClick(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    state.routeList.hiddenRoutes?.deleteDialog?.let { dialogState ->
        DeleteRouteDialog(
            state = dialogState,
            onConfirm = onConfirmDeleteHiddenRouteClick,
            onDismiss = onDismissDeleteHiddenRouteClick,
        )
    }
}

@Composable
private fun RouteDetailScreen(
    state: RouteDetailState?,
    mapState: com.openroute.app.data.MapRenderState,
    onCloseDetailClick: () -> Unit,
    onOpenRenameRouteClick: () -> Unit,
    onRenameDraftChange: (String) -> Unit,
    onConfirmRenameRouteClick: () -> Unit,
    onDismissRenameRouteClick: () -> Unit,
    onNavigationClick: () -> Unit,
    onStopNavigationClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == null) {
        return
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            OutlinedButton(onClick = onCloseDetailClick) {
                Text(state.backLabel)
            }
        }

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            MapWebView(
                state = mapState,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = state.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.fileLabel != null) {
                    Text(
                        text = "Archivo: ${state.fileLabel}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.canRename) {
                    OutlinedButton(onClick = onOpenRenameRouteClick) {
                        Text(state.renameLabel)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryChip(
                label = "Distance",
                value = state.distanceLabel,
                modifier = Modifier.weight(1f),
            )
            SummaryChip(
                label = "Duration",
                value = state.durationLabel,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryChip(
                label = "Points",
                value = state.pointsLabel,
                modifier = Modifier.weight(1f),
            )
            SummaryChip(
                label = "Source",
                value = state.sourceLabel,
                modifier = Modifier.weight(1f),
            )
        }

        NavigationCard(
            state = state.navigationState,
            onNavigationClick = onNavigationClick,
            onStopNavigationClick = onStopNavigationClick,
        )

        state.renameDialog?.let { dialogState ->
            RenameRouteDialog(
                state = dialogState,
                onValueChange = onRenameDraftChange,
                onConfirm = onConfirmRenameRouteClick,
                onDismiss = onDismissRenameRouteClick,
            )
        }
    }
}

@Composable
private fun Navigation3DScreen(
    state: Navigation3DState?,
    onCloseClick: () -> Unit,
    onStopNavigationClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == null) {
        return
    }

    val backgroundColor = if (state.showsOffRouteAlert) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.background
    }
    val statusColor = if (state.showsOffRouteAlert) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = modifier
            .background(backgroundColor)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onCloseClick) {
                Text(state.backLabel)
            }
            Button(onClick = onStopNavigationClick) {
                Text(state.stopLabel)
            }
        }

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(28.dp),
        ) {
            Navigation3DCanvas(
                state = state.renderState,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = backgroundColor),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = state.statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SummaryChip(
                        label = "Progress",
                        value = state.progressLabel,
                        modifier = Modifier.weight(1f),
                    )
                    SummaryChip(
                        label = "Remaining",
                        value = state.remainingLabel,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SummaryChip(
                        label = "ETA",
                        value = state.etaLabel,
                        modifier = Modifier.weight(1f),
                    )
                    SummaryChip(
                        label = "To Route",
                        value = state.distanceToRouteLabel,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionBar(
    state: ActionBarState,
    onImportClick: () -> Unit,
    onTrackClick: () -> Unit,
    onBreadcrumbClick: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = onImportClick,
                enabled = state.isImportEnabled,
            ) {
                if (state.showsImportProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(state.importLabel)
                }
            }

            Button(
                modifier = Modifier.weight(1f),
                onClick = onTrackClick,
            ) {
                Text(state.trackLabel)
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onBreadcrumbClick,
        ) {
            Text(state.breadcrumbLabel)
        }
    }
}

@Composable
private fun DownloadsBanner(
    state: DownloadsBannerState,
    onActionClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else if (state.actionLabel != null) {
                OutlinedButton(onClick = onActionClick) {
                    Text(state.actionLabel)
                }
            }
        }
    }
}

@Composable
private fun RouteSummary(state: SummaryState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryChip(
            label = state.routesLabel,
            value = state.routesValue,
            modifier = Modifier.weight(1f),
        )
        SummaryChip(
            label = state.liveTrackLabel,
            value = state.liveTrackValue,
            modifier = Modifier.weight(1f),
        )
        SummaryChip(
            label = state.selectedLabel,
            value = state.selectedValue,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BrowseActions(
    state: BrowseActionState,
    onHideSelectedClick: () -> Unit,
    onOpenDetailClick: () -> Unit,
) {
    if (!state.canHideSelected && !state.canOpenDetail) {
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        if (state.canOpenDetail) {
            OutlinedButton(onClick = onOpenDetailClick) {
                Text(state.openDetailLabel)
            }
            Spacer(modifier = Modifier.size(8.dp))
        }
        if (state.canHideSelected) {
            OutlinedButton(onClick = onHideSelectedClick) {
                Text(state.hideLabel)
            }
        }
    }
}

@Composable
private fun NavigationCard(
    state: RouteDetailNavigationState,
    onNavigationClick: () -> Unit,
    onStopNavigationClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.showsOffRouteAlert) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Navegación",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.statusLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.showsOffRouteAlert) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SummaryChip(
                    label = "Progress",
                    value = state.progressLabel,
                    modifier = Modifier.weight(1f),
                )
                SummaryChip(
                    label = "Remaining",
                    value = state.remainingLabel,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SummaryChip(
                    label = "ETA",
                    value = state.etaLabel,
                    modifier = Modifier.weight(1f),
                )
                SummaryChip(
                    label = "To Route",
                    value = state.distanceToRouteLabel,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.secondaryActionLabel == null) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onNavigationClick,
                ) {
                    Text(state.actionLabel)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onStopNavigationClick,
                    ) {
                        Text(state.secondaryActionLabel)
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onNavigationClick,
                    ) {
                        Text(state.actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameRouteDialog(
    state: RouteRenameDialogState,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title) },
        text = {
            OutlinedTextField(
                value = state.name,
                onValueChange = onValueChange,
                singleLine = true,
                label = { Text("Nombre") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = state.isConfirmEnabled,
            ) {
                Text(state.confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(state.dismissLabel)
            }
        },
    )
}

@Composable
private fun TrackingSetupDialog(
    state: TrackingSetupDialogState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title) },
        text = { Text(state.message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(state.confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(state.dismissLabel)
            }
        },
    )
}

@Composable
private fun DeleteRouteDialog(
    state: HiddenRouteDeleteDialogState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title) },
        text = { Text(state.message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(state.confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(state.dismissLabel)
            }
        },
    )
}

@Composable
private fun SummaryChip(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HiddenRoutesHeader(
    state: HiddenRoutesState,
    onToggleClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Rutas ocultas",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${state.countLabel} guardadas fuera de la vista principal",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (state.isExpanded) "Ocultar" else "Mostrar",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Toca aquí",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RouteRow(
    state: RouteListItemState,
    onClick: () -> Unit,
) {
    val borderColor = if (state.isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val badgeColor = when (state.badge) {
        RouteBadge.Recording -> MaterialTheme.colorScheme.tertiary
        RouteBadge.Imported -> MaterialTheme.colorScheme.secondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClick = onClick),
        border = BorderStroke(2.dp, borderColor),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(badgeColor, RoundedCornerShape(999.dp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.showsNewBadge) {
                    AssistChip(
                        onClick = onClick,
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                        label = { Text("Nueva") },
                    )
                }
                AssistChip(
                    onClick = onClick,
                    label = { Text(state.badge.label) },
                )
            }
        }
    }
}

@Composable
private fun HiddenRouteRow(
    state: HiddenRouteListItemState,
    onDeleteClick: () -> Unit,
) {
    val badgeColor = when (state.badge) {
        RouteBadge.Recording -> MaterialTheme.colorScheme.tertiary
        RouteBadge.Imported -> MaterialTheme.colorScheme.secondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(badgeColor, RoundedCornerShape(999.dp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onDeleteClick) {
                Text(state.deleteLabel)
            }
        }
    }
}

private enum class DownloadsAccessState {
    Granted,
    NeedsPermission,
    NeedsAllFilesAccess,
}

private enum class LocationAction {
    Record,
    Navigate,
    Breadcrumb,
}

private enum class TrackingSetupDialogKind {
    BackgroundLocation,
    BatteryOptimization,
}

private data class TrackingSetupDialogState(
    val kind: TrackingSetupDialogKind,
    val action: LocationAction,
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String,
)

private fun DownloadsAccessState.toPresentation(): DownloadsAccessPresentation {
    return when (this) {
        DownloadsAccessState.Granted -> DownloadsAccessPresentation.Granted
        DownloadsAccessState.NeedsPermission -> DownloadsAccessPresentation.NeedsPermission
        DownloadsAccessState.NeedsAllFilesAccess -> DownloadsAccessPresentation.NeedsAllFilesAccess
    }
}

private fun Context.resolveDownloadsAccessState(): DownloadsAccessState {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() ->
            DownloadsAccessState.Granted

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            DownloadsAccessState.NeedsAllFilesAccess

        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED -> DownloadsAccessState.Granted

        else -> DownloadsAccessState.NeedsPermission
    }
}

@Suppress("UNUSED_PARAMETER")
private fun Context.withPreciseLocationPermission(
    permissions: List<String>,
    onGranted: () -> Unit,
    onMissingPermission: () -> Unit,
) {
    if (hasPreciseLocationPermission()) {
        onGranted()
    } else {
        onMissingPermission()
    }
}

private fun Context.hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
}

private fun Context.hasPreciseLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
}

private fun Context.hasBackgroundLocationPermission(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
}

private fun Context.hasTrackingLocationPermission(): Boolean {
    return hasPreciseLocationPermission() && hasBackgroundLocationPermission()
}

private fun Context.hasTrackingBatteryExemption(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return true
    }

    val powerManager = getSystemService(PowerManager::class.java) ?: return true
    return powerManager.isIgnoringBatteryOptimizations(packageName)
}

private fun Context.requestBackgroundLocationAccess(
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
        launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    } else {
        openAppDetailsSettings()
    }
}

private fun Context.openManageAllFilesAccessSettings() {
    val packageUri = Uri.parse("package:$packageName")
    val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val openedAppSettings = runCatching {
        startActivity(appIntent)
    }.isSuccess

    if (!openedAppSettings) {
        runCatching {
            startActivity(fallbackIntent)
        }.onFailure { error ->
            if (error !is ActivityNotFoundException) {
                throw error
            }
        }
    }
}

private fun Context.openAppDetailsSettings() {
    val packageUri = Uri.parse("package:$packageName")
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

private fun Context.openTrackingBatterySettings() {
    val packageUri = Uri.parse("package:$packageName")
    val requestIgnoreIntent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        packageUri,
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val batteryOptimizationSettingsIntent = Intent(
        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val openedRequest = runCatching {
        startActivity(requestIgnoreIntent)
    }.isSuccess

    if (!openedRequest) {
        val openedBatterySettings = runCatching {
            startActivity(batteryOptimizationSettingsIntent)
        }.isSuccess

        if (!openedBatterySettings) {
            openAppDetailsSettings()
        }
    }
}
