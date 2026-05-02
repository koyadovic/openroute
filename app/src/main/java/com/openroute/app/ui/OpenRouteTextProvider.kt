package com.openroute.app.ui

import android.content.Context
import com.openroute.app.R

internal interface OpenRouteTextProvider {
    val appTitle: String
    val appSubtitle: String
    val importGpx: String
    val startRecording: String
    val stopRecording: String
    val breadcrumbs: String
    val breadcrumbsStart: String
    val breadcrumbsStop: String
    val breadcrumbsReturning: String
    val routes: String
    val liveTrack: String
    val off: String
    val none: String
    val delete: String
    val cancel: String
    val back: String
    val save: String
    val routeHide: String
    val routeDelete: String
    val routeRename: String
    val routeRenameTitle: String
    val navigationStart: String
    val navigationOpen3D: String
    val navigationStop: String
    val navigationInactive: String
    val navigationWaitingLocation: String
    val navigationFollowingRoute: String
    val navigation3DSubtitle: String
    val navigationBackToDetail: String
    val routesDrawerSubtitle: String
    val recordingTitle: String
    val recordingDrawerSubtitle: String
    val breadcrumbsDrawerSubtitle: String
    val downloadsTitle: String
    val downloadsScanning: String
    val downloadsAutoImportTitle: String
    val downloadsAllFilesMessage: String
    val downloadsStorageMessage: String
    val downloadsAllowAccess: String
    val downloadsGrantPermission: String
    val routeSourceRecorded: String
    val routeSourceImported: String
    val recordingDurationLabel: String
    val breadcrumbsDurationLabel: String
    val breadcrumbsRouteName: String
    val breadcrumbsSubtitleReturning: String
    val breadcrumbsStopShort: String
    val breadcrumbsStatusReturning: String

    fun routesHeaderSubtitle(visibleRoutes: Int, hiddenRoutes: Int): String
    fun recordingSubtitle(isTracking: Boolean): String
    fun breadcrumbsSubtitle(isReturning: Boolean, isActive: Boolean): String
    fun routePoints(points: Int): String
    fun routesEmpty(hiddenRoutes: Int): String
    fun hiddenRoutesToggle(isExpanded: Boolean): String
    fun routeDeleteTitle(isHidden: Boolean): String
    fun routeDeleteMessage(routeName: String): String
    fun navigationOffRoute(distance: String): String
    fun breadcrumbsOffTrail(distance: String): String
}

