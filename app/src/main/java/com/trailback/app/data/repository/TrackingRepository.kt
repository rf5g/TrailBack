package com.trailback.app.data.repository
import com.trailback.app.data.db.EntryPoint
import com.trailback.app.data.db.EntryPointDao
import com.trailback.app.data.db.TrackPoint
import com.trailback.app.data.db.TrackPointDao
import kotlinx.coroutines.flow.Flow
/**
 * Инкапсулирует логику п.7 ТЗ: точки входа хранятся всегда, точки трека —
 * только для текущего маршрута (или до 72 ч при восстановлении после краша).
 */
class TrackingRepository(
    private val entryPointDao: EntryPointDao,
    private val trackPointDao: TrackPointDao,
    private val stateStore: TrackingStateStore
) {
    fun observeEntryPoints(): Flow<List<EntryPoint>> = entryPointDao.observeAll()
    fun observeTrackForEntryPoint(entryPointId: Long): Flow<List<TrackPoint>> =
        trackPointDao.observeForEntryPoint(entryPointId)
    /**
     * Нажатие "Старт": удаляет точки предыдущего трека (старые маршруты
     * не хранятся), создаёт новую точку входа, сбрасывает счётчик дистанции.
     */
    suspend fun startNewRoute(latitude: Double, longitude: Double, name: String): Long {
        stateStore.activeEntryPointId.takeIf { it >= 0 }?.let { previousId ->
            trackPointDao.deleteForEntryPoint(previousId)
        }
        val entryPoint = EntryPoint(
            latitude = latitude,
            longitude = longitude,
            timestamp = System.currentTimeMillis(),
            name = name
        )
        val newId = entryPointDao.insert(entryPoint)
        stateStore.activeEntryPointId = newId
        stateStore.distanceMeters = 0f
        stateStore.mode = TrackingMode.RECORDING
        stateStore.lastUpdateTimestamp = System.currentTimeMillis()
        stateStore.lastLatitude = latitude
        stateStore.lastLongitude = longitude
        return newId
    }
    /** Добавляет точку трека и обновляет накопленную дистанцию (счётчик пути). */
    suspend fun appendTrackPoint(latitude: Double, longitude: Double, accuracyMeters: Float) {
        val entryPointId = stateStore.activeEntryPointId
        if (entryPointId < 0) return
        val deltaMeters = distanceBetween(
            stateStore.lastLatitude, stateStore.lastLongitude,
            latitude, longitude
        )
        stateStore.distanceMeters += deltaMeters
        stateStore.lastLatitude = latitude
        stateStore.lastLongitude = longitude
        stateStore.lastUpdateTimestamp = System.currentTimeMillis()
        trackPointDao.insert(
            TrackPoint(
                entryPointId = entryPointId,
                latitude = latitude,
                longitude = longitude,
                timestamp = System.currentTimeMillis(),
                accuracyMeters = accuracyMeters
            )
        )
    }
    suspend fun enterReturningMode() {
        stateStore.mode = TrackingMode.RETURNING
        stateStore.lastUpdateTimestamp = System.currentTimeMillis()
    }
    /** Подтверждено "Вы вернулись!" — полный сброс в исходное состояние. */
    suspend fun confirmArrivedHome() {
        val entryPointId = stateStore.activeEntryPointId
        if (entryPointId >= 0) {
            trackPointDao.deleteForEntryPoint(entryPointId)
        }
        stateStore.reset()
    }
    /**
     * Пользователь отказался восстанавливать трек после краша (диалог
     * "продолжить/отменить"). Точка входа остаётся в истории (постоянное
     * хранение по п.7.1), но точки самого трека удаляются, состояние — в IDLE.
     */
    suspend fun cancelRecovery() {
        val entryPointId = stateStore.activeEntryPointId
        if (entryPointId >= 0) {
            trackPointDao.deleteForEntryPoint(entryPointId)
        }
        stateStore.reset()
    }
    suspend fun getActiveEntryPoint(): EntryPoint? {
        val id = stateStore.activeEntryPointId
        return if (id >= 0) entryPointDao.getById(id) else null
    }
    /**
     * Выбор другой сохранённой точки входа как активной цели навигации
     * (см. решение по ТЗ — доступно только вне режима "Домой").
     */
    suspend fun selectActiveEntryPoint(entryPointId: Long) {
        stateStore.activeEntryPointId = entryPointId
    }
    /**
     * Удаляет точки трека, оставшиеся от предыдущих сессий (не относящиеся
     * к текущей активной точке входа) — это осиротевшие данные после краша,
     * которые не были подчищены штатным deleteForEntryPoint при новом "Старт".
     * Точки АКТИВНОЙ записи не трогает независимо от их возраста.
     */
    suspend fun purgeOrphanedTrackPoints() {
        val activeId = stateStore.activeEntryPointId
        if (activeId < 0) {
            trackPointDao.deleteAll()
        } else {
            trackPointDao.deleteAllExceptEntryPoint(activeId)
        }
    }
    /**
     * Если после краша прошло больше 72 часов (см. RECOVERY_WINDOW_MILLIS),
     * трек считается брошенным: точки удаляются, состояние сбрасывается в IDLE.
     * Вызывается при старте приложения, до показа диалога восстановления.
     * @return true, если трек был признан брошенным и сброшен.
     */
    suspend fun expireStaleSessionIfNeeded(nowMillis: Long): Boolean {
        val mode = stateStore.mode
        if (mode != TrackingMode.RECORDING && mode != TrackingMode.RETURNING) return false
        if (stateStore.hasRecoverableTrack(nowMillis)) return false
        val entryPointId = stateStore.activeEntryPointId
        if (entryPointId >= 0) {
            trackPointDao.deleteForEntryPoint(entryPointId)
        }
        stateStore.reset()
        return true
    }
    suspend fun clearAllEntryPoints() {
        entryPointDao.deleteAll()
    }
    private fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        if (lat1 == 0.0 && lon1 == 0.0) return 0f
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }
}
