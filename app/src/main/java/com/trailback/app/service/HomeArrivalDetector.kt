package com.trailback.app.service
import android.location.Location
/**
 * Логика показа диалога "Вы вернулись!" (см. обсуждение ТЗ):
 * - автотриггер только при устойчивом нахождении в радиусе (несколько
 *   фиксов подряд) и хорошей точности GPS — один случайный скачок координат
 *   не засчитывается;
 * - плюс ручной триггер кнопкой "Я на месте" в любой момент;
 * - отказ пользователя ("Нет") не сбрасывает режим — просто закрывает диалог,
 *   с cooldown на повторный автопоказ.
 */
class HomeArrivalDetector(
    private val arrivalRadiusMeters: Float = DEFAULT_ARRIVAL_RADIUS_METERS,
    private val requiredConsecutiveFixes: Int = DEFAULT_REQUIRED_FIXES,
    private val maxAcceptableAccuracyMeters: Float = DEFAULT_MAX_ACCURACY,
    private val autoPromptCooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS
) {
    private var consecutiveFixesInRadius = 0
    private var lastPromptTimestamp = 0L
    private var wasOutsideRadiusSinceLastPrompt = true
    /**
     * Вызывается на каждый новый location fix в режиме "Домой".
     * @return true, если нужно автоматически показать диалог подтверждения.
     */
    fun onLocationUpdate(current: Location, homeLat: Double, homeLon: Double, now: Long): Boolean {
        val results = FloatArray(1)
        Location.distanceBetween(current.latitude, current.longitude, homeLat, homeLon, results)
        val distanceMeters = results[0]
        val inRadius = distanceMeters <= arrivalRadiusMeters
        val accuracyOk = !current.hasAccuracy() || current.accuracy <= maxAcceptableAccuracyMeters
        if (!inRadius) {
            consecutiveFixesInRadius = 0
            wasOutsideRadiusSinceLastPrompt = true
            return false
        }
        if (!accuracyOk) {
            // Точность плохая — не засчитываем фикс, но и не сбрасываем счётчик резко,
            // чтобы одиночный шумный фикс не откатывал уже накопленную серию.
            return false
        }
        consecutiveFixesInRadius++
        val enoughFixes = consecutiveFixesInRadius >= requiredConsecutiveFixes
        val cooldownPassed = (now - lastPromptTimestamp) >= autoPromptCooldownMillis
        val movedAwayAndBack = wasOutsideRadiusSinceLastPrompt
        if (enoughFixes && cooldownPassed && movedAwayAndBack) {
            lastPromptTimestamp = now
            wasOutsideRadiusSinceLastPrompt = false
            return true
        }
        return false
    }
    /** Вызывается при нажатии пользователем ручной кнопки "Я на месте". */
    fun onManualTrigger(now: Long) {
        lastPromptTimestamp = now
    }
    /** Вызывается, когда пользователь ответил "Нет" — просто фиксируем момент для cooldown. */
    fun onDialogDismissed(now: Long) {
        lastPromptTimestamp = now
    }
    fun reset() {
        consecutiveFixesInRadius = 0
        lastPromptTimestamp = 0L
        wasOutsideRadiusSinceLastPrompt = true
    }
    companion object {
        const val DEFAULT_ARRIVAL_RADIUS_METERS = 10f
        const val DEFAULT_REQUIRED_FIXES = 3
        const val DEFAULT_MAX_ACCURACY = 20f
        const val DEFAULT_COOLDOWN_MILLIS = 60_000L
    }
}