internal class AndroidOpenRouteTextProvider(
    private val context: Context,
) : OpenRouteTextProvider {
    override val appTitle: String get() = context.getString(R.string.app_name)
    override val appSubtitle: String get() = context.getString(R.string.app_subtitle)
    override val importGpx: String get() = context.getString(R.string.import_gpx)
    override val startRecording: String get() = context.getString(R.string.start_recording)
    override val stopRecording: String get() = context.getString(R.string.stop_recording)
    override val breadcrumbs: String get() = context.getString(R.string.breadcrumbs_start)
    override val breadcrumbsStart: String get() = context.getString(R.string.breadcrumbs_action_start)
    override val breadcrumbsStop: String get() = context.getString(R.string.breadcrumbs_stop)
    override val breadcrumbsReturning: String get() = context.getString(R.string.breadcrumbs_returning)
    override val routes: String get() = context.getString(R.string.summary_routes)
    override val liveTrack: String get() = context.getString(R.string.summary_live_track)
    override val off: String get() = context.getString(R.string.summary_off)
    override val none: String get() = context.getString(R.string.summary_none)
    override val delete: String get() = context.getString(R.string.delete)
    override val cancel: String get() = context.getString(R.string.cancel)
    override val back: String get() = context.getString(R.string.back)
    override val save: String get() = context.getString(R.string.save)
    override val routeHide: String get() = context.getString(R.string.route_hide)
    override val routeDelete: String get() = context.getString(R.string.route_delete)
    override val routeRename: String get() = context.getString(R.string.route_rename)
    override val routeRenameTitle: String get() = context.getString(R.string.route_rename_title)
    override val navigationStart: String get() = context.getString(R.string.navigation_start)
    override val navigationOpen3D: String get() = context.getString(R.string.navigation_open_3d)
    override val navigationStop: String get() = context.getString(R.string.navigation_stop)
    override val navigationInactive: String get() = context.getString(R.string.navigation_inactive)
    override val navigationWaitingLocation: String get() = context.getString(R.string.navigation_waiting_location)
    override val navigationFollowingRoute: String get() = context.getString(R.string.navigation_following_route)
    override val navigation3DSubtitle: String get() = context.getString(R.string.navigation_3d_subtitle)
    override val navigationBackToDetail: String get() = context.getString(R.string.navigation_back_to_detail)
    override val routesDrawerSubtitle: String get() = context.getString(R.string.routes_drawer_subtitle)
    override val recordingTitle: String get() = context.getString(R.string.recording_title)
    override val recordingDrawerSubtitle: String get() = context.getString(R.string.recording_drawer_subtitle)
    override val breadcrumbsDrawerSubtitle: String get() = context.getString(R.string.breadcrumbs_drawer_subtitle)
    override val downloadsTitle: String get() = context.getString(R.string.downloads_title)
    override val downloadsScanning: String get() = context.getString(R.string.downloads_scanning)
    override val downloadsAutoImportTitle: String get() = context.getString(R.string.downloads_auto_import_title)
    override val downloadsAllFilesMessage: String get() = context.getString(R.string.downloads_all_files_message)
    override val downloadsStorageMessage: String get() = context.getString(R.string.downloads_storage_message)
    override val downloadsAllowAccess: String get() = context.getString(R.string.downloads_allow_access)
    override val downloadsGrantPermission: String get() = context.getString(R.string.downloads_grant_permission)
    override val routeSourceRecorded: String get() = context.getString(R.string.route_source_recorded)
    override val routeSourceImported: String get() = context.getString(R.string.route_source_imported)
    override val recordingDurationLabel: String get() = context.getString(R.string.recording_duration_label)
    override val breadcrumbsDurationLabel: String get() = context.getString(R.string.breadcrumbs_duration_label)
    override val breadcrumbsRouteName: String get() = context.getString(R.string.breadcrumbs_route_name)
    override val breadcrumbsSubtitleReturning: String get() = context.getString(R.string.breadcrumbs_subtitle_returning)
    override val breadcrumbsStopShort: String get() = context.getString(R.string.breadcrumbs_stop_short)
    override val breadcrumbsStatusReturning: String get() = context.getString(R.string.breadcrumbs_status_returning)

    override fun routesHeaderSubtitle(visibleRoutes: Int, hiddenRoutes: Int): String {
        return context.getString(R.string.routes_header_subtitle, visibleRoutes, hiddenRoutes)
    }

    override fun recordingSubtitle(isTracking: Boolean): String {
        return context.getString(
            if (isTracking) R.string.recording_subtitle_active else R.string.recording_subtitle_idle,
        )
    }

    override fun breadcrumbsSubtitle(isReturning: Boolean, isActive: Boolean): String {
        return context.getString(
            when {
                isReturning -> R.string.breadcrumbs_subtitle_returning
                isActive -> R.string.breadcrumbs_subtitle_active
                else -> R.string.breadcrumbs_subtitle_idle
            },
        )
    }

    override fun routePoints(points: Int): String {
        return context.getString(R.string.route_points_count, points)
    }

    override fun routesEmpty(hiddenRoutes: Int): String {
        return if (hiddenRoutes > 0) {
            context.getString(R.string.routes_empty_with_hidden, hiddenRoutes)
        } else {
            context.getString(R.string.routes_empty)
        }
    }

    override fun hiddenRoutesToggle(isExpanded: Boolean): String {
        return context.getString(
            if (isExpanded) R.string.hidden_routes_hide else R.string.hidden_routes_show,
        )
    }

    override fun routeDeleteTitle(isHidden: Boolean): String {
        return context.getString(
            if (isHidden) R.string.route_delete_hidden_title else R.string.route_delete_title,
        )
    }

    override fun routeDeleteMessage(routeName: String): String {
        return context.getString(R.string.route_delete_message, routeName)
    }

    override fun navigationOffRoute(distance: String): String {
        return context.getString(R.string.navigation_off_route, distance)
    }

    override fun breadcrumbsOffTrail(distance: String): String {
        return context.getString(R.string.breadcrumbs_status_off_route, distance)
    }
}

