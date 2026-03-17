package com.openroute.app.location

import android.annotation.SuppressLint
import android.os.Build
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.openroute.app.data.LatLngPoint
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine

class LastKnownLocationReader(
    private val context: Context,
) {
    @SuppressLint("MissingPermission")
    suspend fun read(): LatLngPoint? {
        return readCurrentLocation() ?: readLastKnownLocation()
    }

    @SuppressLint("MissingPermission")
    private suspend fun readCurrentLocation(): LatLngPoint? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).firstOrNull { providerName ->
            runCatching { locationManager.isProviderEnabled(providerName) }.getOrDefault(false)
        } ?: return null

        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine { continuation ->
                    locationManager.getCurrentLocation(
                        provider,
                        null,
                        context.mainExecutor,
                    ) { location ->
                        if (continuation.isActive) {
                            continuation.resume(location?.toLatLngPoint())
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readLastKnownLocation(): LatLngPoint? = withContext(Dispatchers.IO) {
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return@withContext null
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull(Location::getTime)?.toLatLngPoint()
    }
}

private fun Location.toLatLngPoint(): LatLngPoint {
    return LatLngPoint(
        latitude = latitude,
        longitude = longitude,
        timestampMillis = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
    )
}
