package com.openroute.app.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RouteRepository(context: Context) {
    private val storageFile = File(context.filesDir, STORAGE_FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    suspend fun loadRoutes(): List<RouteTrack> = withContext(Dispatchers.IO) {
        readRoutesInternal()
    }

    suspend fun addRoute(route: RouteTrack): RouteTrack = withContext(Dispatchers.IO) {
        val routes = readRoutesInternal().filterNot { it.id == route.id }
        writeRoutesInternal(listOf(route) + routes)
        route
    }

    suspend fun addRoutes(routesToAdd: List<RouteTrack>): List<RouteTrack> = withContext(Dispatchers.IO) {
        if (routesToAdd.isEmpty()) {
            return@withContext emptyList()
        }

        val currentRoutes = readRoutesInternal()
        val incomingIds = routesToAdd.map(RouteTrack::id).toSet()
        val updatedRoutes = routesToAdd + currentRoutes.filterNot { it.id in incomingIds }

        writeRoutesInternal(updatedRoutes)
        routesToAdd.sortedByDescending(RouteTrack::createdAtMillis)
    }

    suspend fun hideRoute(routeId: String): RouteTrack? = withContext(Dispatchers.IO) {
        val currentRoutes = readRoutesInternal()
        val routeToHide = currentRoutes.firstOrNull { it.id == routeId } ?: return@withContext null
        val updatedRoutes = currentRoutes.map { route ->
            if (route.id == routeId) {
                route.copy(isHidden = true)
            } else {
                route
            }
        }

        writeRoutesInternal(updatedRoutes)
        routeToHide.copy(isHidden = true)
    }

    suspend fun markRouteAsSeen(routeId: String): RouteTrack? = withContext(Dispatchers.IO) {
        val currentRoutes = readRoutesInternal()
        val routeToUpdate = currentRoutes.firstOrNull { it.id == routeId } ?: return@withContext null
        if (!routeToUpdate.isNew) {
            return@withContext routeToUpdate
        }

        val updatedRoutes = currentRoutes.map { route ->
            if (route.id == routeId) {
                route.copy(isNew = false)
            } else {
                route
            }
        }

        writeRoutesInternal(updatedRoutes)
        routeToUpdate.copy(isNew = false)
    }

    suspend fun renameRoute(routeId: String, name: String): RouteTrack? = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            return@withContext null
        }

        val currentRoutes = readRoutesInternal()
        val routeToUpdate = currentRoutes.firstOrNull { it.id == routeId } ?: return@withContext null
        val updatedRoute = routeToUpdate.copy(name = normalizedName)
        val updatedRoutes = currentRoutes.map { route ->
            if (route.id == routeId) {
                updatedRoute
            } else {
                route
            }
        }

        writeRoutesInternal(updatedRoutes)
        updatedRoute
    }

    suspend fun deleteRoute(routeId: String): RouteTrack? = withContext(Dispatchers.IO) {
        val currentRoutes = readRoutesInternal()
        val routeToDelete = currentRoutes.firstOrNull { it.id == routeId } ?: return@withContext null
        val updatedRoutes = currentRoutes.filterNot { it.id == routeId }
        writeRoutesInternal(updatedRoutes)
        routeToDelete
    }

    private fun readRoutesInternal(): List<RouteTrack> {
        if (!storageFile.exists()) {
            return emptyList()
        }

        val stored = json.decodeFromString<StoredRoutes>(storageFile.readText())
        return stored.routes.sortedByDescending(RouteTrack::createdAtMillis)
    }

    private fun writeRoutesInternal(routes: List<RouteTrack>) {
        storageFile.writeText(json.encodeToString(StoredRoutes(routes)))
    }

    private companion object {
        private const val STORAGE_FILE_NAME = "routes.json"
    }
}
