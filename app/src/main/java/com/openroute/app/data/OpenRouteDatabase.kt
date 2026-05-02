package com.openroute.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RouteEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class OpenRouteDatabase : RoomDatabase() {
    abstract fun routeDao(): RouteDao

    companion object {
        @Volatile
        private var instance: OpenRouteDatabase? = null

        fun get(context: Context): OpenRouteDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OpenRouteDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { database ->
                    instance = database
                }
            }
        }

        private const val DATABASE_NAME = "openroute.db"
    }
}
