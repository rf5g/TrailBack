package com.trailback.app.data.db
import androidx.room.Entity
import androidx.room.PrimaryKey
/**
 * Место, отмеченное пользователем кнопкой быстрой отметки.
 * Название — произвольный текст, который вводит сам пользователь;
 * фиксированных категорий нет. Хранится постоянно до ручного удаления
 * (удаление возможно по одной записи — в отличие от точек входа).
 */
@Entity(tableName = "marked_places")
data class MarkedPlace(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)
