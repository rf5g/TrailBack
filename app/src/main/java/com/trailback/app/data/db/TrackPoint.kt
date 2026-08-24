package com.trailback.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Промежуточная точка активного трека.
 *
 * Хранится, пока запись активна (без ограничения по времени).
 * При явном нажатии "Старт" заново — точки предыдущего трека удаляются.
 * Если запись была прервана крашем/убийством процесса — точки хранятся
 * не дольше 72 часов с момента последнего апдейта (см. entryPointIdForRecovery),
 * после чего удаляются как устаревшие.
 */
@Entity(tableName = "track_points")
data class TrackPoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val entryPointId: Long,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val accuracyMeters: Float
)
