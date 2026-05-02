package com.openroute.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val source: String,
    val createdAtMillis: Long,
    val distanceMeters: Double,
    val durationMillis: Long?,
    val pointsJson: String,
    val isNew: Boolean,
    val isHidden: Boolean,
    val importReference: String?,
    val originalFileName: String?,
)
