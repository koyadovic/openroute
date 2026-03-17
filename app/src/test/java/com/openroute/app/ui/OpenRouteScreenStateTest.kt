package com.openroute.app.ui

import com.openroute.app.data.LatLngPoint
import com.openroute.app.data.RouteSource
import com.openroute.app.data.RouteTrack
import com.openroute.app.location.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouteScreenStateTest {
    @Test
    fun `maps browse state into presentation state`() {
        val route = RouteTrack(
            id = "route-1",
            name = "Sunday Ride",
            source = RouteSource.RECORDED,
            createdAtMillis = 1_700_000_000_000,
            distanceMeters = 12_340.0,
            durationMillis = 2_760_000,
            points = listOf(
                LatLngPoint(40.4, -3.7),
                LatLngPoint(40.5, -3.6),
            ),
            isHidden = false,
        )
        val hiddenRoute = route.copy(
            id = "route-2",
            name = "Hidden Ride",
            isHidden = true,
        )

        val screenState = OpenRouteUiState(
            isLoading = false,
            isImporting = true,
            isSyncingDownloads = true,
            isTracking = true,
            routes = listOf(route, hiddenRoute),
            selectedRouteId = route.id,
            liveTrack = listOf(LatLngPoint(40.41, -3.71)),
            currentLocation = LatLngPoint(40.41, -3.71),
            message = "Ruta guardada",
        ).toScreenState()

        assertEquals(OpenRouteScreenMode.Browse, screenState.mode)
        assertFalse(screenState.isLoading)
        assertTrue(screenState.isSyncingDownloads)
        assertFalse(screenState.actionBar.isImportEnabled)
        assertTrue(screenState.actionBar.showsImportProgress)
        assertEquals("Stop recording", screenState.actionBar.trackLabel)
        assertEquals("1", screenState.summary.routesValue)
        assertEquals("1", screenState.summary.liveTrackValue)
        assertEquals("12.3 km", screenState.summary.selectedValue)
        assertTrue(screenState.browseAction.canHideSelected)
        assertTrue(screenState.browseAction.canOpenDetail)
        assertEquals("Ruta guardada", screenState.snackbarMessage)
        assertEquals(1, screenState.routeList.items.size)
        assertTrue(screenState.routeList.items.single().isSelected)
        assertFalse(screenState.routeList.items.single().showsNewBadge)
        assertEquals(RouteBadge.Recording, screenState.routeList.items.single().badge)
        assertEquals("12.3 km · 46 min · 2 puntos", screenState.routeList.items.single().subtitle)
        assertEquals(1, screenState.mapState.liveTrack.size)
        assertEquals("#0B6E4F", screenState.mapState.routes.single().color)
        assertNull(screenState.detailState)
    }

    @Test
    fun `maps detail state with navigation progress`() {
        val route = RouteTrack(
            id = "route-1",
            name = "Sierra Loop",
            source = RouteSource.IMPORTED_GPX,
            createdAtMillis = 1_700_000_000_000,
            distanceMeters = 10_000.0,
            durationMillis = 5_400_000,
            points = listOf(
                LatLngPoint(40.4, -3.7),
                LatLngPoint(40.41, -3.69),
                LatLngPoint(40.42, -3.68),
            ),
            originalFileName = "sierra-loop.gpx",
        )

        val screenState = OpenRouteUiState(
            isLoading = false,
            routes = listOf(route),
            selectedRouteId = route.id,
            detailRouteId = route.id,
            navigationState = NavigationState(
                isNavigating = true,
                route = route,
                currentLocation = LatLngPoint(40.41, -3.69),
                visitedPoints = listOf(LatLngPoint(40.405, -3.695)),
                progress = com.openroute.app.data.RouteNavigationProgress(
                    nearestRoutePointIndex = 1,
                    completedDistanceMeters = 4_500.0,
                    remainingDistanceMeters = 5_500.0,
                    completionRatio = 0.45,
                    distanceToRouteMeters = 12.0,
                    estimatedRemainingSeconds = 1_200,
                    currentSpeedMetersPerSecond = 4.5,
                ),
            ),
        ).toScreenState()

        assertEquals(OpenRouteScreenMode.Detail, screenState.mode)
        assertEquals("Sierra Loop", screenState.header.title)
        assertEquals("10.0 km · 1h 30m · 3 puntos", screenState.header.subtitle)
        assertEquals("10.0 km", screenState.detailState?.distanceLabel)
        assertEquals("1h 30m", screenState.detailState?.durationLabel)
        assertEquals("sierra-loop.gpx", screenState.detailState?.fileLabel)
        assertEquals(1, screenState.mapState.routes.size)
        assertEquals(route.id, screenState.mapState.routes.single().id)
        assertEquals(route.id, screenState.mapState.focus.routeId)
        assertTrue(screenState.mapState.focus.includeCurrentLocation)
        assertEquals("Siguiendo ruta", screenState.detailState?.navigationState?.statusLabel)
        assertEquals("45%", screenState.detailState?.navigationState?.progressLabel)
        assertEquals("5.5 km", screenState.detailState?.navigationState?.remainingLabel)
        assertEquals("20 min", screenState.detailState?.navigationState?.etaLabel)
        assertEquals("12 m", screenState.detailState?.navigationState?.distanceToRouteLabel)
        assertEquals("Detener navegación", screenState.detailState?.navigationState?.actionLabel)
    }

    @Test
    fun `resolves downloads banner from access state`() {
        val loadingBanner = resolveDownloadsBannerState(
            isSyncingDownloads = true,
            accessPresentation = DownloadsAccessPresentation.Granted,
        )
        val permissionBanner = resolveDownloadsBannerState(
            isSyncingDownloads = false,
            accessPresentation = DownloadsAccessPresentation.NeedsAllFilesAccess,
        )
        val noBanner = resolveDownloadsBannerState(
            isSyncingDownloads = false,
            accessPresentation = DownloadsAccessPresentation.Granted,
        )

        assertEquals("Descargas", loadingBanner?.title)
        assertTrue(loadingBanner?.isLoading == true)
        assertEquals("Permitir acceso", permissionBanner?.actionLabel)
        assertNull(noBanner)
    }

    @Test
    fun `derives duration from recorded point timestamps when route predates stored duration`() {
        val route = RouteTrack(
            id = "route-legacy",
            name = "Legacy Ride",
            source = RouteSource.RECORDED,
            createdAtMillis = 1_700_000_000_000,
            distanceMeters = 6_700.0,
            points = listOf(
                LatLngPoint(40.4, -3.7, timestampMillis = 1_000L),
                LatLngPoint(40.41, -3.69, timestampMillis = 121_000L),
            ),
        )

        val screenState = OpenRouteUiState(
            isLoading = false,
            routes = listOf(route),
            selectedRouteId = route.id,
            detailRouteId = route.id,
        ).toScreenState()

        assertEquals("6.7 km · 2 min · 2 puntos", screenState.header.subtitle)
        assertEquals("2 min", screenState.detailState?.durationLabel)
    }

    @Test
    fun `prefers current location zoom in browse mode when available`() {
        val screenState = OpenRouteUiState(
            isLoading = false,
            currentLocation = LatLngPoint(40.4168, -3.7038),
        ).toScreenState()

        assertTrue(screenState.mapState.focus.includeCurrentLocation)
        assertTrue(screenState.mapState.focus.preferCurrentLocationZoom)
        assertNull(screenState.mapState.focus.routeId)
    }

    @Test
    fun `orders visible routes by proximity to current location`() {
        val farRoute = RouteTrack(
            id = "route-far",
            name = "Far Ride",
            source = RouteSource.IMPORTED_GPX,
            createdAtMillis = 1_700_000_000_000,
            distanceMeters = 12_000.0,
            points = listOf(
                LatLngPoint(40.50, -3.80),
                LatLngPoint(40.51, -3.79),
            ),
        )
        val nearRoute = RouteTrack(
            id = "route-near",
            name = "Near Ride",
            source = RouteSource.RECORDED,
            createdAtMillis = 1_700_000_100_000,
            distanceMeters = 3_200.0,
            points = listOf(
                LatLngPoint(40.4170, -3.7039),
                LatLngPoint(40.4180, -3.7042),
            ),
        )

        val screenState = OpenRouteUiState(
            isLoading = false,
            routes = listOf(farRoute, nearRoute),
            currentLocation = LatLngPoint(40.4168, -3.7038),
        ).toScreenState()

        assertEquals(listOf("route-near", "route-far"), screenState.routeList.items.map { it.id })
        assertEquals("3.2 km", screenState.summary.selectedValue)
    }

    @Test
    fun `shows new badge for unseen imported routes`() {
        val route = RouteTrack(
            id = "route-new",
            name = "Fresh Import",
            source = RouteSource.IMPORTED_GPX,
            createdAtMillis = 1_700_000_000_000,
            distanceMeters = 4_800.0,
            points = listOf(
                LatLngPoint(40.40, -3.70),
                LatLngPoint(40.41, -3.69),
            ),
            isNew = true,
        )

        val screenState = OpenRouteUiState(
            isLoading = false,
            routes = listOf(route),
            selectedRouteId = route.id,
        ).toScreenState()

        assertTrue(screenState.routeList.items.single().showsNewBadge)
        assertEquals(RouteBadge.Imported, screenState.routeList.items.single().badge)
    }
}
