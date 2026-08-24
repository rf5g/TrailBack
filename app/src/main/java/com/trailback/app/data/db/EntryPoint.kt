package com.trailback.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Стартовая точка маршрута.
 * Хранится постоянно, пока пользователь не выполнит массовую очистку
 * (с тройным подтверждением) в экране меню.
 */
@Entity(tableName = "entry_points")
data class EntryPoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val name: String
)
