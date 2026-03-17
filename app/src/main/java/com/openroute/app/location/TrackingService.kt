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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openroute.app.MainActivity
import com.openroute.app.data.LatLngPoint
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

class TrackingService : Service(), LocationListener {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager
    private lateinit var repository: RouteRepository

    override fun onCreate() {
        super.onCreate()
        repository = RouteRepository(applicationContext)
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> finalizeAndStop()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationUpdates()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        val sample = location.toLocationSample()
        if (!LocationSamplePolicy.isUsable(sample)) {
            return
        }

        val point = LocationSamplePolicy.toPoint(sample)
        TrackingSessionStore.updateCurrentLocation(point)

        val previousPoint = TrackingSessionStore.state.value.points.lastOrNull()
        if (LocationSamplePolicy.shouldAppend(previousPoint, sample)) {
            TrackingSessionStore.addPoint(point)
        }

        val trackedPoints = TrackingSessionStore.state.value.points.size
        updateNotification("Grabando ruta", "$trackedPoints puntos registrados")
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

        val providers = locationManager.preferredRouteProviders()
        if (providers.isEmpty()) {
            stopSelf()
            return
        }

        providers.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                LOCATION_UPDATE_INTERVAL_MS,
                LOCATION_UPDATE_DISTANCE_METERS,
                this,
                Looper.getMainLooper(),
            )
        }

        locationManager.freshestLastKnownLocation(providers)?.let(::onLocationChanged)
    }

    private fun finalizeAndStop() {
        val session = TrackingSessionStore.finishSession()
        val points = session.points
        stopLocationUpdates()
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
        runCatching { locationManager.removeUpdates(this) }
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

    companion object {
        private const val CHANNEL_ID = "openroute_tracking"
        private const val NOTIFICATION_ID = 7
        private const val LOCATION_UPDATE_INTERVAL_MS = 3_000L
        private const val LOCATION_UPDATE_DISTANCE_METERS = 5f
        private const val ACTION_START = "com.openroute.app.action.START_TRACKING"
        private const val ACTION_STOP = "com.openroute.app.action.STOP_TRACKING"

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
