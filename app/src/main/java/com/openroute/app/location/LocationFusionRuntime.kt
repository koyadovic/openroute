package com.openroute.app.location

import android.content.Context
import android.location.Location

internal class LocationFusionRuntime(
    context: Context,
    private val onUpdate: (FusedLocationUpdate) -> Unit,
) {
    private val engine = LocationFusionEngine()
    private val deviceMotionBridge = DeviceMotionBridge(
        context = context,
        onOrientationSample = engine::updateOrientation,
        onMotionSample = { sample ->
            engine.updateFromImu(sample)?.let(onUpdate)
        },
    )

    fun reset() {
        engine.reset()
    }

    fun start() {
        deviceMotionBridge.start()
    }

    fun stop() {
        deviceMotionBridge.stop()
        engine.reset()
    }

    fun onLocationChanged(location: Location) {
        engine.updateFromLocation(location.toLocationSample())?.let(onUpdate)
    }
}
