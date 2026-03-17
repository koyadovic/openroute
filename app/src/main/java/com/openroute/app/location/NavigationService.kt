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

class NavigationService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var fusionRuntime: LocationFusionRuntime

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        fusionRuntime = LocationFusionRuntime(applicationContext) { update ->
            NavigationSessionStore.updateLocation(
                point = update.point,
                appendVisited = update.shouldAppendVisitedPoint,
            )

            if (update.source == FusedLocationSource.Gnss || update.shouldAppendVisitedPoint) {
                val navigationState = NavigationSessionStore.state.value
                val routeName = navigationState.route?.name ?: "Ruta"
                val progress = navigationState.progress

                updateNotification(
                    title = "Navegando $routeName",
                    text = if (progress != null) {
                        "${(progress.completionRatio * 100).toInt()}% · ${progress.remainingDistanceMeters.toDistanceLabel()} restantes"
                    } else {
                        "Esperando posicion…"
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
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationUpdates()
        fusionRuntime.stop()
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        fusionRuntime.onLocationChanged(location)
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

        startForeground(NOTIFICATION_ID, buildNotification("Navegacion activa", "Esperando posicion…"))
        fusionRuntime.reset()
        fusionRuntime.start()

        val providers = locationManager.preferredRouteProviders()
        if (providers.isEmpty()) {
            fusionRuntime.stop()
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
        stopLocationUpdates()
        fusionRuntime.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        NavigationSessionStore.finishSession()
        stopSelf()
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
            "OpenRoute navigation",
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

    companion object {
        private const val CHANNEL_ID = "openroute_navigation"
        private const val NOTIFICATION_ID = 17
        private const val LOCATION_UPDATE_INTERVAL_MS = 3_000L
        private const val LOCATION_UPDATE_DISTANCE_METERS = 5f
        private const val ACTION_START = "com.openroute.app.action.START_NAVIGATION"
        private const val ACTION_STOP = "com.openroute.app.action.STOP_NAVIGATION"

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
