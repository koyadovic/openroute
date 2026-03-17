package com.openroute.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.openroute.app.data.LatLngPoint
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine

class LastKnownLocationReader(
    private val context: Context,
) {
    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    @SuppressLint("MissingPermission")
    suspend fun read(): LatLngPoint? {
        return readCurrentLocation() ?: readLastKnownLocation()
    }

    @SuppressLint("MissingPermission")
    private suspend fun readCurrentLocation(): LatLngPoint? {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine { continuation ->
                    val cancellationTokenSource = CancellationTokenSource()
                    continuation.invokeOnCancellation {
                        cancellationTokenSource.cancel()
                    }

                    fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        cancellationTokenSource.token,
                    ).addOnSuccessListener { location ->
                        if (continuation.isActive) {
                            continuation.resume(location?.toLatLngPoint())
                        }
                    }.addOnFailureListener {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readLastKnownLocation(): LatLngPoint? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (continuation.isActive) {
                        continuation.resume(location?.toLatLngPoint())
                    }
                }
                .addOnFailureListener {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
        }
    }
}

private fun Location.toLatLngPoint(): LatLngPoint {
    return LatLngPoint(
        latitude = latitude,
        longitude = longitude,
        timestampMillis = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
        bearingDegrees = bearing.takeIf { hasBearing() }?.toDouble(),
    )
}