internal object EnglishOpenRouteTextProvider : OpenRouteTextProvider {
    override val appTitle = "OpenRoute"
    override val appSubtitle = "Local GPX, OSM map and route recording"
    override val importGpx = "Import GPX"
    override val startRecording = "Record"
    override val stopRecording = "Stop recording"
    override val breadcrumbs = "Breadcrumbs"
    override val breadcrumbsStart = "Start"
    override val breadcrumbsStop = "Stop breadcrumbs"
    override val breadcrumbsReturning = "Returning"
    override val routes = "Routes"
    override val liveTrack = "Live track"
    override val off = "off"
    override val none = "-"
    override val delete = "Delete"
    override val cancel = "Cancel"
    override val back = "Back"
    override val save = "Save"
    override val routeHide = "Hide route"
    override val routeDelete = "Delete route"
    override val routeRename = "Rename"
    override val routeRenameTitle = "Rename route"
    override val navigationStart = "Start navigation"
    override val navigationOpen3D = "Open 3D guide"
    override val navigationStop = "Stop navigation"
    override val navigationInactive = "Navigation inactive"
    override val navigationWaitingLocation = "Waiting for location..."
    override val navigationFollowingRoute = "Following route"
    override val navigation3DSubtitle = "Approximate 3D guide"
    override val navigationBackToDetail = "Back to detail"
    override val routesDrawerSubtitle = "View, import and manage routes"
    override val recordingTitle = "Record route"
    override val recordingDrawerSubtitle = "Record a new outing"
    override val breadcrumbsDrawerSubtitle = "Leave a trail to return"
    override val downloadsTitle = "Downloads"
    override val downloadsScanning = "Searching Downloads for new GPX files..."
    override val downloadsAutoImportTitle = "Auto-import Downloads"
    override val downloadsAllFilesMessage = "Allow access to Downloads to import GPX files automatically when the app opens."
    override val downloadsStorageMessage = "Allow storage read access to import downloaded GPX files when the app opens."
    override val downloadsAllowAccess = "Allow access"
    override val downloadsGrantPermission = "Grant permission"
    override val routeSourceRecorded = "Locally recorded route"
    override val routeSourceImported = "Route imported from GPX"
    override val recordingDurationLabel = "Recording time"
    override val breadcrumbsDurationLabel = "Breadcrumb time"
    override val breadcrumbsRouteName = "Breadcrumbs"
    override val breadcrumbsSubtitleReturning = "Returning to start"
    override val breadcrumbsStopShort = "Stop breadcrumbs"
    override val breadcrumbsStatusReturning = "Returning by your breadcrumbs"

    override fun routesHeaderSubtitle(visibleRoutes: Int, hiddenRoutes: Int): String = "$visibleRoutes visible · $hiddenRoutes hidden"
    override fun recordingSubtitle(isTracking: Boolean): String = if (isTracking) "Recording local track" else "Record a new route"
    override fun breadcrumbsSubtitle(isReturning: Boolean, isActive: Boolean): String = when {
        isReturning -> "Returning to start"
        isActive -> "Leaving trail"
        else -> "Leave a trail to return"
    }
    override fun routePoints(points: Int): String = "$points points"
    override fun routesEmpty(hiddenRoutes: Int): String = if (hiddenRoutes > 0) {
        "No visible routes. You can review the $hiddenRoutes hidden ones."
    } else {
        "No routes yet. Import a GPX or start recording."
    }
    override fun hiddenRoutesToggle(isExpanded: Boolean): String = if (isExpanded) "Hide hidden" else "Show hidden"
    override fun routeDeleteTitle(isHidden: Boolean): String = if (isHidden) "Delete hidden route" else "Delete route"
    override fun routeDeleteMessage(routeName: String): String = "\"$routeName\" will be removed from OpenRoute."
    override fun navigationOffRoute(distance: String): String = "Off route ($distance)"
    override fun breadcrumbsOffTrail(distance: String): String = "Off trail ($distance)"
}

internal fun OpenRouteTextProvider.drawerItems(currentSection: OpenRouteMainSection): List<DrawerItemState> {
    return listOf(
        DrawerItemState(
            section = OpenRouteMainSection.Routes,
            icon = "🗺️",
            title = routes,
            subtitle = routesDrawerSubtitle,
            isSelected = currentSection == OpenRouteMainSection.Routes,
        ),
        DrawerItemState(
            section = OpenRouteMainSection.Recording,
            icon = "⏺️",
            title = recordingTitle,
            subtitle = recordingDrawerSubtitle,
            isSelected = currentSection == OpenRouteMainSection.Recording,
        ),
        DrawerItemState(
            section = OpenRouteMainSection.Breadcrumbs,
            icon = "🧭",
            title = breadcrumbs,
            subtitle = breadcrumbsDrawerSubtitle,
            isSelected = currentSection == OpenRouteMainSection.Breadcrumbs,
        ),
    )
}
