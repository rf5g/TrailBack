package com.trailback.app.data.repository
import android.content.Context
import androidx.core.content.edit
enum class TrackingMode {
    IDLE,       // кнопка "Старт" — трекинг не активен
    RECORDING,  // кнопка "Домой" — идёт запись трека
    RETURNING   // кнопка "Я на месте" — режим возврата активен
}
/**
 * Переживает смерть процесса: используется для восстановления состояния
 * (режим "Старт"/"Домой") при крашах, не позднее 72 часов с последнего апдейта.
 */
class TrackingStateStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("tracking_state", Context.MODE_PRIVATE)
    var mode: TrackingMode
        get() = TrackingMode.valueOf(prefs.getString(KEY_MODE, TrackingMode.IDLE.name)!!)
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }
    var activeEntryPointId: Long
        get() = prefs.getLong(KEY_ENTRY_POINT_ID, -1L)
        set(value) = prefs.edit { putLong(KEY_ENTRY_POINT_ID, value) }
    /** Обновляется при каждой записанной точке — база для окна восстановления 72 ч. */
    var lastUpdateTimestamp: Long
        get() = prefs.getLong(KEY_LAST_UPDATE, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_UPDATE, value) }
    /** Дистанция текущего маршрута в метрах; сбрасывается на 0 при каждом "Старт". */
    var distanceMeters: Float
        get() = prefs.getFloat(KEY_DISTANCE, 0f)
        set(value) = prefs.edit { putFloat(KEY_DISTANCE, value) }
    var lastLatitude: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_LAT, 0L))
        set(value) = prefs.edit { putLong(KEY_LAST_LAT, java.lang.Double.doubleToLongBits(value)) }
    var lastLongitude: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_LON, 0L))
        set(value) = prefs.edit { putLong(KEY_LAST_LON, java.lang.Double.doubleToLongBits(value)) }
    fun reset() {
        prefs.edit {
            putString(KEY_MODE, TrackingMode.IDLE.name)
            putLong(KEY_ENTRY_POINT_ID, -1L)
            putFloat(KEY_DISTANCE, 0f)
            putLong(KEY_LAST_UPDATE, 0L)
        }
    }
    // === НОВОЕ: "взятие направления" ===
    // Независимое от TrackingMode состояние (см. решение по ТЗ) — может быть
    // активно одновременно с RECORDING или IDLE. При входе в RETURNING
    // (кнопка "Домой") принудительно отменяется — см. TrackingRepository.
    // Не участвует в 72-часовой логике восстановления трека — это не
    // маршрут, а разовая точка, поэтому отдельного окна давности не нужно.
    var navigationTargetActive: Boolean
        get() = prefs.getBoolean(KEY_NAV_TARGET_ACTIVE, false)
        set(value) = prefs.edit { putBoolean(KEY_NAV_TARGET_ACTIVE, value) }
    var navigationTargetLatitude: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_NAV_TARGET_LAT, 0L))
        set(value) = prefs.edit { putLong(KEY_NAV_TARGET_LAT, java.lang.Double.doubleToLongBits(value)) }
    var navigationTargetLongitude: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_NAV_TARGET_LON, 0L))
        set(value) = prefs.edit { putLong(KEY_NAV_TARGET_LON, java.lang.Double.doubleToLongBits(value)) }
    fun clearNavigationTarget() {
        prefs.edit { putBoolean(KEY_NAV_TARGET_ACTIVE, false) }
    }
    /**
     * true, если есть незавершённый трек в допустимом окне восстановления (72 ч).
     * Восстанавливаются только состояния RECORDING (идёт запись) и RETURNING
     * (режим "Домой").
     */
    fun hasRecoverableTrack(nowMillis: Long): Boolean {
        if (mode != TrackingMode.RECORDING && mode != TrackingMode.RETURNING) return false
        if (activeEntryPointId < 0) return false
        val age = nowMillis - lastUpdateTimestamp
        return age in 0..RECOVERY_WINDOW_MILLIS
    }
    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_ENTRY_POINT_ID = "entry_point_id"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_DISTANCE = "distance_meters"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LON = "last_lon"
        // НОВОЕ: "взятие направления"
        private const val KEY_NAV_TARGET_ACTIVE = "nav_target_active"
        private const val KEY_NAV_TARGET_LAT = "nav_target_lat"
        private const val KEY_NAV_TARGET_LON = "nav_target_lon"
        const val RECOVERY_WINDOW_MILLIS = 72L * 60 * 60 * 1000 // 72 часа
    }
}
