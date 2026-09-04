package com.trailback.app.ui.compass
import android.app.Activity
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.view.Surface
import com.trailback.app.data.repository.NorthMode

/**
 * TYPE_ROTATION_VECTOR уже даёт очищенный, стабильный вектор поворота
 * (Android сам сливает акселерометр/магнетометр/гироскоп) — поэтому
 * собственный EMA-фильтр здесь больше не нужен и был УБРАН: наложение
 * фильтра поверх уже сглаженных системой данных давало лаг и накопление
 * ошибки на переходе через 0°/360°, из-за чего компас мог указывать
 * неверное направление при быстром повороте телефона.
 *
 * Азимут считается классической связкой:
 * getRotationMatrixFromVector -> remapCoordinateSystem (под поворот экрана)
 * -> getOrientation -> нормализация в [0, 360).
 */
class CompassSensorManager(
    private val context: Context,
    private val onHeadingChanged: (headingDegrees: Float) -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var magneticDeclination = 0f
    var northMode: NorthMode = NorthMode.TRUE

    /** Нужно снаружи (CompassActivity) для приведения GPS-азимута к той же системе отсчёта. */
    val currentDeclination: Float
        get() = magneticDeclination

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
        sensorManager.registerListener(this, rotationVectorSensor, SENSOR_DELAY_MICROS)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        // Защита от битого/пустого пакета (те же случаи, что были и раньше:
        // отдельные чипсеты присылают NaN на первом кадре после сна).
        if (event.values.isEmpty() || event.values.any { it.isNaN() }) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        val (newX, newY) = remapAxesForScreenRotation(currentScreenRotation())
        val remapOk = SensorManager.remapCoordinateSystem(rotationMatrix, newX, newY, remappedMatrix)
        if (!remapOk) return

        SensorManager.getOrientation(remappedMatrix, orientation)
        val azimuthRad = orientation[0]
        if (azimuthRad.isNaN()) return

        val magneticHeadingDeg = AzimuthNormalizer.normalize(Math.toDegrees(azimuthRad.toDouble()).toFloat())
        val heading = if (northMode == NorthMode.TRUE) {
            AzimuthNormalizer.normalize(magneticHeadingDeg + magneticDeclination)
        } else {
            magneticHeadingDeg
        }
        onHeadingChanged(heading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION") // minSdk 24 — context.display доступен только с API 30
    private fun currentScreenRotation(): Int =
        (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0

    private fun remapAxesForScreenRotation(rotation: Int): Pair<Int, Int> = when (rotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }

    companion object {
        private const val SENSOR_DELAY_MICROS = 20_000
    }
}
