package com.trailback.app.ui.compass

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import com.trailback.app.data.repository.NorthMode

/**
 * SensorManager (магнитное поле + акселерометр), ~20 Гц (п.8 ТЗ).
 * Истинный север считается штатным Android API GeomagneticField —
 * отдельная реализация модели WMM не нужна.
 * Сглаживание курса — экспоненциальный фильтр, чтобы диск компаса
 * не дёргался от шума датчика (см. решение по компасу).
 */
class CompassSensorManager(
    context: Context,
    private val onHeadingChanged: (headingDegrees: Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var hasGravity = false
    private var hasGeomagnetic = false

    private var smoothedHeading: Float? = null
    private var magneticDeclination = 0f

    var northMode: NorthMode = NorthMode.TRUE

    /**
     * Текущее магнитное склонение в градусах. Нужно снаружи, чтобы привести
     * азимут на точку старта (Location.bearingTo всегда возвращает азимут
     * относительно ИСТИННОГО севера) к той же системе отсчёта, что и текущий
     * headingDegrees — иначе в магнитном режиме стрелка "домой" и циферблат
     * будут рассинхронизированы на величину склонения (см. решение по багу
     * "стрелка не указывает на место").
     */
    val currentDeclination: Float
        get() = magneticDeclination

    /** Вызывать при получении текущей позиции — нужно для расчёта магнитного склонения. */
    fun updateLocationForDeclination(location: Location) {
        val field = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis()
        )
        magneticDeclination = field.declination
    }

    fun start() {
        sensorManager.registerListener(this, accelerometer, SENSOR_DELAY_MICROS)
        sensorManager.registerListener(this, magnetometer, SENSOR_DELAY_MICROS)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
                hasGravity = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                hasGeomagnetic = true
            }
        }

        if (hasGravity && hasGeomagnetic) {
            val success = SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientation)
                var magneticHeading = Math.toDegrees(orientation[0].toDouble()).toFloat()
                magneticHeading = (magneticHeading + 360f) % 360f

                val heading = if (northMode == NorthMode.TRUE) {
                    (magneticHeading + magneticDeclination + 360f) % 360f
                } else {
                    magneticHeading
                }

                val smoothed = applySmoothing(heading)
                smoothedHeading = smoothed
                onHeadingChanged(smoothed)
            }
        }
    }

    /** Экспоненциальное сглаживание с корректной обработкой перехода через 0/360°. */
    private fun applySmoothing(newHeading: Float): Float {
        val previous = smoothedHeading ?: return newHeading

        var delta = newHeading - previous
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f

        return (previous + SMOOTHING_FACTOR * delta + 360f) % 360f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val SENSOR_DELAY_MICROS = 50_000 // ~20 Гц
        private const val SMOOTHING_FACTOR = 0.10f
    }
}
