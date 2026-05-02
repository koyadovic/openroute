package com.openroute.app.data

import android.content.Context
import com.openroute.app.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RouteRepository(context: Context) {
    private val appContext = context.applicationContext
    private val routeDao = OpenRouteDatabase.get(appContext).routeDao()
    private val legacyStorageFile = File(appContext.filesDir, LEGACY_STORAGE_FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    suspend fun loadRoutes(): List<RouteTrack> = withContext(Dispatchers.IO) {
        ensureLegacyMigration()
        routeDao.getAllRoutes().map { entity -> entity.toRouteTrack() }
    }

    suspend fun addRoute(route: RouteTrack): RouteTrack = withContext(Dispatchers.IO) {
        ensureLegacyMigration()
        routeDao.upsertRoute(route.toEntity())
        route
    }

    suspend fun addRoutes(routesToAdd: List<RouteTrack>): List<RouteTrack> = withContext(Dispatchers.IO) {
        if (routesToAdd.isEmpty()) {
            return@withContext emptyList()
        }

        ensureLegacyMigration()
        routeDao.upsertRoutes(routesToAdd.map { route -> route.toEntity() })
        routesToAdd.sortedByDescending(RouteTrack::createdAtMillis)
    }

    suspend fun hideRoute(routeId: String): RouteTrack? = withContext(Dispatchers.IO) {
        ensureLegacyMigration()
        val routeToHide = routeDao.getRoute(routeId)?.toRouteTrack() ?: return@withContext null
        routeDao.hideRoute(routeId)
        routeToHide.copy(isHidden = true)
    }

    suspend fun markRouteAsSeen(routeId: String): RouteTrack? = withContext(Dispatchers.IO) {
        ensureLegacyMigration()
        val routeToUpdate = routeDao.getRoute(routeId)?.toRouteTrack() ?: return@withContext null
        if (!routeToUpdate.isNew) {
            return@withContext routeToUpdate
        }

        routeDao.markRouteAsSeen(routeId)
        routeToUpdate.copy(isNew = false)
    }

    suspend fun renameRoute(routeId: String, name: String): RouteTrack? = withContext(Dispatchers.IO) {
        ensureLegacyMigration()
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            return@withContext null
        }

        val routeToUpdate = routeDao.getRoute(routeId)?.toRouteTrack() ?: return@withContext null
        val updatedRoute = routeToUpdate.copy(name = normalizedName)
        routeDao.renameRoute(routeId, normalizedName)
        updatedRoute
    }

    suspend fun deleteRoute(routeId: String): RouteTrack? = withContext(Dispatchers.IO) {
        ensureLegacyMigration()
        val routeToDelete = routeDao.getRoute(routeId)?.toRouteTrack() ?: return@withContext null
        routeDao.deleteRoute(routeId)
        routeToDelete
    }

    private suspend fun ensureLegacyMigration() {
        if (hasCheckedLegacyMigration) {
            return
        }

        legacyMigrationMutex.withLock {
            if (hasCheckedLegacyMigration) {
                return
            }

            migrateLegacyJsonIfNeeded()
            hasCheckedLegacyMigration = true
        }
    }

    private suspend fun migrateLegacyJsonIfNeeded() {
        if (!legacyStorageFile.exists()) {
            return
        }

        val stored = json.decodeFromString<StoredRoutes>(legacyStorageFile.readText())
        if (stored.routes.isNotEmpty()) {
            routeDao.upsertRoutes(stored.routes.map { route -> route.toEntity() })
        }

        if (!legacyStorageFile.delete() && legacyStorageFile.exists()) {
            error(appContext.getString(R.string.legacy_migration_delete_failed, legacyStorageFile.name))
        }
    }

    private fun RouteTrack.toEntity(): RouteEntity {
        return RouteEntity(
            id = id,
            name = name,
            source = source.name,
            createdAtMillis = createdAtMillis,
            distanceMeters = distanceMeters,
            durationMillis = durationMillis,
            pointsJson = json.encodeToString(points),
            isNew = isNew,
            isHidden = isHidden,
            importReference = importReference,
            originalFileName = originalFileName,
        )
    }

    private fun RouteEntity.toRouteTrack(): RouteTrack {
        return RouteTrack(
            id = id,
            name = name,
            source = RouteSource.valueOf(source),
            createdAtMillis = createdAtMillis,
            distanceMeters = distanceMeters,
            durationMillis = durationMillis,
            points = json.decodeFromString(pointsJson),
            isNew = isNew,
            isHidden = isHidden,
            importReference = importReference,
            originalFileName = originalFileName,
        )
    }

    private companion object {
        private const val LEGACY_STORAGE_FILE_NAME = "routes.json"
        private val legacyMigrationMutex = Mutex()

        @Volatile
        private var hasCheckedLegacyMigration = false
    }
}
