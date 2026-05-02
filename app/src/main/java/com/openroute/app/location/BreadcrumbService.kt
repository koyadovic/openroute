package com.openroute.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.openroute.app.MainActivity
import com.openroute.app.R

class BreadcrumbService : Service() {
    private lateinit var fusionRuntime: LocationFusionRuntime
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(applicationContext)
    }
    private val locationUpdatesPendingIntent by lazy {
        PendingIntent.getService(
            this,
            LOCATION_UPDATES_REQUEST_CODE,
            Intent(this, BreadcrumbService::class.java)
                .setAction(ACTION_LOCATION_UPDATE)
                .setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    override fun onCreate() {
        super.onCreate()
        fusionRuntime = LocationFusionRuntime(applicationContext) { update ->
            BreadcrumbSessionStore.updateLocation(
                point = update.point,
                appendBreadcrumb = update.source == FusedLocationSource.Gnss,
            )
            if (!BreadcrumbSessionStore.state.value.isActive) {
                finalizeAndStop()
                return@LocationFusionRuntime
            }

            if (update.source == FusedLocationSource.Gnss || update.shouldAppendVisitedPoint) {
                updateNotificationForState()
            }
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBreadcrumbs()
            ACTION_STOP -> finalizeAndStop()
            ACTION_LOCATION_UPDATE -> handleLocationUpdateIntent(intent)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationUpdates()
        fusionRuntime.stop()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startBreadcrumbs() {
        if (BreadcrumbSessionStore.state.value.isActive) {
            return
        }

        if (!hasFineLocationPermission()) {
            stopSelf()
            return
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                getString(R.string.breadcrumbs_title),
                getString(R.string.navigation_waiting_location),
            ),
        )
        BreadcrumbSessionStore.startSession()
        fusionRuntime.reset()
        fusionRuntime.start()

        fusedLocationClient
            .requestLocationUpdates(
                buildBreadcrumbLocationRequest(),
                locationUpdatesPendingIntent,
            )
            .addOnFailureListener {
                updateNotification(
                    getString(R.string.breadcrumbs_title),
                    getString(R.string.tracking_activation_failed),
                )
                finalizeAndStop()
            }

        requestBootstrapLocation()
    }

    private fun handleLocationUpdateIntent(intent: Intent) {
        val locationResult = LocationResult.extractResult(intent)
        if (locationResult == null) {
            Log.d(TAG, "Breadcrumb intent received without LocationResult payload")
        }
        locationResult
            ?.locations
            ?.sortedBy { location -> location.elapsedRealtimeNanos }
            ?.forEach(fusionRuntime::onLocationChanged)

        LocationAvailability.extractLocationAvailability(intent)
            ?.takeIf { availability -> !availability.isLocationAvailable }
            ?.let {
                Log.w(TAG, "Location availability reported as unavailable during breadcrumbs")
                updateNotification(
                    getString(R.string.breadcrumbs_title),
                    getString(R.string.tracking_signal_lost),
                )
            }
    }

    @SuppressLint("MissingPermission")
    private fun requestBootstrapLocation() {
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                location?.let(fusionRuntime::onLocationChanged)
            }

        val cancellationTokenSource = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationTokenSource.token,
        ).addOnSuccessListener { location ->
            location?.let(fusionRuntime::onLocationChanged)
        }
    }

    private fun finalizeAndStop() {
        stopLocationUpdates()
        fusionRuntime.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        BreadcrumbSessionStore.finishSession()
        stopSelf()
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationUpdatesPendingIntent)
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.breadcrumbs_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotificationForState() {
        val state = BreadcrumbSessionStore.state.value
        val title = when (state.mode) {
            BreadcrumbMode.Seeding -> getString(R.string.breadcrumbs_notification_seeding)
            BreadcrumbMode.Returning -> getString(R.string.breadcrumbs_subtitle_returning)
        }
        val text = when {
            state.isReturning && state.progress != null ->
                getString(
                    R.string.breadcrumbs_distance_to_start,
                    state.progress.remainingDistanceMeters.toDistanceLabel(),
                )

            else -> getString(R.string.tracking_points_registered, state.points.size)
        }
        updateNotification(title, text)
    }

    private fun updateNotification(title: String, text: String) {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildBreadcrumbLocationRequest(): LocationRequest {
        return LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL_MS,
        )
            .setMinUpdateIntervalMillis(MIN_LOCATION_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(LOCATION_UPDATE_DISTANCE_METERS)
            .setWaitForAccurateLocation(true)
            .setMaxUpdateDelayMillis(MAX_LOCATION_UPDATE_DELAY_MS)
            .build()
    }

    companion object {
        private const val TAG = "OpenRouteBreadcrumb"
        private const val CHANNEL_ID = "openroute_breadcrumbs"
        private const val NOTIFICATION_ID = 27
        private const val LOCATION_UPDATES_REQUEST_CODE = 2701
        private const val LOCATION_UPDATE_INTERVAL_MS = 1_500L
        private const val MIN_LOCATION_UPDATE_INTERVAL_MS = 750L
        private const val MAX_LOCATION_UPDATE_DELAY_MS = 3_000L
        private const val LOCATION_UPDATE_DISTANCE_METERS = 2f
        private const val ACTION_START = "com.openroute.app.action.START_BREADCRUMBS"
        private const val ACTION_STOP = "com.openroute.app.action.STOP_BREADCRUMBS"
        private const val ACTION_LOCATION_UPDATE = "com.openroute.app.action.BREADCRUMB_LOCATION_UPDATE"

        fun start(context: Context) {
            val intent = Intent(context, BreadcrumbService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BreadcrumbService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

private fun Double.toDistanceLabel(): String {
    return if (this >= 1000) {
        String.format("%.1f km", this / 1000.0)
    } else {
        String.format("%.0f m", this)
    }
}
