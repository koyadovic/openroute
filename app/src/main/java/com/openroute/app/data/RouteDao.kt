package com.openroute.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY createdAtMillis DESC")
    suspend fun getAllRoutes(): List<RouteEntity>

    @Query("SELECT * FROM routes WHERE id = :routeId LIMIT 1")
    suspend fun getRoute(routeId: String): RouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoute(route: RouteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoutes(routes: List<RouteEntity>)

    @Query("UPDATE routes SET isHidden = 1 WHERE id = :routeId")
    suspend fun hideRoute(routeId: String): Int

    @Query("UPDATE routes SET isNew = 0 WHERE id = :routeId")
    suspend fun markRouteAsSeen(routeId: String): Int

    @Query("UPDATE routes SET name = :name WHERE id = :routeId")
    suspend fun renameRoute(routeId: String, name: String): Int

    @Query("DELETE FROM routes WHERE id = :routeId")
    suspend fun deleteRoute(routeId: String): Int
}
