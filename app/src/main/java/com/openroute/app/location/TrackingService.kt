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
import com.openroute.app.data.RouteRepository
import com.openroute.app.data.RouteSource
import com.openroute.app.data.RouteTrack
import com.openroute.app.data.distanceMeters
import com.openroute.app.data.durationMillis
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: RouteRepository
    private lateinit var fusionRuntime: LocationFusionRuntime
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(applicationContext)
    }
    private val locationUpdatesPendingIntent by lazy {
        PendingIntent.getService(
            this,
            LOCATION_UPDATES_REQUEST_CODE,
            Intent(this, TrackingService::class.java)
                .setAction(ACTION_LOCATION_UPDATE)
                .setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    override fun onCreate() {
        super.onCreate()
        repository = RouteRepository(applicationContext)
        fusionRuntime = LocationFusionRuntime(applicationContext) { update ->
            if (update.source == FusedLocationSource.Gnss) {
                TrackingSessionStore.addPoint(update.point)
            } else {
                TrackingSessionStore.updateCurrentLocation(update.point)
            }

            if (update.source == FusedLocationSource.Gnss || update.shouldAppendTrackPoint) {
                val trackedPoints = TrackingSessionStore.state.value.points.size
                updateNotification("Grabando ruta", "$trackedPoints puntos registrados")
            }
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> finalizeAndStop()
            ACTION_LOCATION_UPDATE -> handleLocationUpdateIntent(intent)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationUpdates()
        fusionRuntime.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (TrackingSessionStore.state.value.isRecording) {
            return
        }

        if (!hasFineLocationPermission()) {
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification("Grabando ruta", "Esperando posición…"))
        TrackingSessionStore.startSession()
        fusionRuntime.reset()
        fusionRuntime.start()

        fusedLocationClient
            .requestLocationUpdates(
                buildTrackingLocationRequest(),
                locationUpdatesPendingIntent,
            )
            .addOnFailureListener {
                updateNotification("Grabando ruta", "No se pudo activar la localización.")
                finalizeAndStop()
            }

        requestBootstrapLocation()
    }

    private fun handleLocationUpdateIntent(intent: Intent) {
        val locationResult = LocationResult.extractResult(intent)
        if (locationResult == null) {
            Log.d(TAG, "Tracking intent received without LocationResult payload")
        }
        locationResult
            ?.locations
            ?.sortedBy { location -> location.elapsedRealtimeNanos }
            ?.forEach(fusionRuntime::onLocationChanged)

        LocationAvailability.extractLocationAvailability(intent)
            ?.takeIf { availability -> !availability.isLocationAvailable }
            ?.let {
                Log.w(TAG, "Location availability reported as unavailable during tracking")
                updateNotification("Grabando ruta", "Se ha perdido señal temporalmente.")
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
        val session = TrackingSessionStore.finishSession()
        val points = session.points
        stopLocationUpdates()
        fusionRuntime.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (points.size < 2) {
            stopSelf()
            return
        }

        serviceScope.launch {
            val route = RouteTrack(
                id = UUID.randomUUID().toString(),
                name = "Ride ${System.currentTimeMillis()}",
                source = RouteSource.RECORDED,
                createdAtMillis = session.finishedAtMillis,
                distanceMeters = points.distanceMeters(),
                durationMillis = session.durationMillis ?: points.durationMillis(),
                points = points,
            )

            repository.addRoute(route)
            TrackingSessionStore.publishSavedRoute(route)
            stopSelf()
        }
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
            "OpenRoute tracking",
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
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildTrackingLocationRequest(): LocationRequest {
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
        private const val TAG = "OpenRouteTracking"
        private const val CHANNEL_ID = "openroute_tracking"
        private const val NOTIFICATION_ID = 7
        private const val LOCATION_UPDATES_REQUEST_CODE = 701
        private const val LOCATION_UPDATE_INTERVAL_MS = 2_000L
        private const val MIN_LOCATION_UPDATE_INTERVAL_MS = 1_000L
        private const val MAX_LOCATION_UPDATE_DELAY_MS = 4_000L
        private const val LOCATION_UPDATE_DISTANCE_METERS = 3f
        private const val ACTION_START = "com.openroute.app.action.START_TRACKING"
        private const val ACTION_STOP = "com.openroute.app.action.STOP_TRACKING"
        private const val ACTION_LOCATION_UPDATE = "com.openroute.app.action.TRACKING_LOCATION_UPDATE"

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
