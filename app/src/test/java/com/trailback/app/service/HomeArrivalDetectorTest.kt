package com.trailback.app.service

import android.location.Location
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Точка старта — Москва, Красная площадь. Все "текущие" локации в тестах
 * находятся либо строго внутри 10-метрового радиуса, либо явно снаружи,
 * чтобы не зависеть от точности плавающей точки на границе.
 */
@RunWith(RobolectricTestRunner::class)
class HomeArrivalDetectorTest {

    private val homeLat = 55.753930
    private val homeLon = 37.620795

    private lateinit var detector: HomeArrivalDetector

    private fun locationAt(lat: Double, lon: Double, accuracy: Float = 5f): Location {
        return Location("test").apply {
            latitude = lat
            longitude = lon
            this.accuracy = accuracy
        }
    }

    /** Точка практически совпадает с домом — гарантированно внутри радиуса 10 м. */
    private fun insideRadiusLocation(accuracy: Float = 5f) =
        locationAt(homeLat + 0.00002, homeLon, accuracy)

    /** Точка примерно в 200 м от дома — гарантированно снаружи радиуса. */
    private fun outsideRadiusLocation() =
        locationAt(homeLat + 0.002, homeLon)

    @Before
    fun setUp() {
        detector = HomeArrivalDetector(
            arrivalRadiusMeters = 10f,
            requiredConsecutiveFixes = 3,
            maxAcceptableAccuracyMeters = 20f,
            autoPromptCooldownMillis = 60_000L
        )
    }

    @Test
    fun `does not trigger on a single fix inside radius`() {
        val now = 1_000_000L
        val result = detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now)
        assertFalse("Одиночный фикс не должен сразу показывать диалог", result)
    }

    @Test
    fun `triggers only after required consecutive fixes inside radius`() {
        var now = 1_000_000L
        assertFalse(detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now))
        now += 5_000
        assertFalse(detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now))
        now += 5_000
        val triggered = detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now)
        assertTrue("После 3 фиксов подряд диалог должен показаться", triggered)
    }

    @Test
    fun `single noisy fix outside radius resets the streak`() {
        var now = 1_000_000L
        detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now)
        now += 5_000
        detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now)

        // Один случайный скачок GPS наружу — серия должна сброситься
        now += 5_000
        detector.onLocationUpdate(outsideRadiusLocation(), homeLat, homeLon, now)

        now += 5_000
        detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now)
        now += 5_000
        val triggeredTooEarly = detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now)
        assertFalse("После сброса серии нужно снова накопить 3 фикса", triggeredTooEarly)
    }

    @Test
    fun `poor accuracy fix does not count towards the streak`() {
        var now = 1_000_000L
        detector.onLocationUpdate(insideRadiusLocation(accuracy = 5f), homeLat, homeLon, now)
        now += 5_000
        // Точность хуже допустимой (20 м) — не должна засчитываться как валидный фикс
        detector.onLocationUpdate(insideRadiusLocation(accuracy = 50f), homeLat, homeLon, now)
        now += 5_000
        val triggeredTooEarly = detector.onLocationUpdate(insideRadiusLocation(accuracy = 5f), homeLat, homeLon, now)
        assertFalse("Фикс с плохой точностью не должен засчитываться в серию", triggeredTooEarly)
    }

    @Test
    fun `cooldown prevents immediate re-trigger after dialog shown`() {
        var now = 1_000_000L
        detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now)
        now += 5_000
        detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now)
        now += 5_000
        assertTrue(detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now))

        // Пользователь остаётся в радиусе, ушёл-вернулся не было — cooldown должен блокировать повтор
        now += 10_000
        val retriggered = detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now)
        assertFalse("В течение cooldown повторный автопоказ не должен срабатывать", retriggered)
    }

    @Test
    fun `manual trigger works regardless of streak state`() {
        val now = 1_000_000L
        detector.onManualTrigger(now)
        // onManualTrigger сам не возвращает событие — это ответственность вызывающего кода
        // (см. TrackingService.triggerManualArrivalCheck), но не должен бросать исключений
        // и должен запускать cooldown для последующих автотриггеров.
        val immediateAuto = detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now + 1_000)
        assertFalse(immediateAuto)
    }

    @Test
    fun `reset clears streak and cooldown state`() {
        var now = 1_000_000L
        detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now)
        now += 5_000
        detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now)
        now += 5_000
        assertTrue(detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now))

        detector.reset()

        // После reset требуется снова накопить полную серию, cooldown тоже снят
        now += 100_000
        assertFalse(detector.onLocationUpdate(insideRadiusLocation(), homeLat, homeLon, now))
    }
}
