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

class NavigationService : Service() {
    private lateinit var fusionRuntime: LocationFusionRuntime
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(applicationContext)
    }
    private val locationUpdatesPendingIntent by lazy {
        PendingIntent.getService(
            this,
            LOCATION_UPDATES_REQUEST_CODE,
            Intent(this, NavigationService::class.java)
                .setAction(ACTION_LOCATION_UPDATE)
                .setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    override fun onCreate() {
        super.onCreate()
        fusionRuntime = LocationFusionRuntime(applicationContext) { update ->
            NavigationSessionStore.updateLocation(
                point = update.point,
                appendVisited = update.shouldAppendVisitedPoint,
            )

            if (update.source == FusedLocationSource.Gnss || update.shouldAppendVisitedPoint) {
                val navigationState = NavigationSessionStore.state.value
                val routeName = navigationState.route?.name ?: getString(R.string.navigation_default_route)
                val progress = navigationState.progress

                updateNotification(
                    title = getString(R.string.navigation_notification_title, routeName),
                    text = if (progress != null) {
                        getString(
                            R.string.navigation_notification_text,
                            (progress.completionRatio * 100).toInt(),
                            progress.remainingDistanceMeters.toDistanceLabel(),
                        )
                    } else {
                        getString(R.string.navigation_waiting_location)
                    },
                )
            }
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startNavigation()
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
    private fun startNavigation() {
        if (NavigationSessionStore.state.value.route == null) {
            stopSelf()
            return
        }

        if (!hasFineLocationPermission()) {
            stopSelf()
            return
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                getString(R.string.navigation_active),
                getString(R.string.navigation_waiting_location),
            ),
        )
        fusionRuntime.reset()
        fusionRuntime.start()

        fusedLocationClient
            .requestLocationUpdates(
                buildNavigationLocationRequest(),
                locationUpdatesPendingIntent,
            )
            .addOnFailureListener {
                updateNotification(
                    getString(R.string.navigation_active),
                    getString(R.string.tracking_activation_failed),
                )
                finalizeAndStop()
            }

        requestBootstrapLocation()
    }

    private fun handleLocationUpdateIntent(intent: Intent) {
        LocationResult.extractResult(intent)
            ?.locations
            ?.sortedBy { location -> location.elapsedRealtimeNanos }
            ?.forEach(fusionRuntime::onLocationChanged)

        LocationAvailability.extractLocationAvailability(intent)
            ?.takeIf { availability -> !availability.isLocationAvailable }
            ?.let {
                updateNotification(
                    getString(R.string.navigation_active),
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
        NavigationSessionStore.finishSession()
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
            getString(R.string.navigation_channel_name),
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
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNavigationLocationRequest(): LocationRequest {
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
        private const val CHANNEL_ID = "openroute_navigation"
        private const val NOTIFICATION_ID = 17
        private const val LOCATION_UPDATES_REQUEST_CODE = 1701
        private const val LOCATION_UPDATE_INTERVAL_MS = 1_500L
        private const val MIN_LOCATION_UPDATE_INTERVAL_MS = 750L
        private const val MAX_LOCATION_UPDATE_DELAY_MS = 3_000L
        private const val LOCATION_UPDATE_DISTANCE_METERS = 2f
        private const val ACTION_START = "com.openroute.app.action.START_NAVIGATION"
        private const val ACTION_STOP = "com.openroute.app.action.STOP_NAVIGATION"
        private const val ACTION_LOCATION_UPDATE = "com.openroute.app.action.NAVIGATION_LOCATION_UPDATE"

        fun start(context: Context) {
            val intent = Intent(context, NavigationService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NavigationService::class.java).setAction(ACTION_STOP)
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
