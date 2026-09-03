package com.trailback.app.ui.compass
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import com.trailback.app.data.repository.NorthMode
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
/**
 * Использует системный виртуальный датчик TYPE_ROTATION_VECTOR вместо
 * ручного скрещивания акселерометра и магнетометра: Android сам объединяет
 * акселерометр, магнетометр и гироскоп (если есть) и отдаёт уже очищенный
 * от наклонов телефона вектор вращения — меньше шума на входе, чем при
 * самостоятельной сборке через getRotationMatrix(gravity, geomagnetic).
 *
 * Сглаживание — EMA (экспоненциальное скользящее среднее) отдельно по
 * синусу и косинусу азимута, а не по самому углу. Сглаживание угла напрямую
 * ломается на переходе 359°→0° (компас "прокручивается" через 180°, а не
 * идёт кратчайшим путём) — раскладка на sin/cos и последующая сборка через
 * atan2 гарантирует кратчайший путь поворота стрелки без рывков.
 */
class CompassSensorManager(
    context: Context,
    private val onHeadingChanged: (headingDegrees: Float) -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var smoothedSin: Float? = null
    private var smoothedCos: Float? = null
    private var magneticDeclination = 0f
    var northMode: NorthMode = NorthMode.TRUE

    /**
     * Взводится в true при каждом start() (холодный старт датчика после
     * onResume/разблокировки экрана). На первом валидном кадре фильтр НЕ
     * сглаживает, а жёстко присваивает сырые sin/cos — стрелка встаёт
     * мгновенно, без "доплывания" из позиции, замороженной во время сна.
     */
    private var isFirstFrame = true

    /**
     * Текущее магнитное склонение в градусах. Нужно снаружи, чтобы привести
     * азимут на точку старта (Location.bearingTo всегда относительно
     * ИСТИННОГО севера) к той же системе отсчёта, что и headingDegrees.
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

    /** Опрос строго в onResume/onPause (жизненный цикл Android) — экономия батареи. */
    fun start() {
        isFirstFrame = true
        sensorManager.registerListener(this, rotationVectorSensor, SENSOR_DELAY_MICROS)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        reset()
    }

    /**
     * Полный сброс математического состояния фильтра. Безопасно вызывать
     * из onPause()/onResume() вызывающей Activity — гарантирует, что после
     * возврата из сна/блокировки не останется "протухших" синусов/косинусов
     * или зависшего флага первого кадра.
     */
    fun reset() {
        smoothedSin = null
        smoothedCos = null
        isFirstFrame = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        // Защита от NaN/пустого пакета: битые данные от датчика (нередки
        // на первом кадре после выхода из сна на некоторых чипсетах) не
        // должны попадать в фильтр — иначе EMA необратимо загрязняется
        // NaN и стрелка "замерзает" до принудительного сброса.
        if (event.values.isEmpty() || event.values.any { it.isNaN() }) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        // Азимут в радианах, -π..π, относительно магнитного севера
        // (TYPE_ROTATION_VECTOR не корректирует склонение автоматически).
        val azimuthRad = orientation[0]
        if (azimuthRad.isNaN()) return

        val rawSin = sin(azimuthRad).toFloat()
        val rawCos = cos(azimuthRad).toFloat()
        if (rawSin.isNaN() || rawCos.isNaN()) return

        val previousSin = smoothedSin
        val previousCos = smoothedCos
        val newSmoothedSin: Float
        val newSmoothedCos: Float
        if (isFirstFrame || previousSin == null || previousCos == null) {
            // Холодный старт — без сглаживания, мгновенная установка стрелки.
            newSmoothedSin = rawSin
            newSmoothedCos = rawCos
            isFirstFrame = false
        } else {
            newSmoothedSin = EMA_OLD_WEIGHT * previousSin + EMA_NEW_WEIGHT * rawSin
            newSmoothedCos = EMA_OLD_WEIGHT * previousCos + EMA_NEW_WEIGHT * rawCos
        }
        smoothedSin = newSmoothedSin
        smoothedCos = newSmoothedCos
        // Собираем угол обратно через atan2 — гарантирует кратчайший путь
        // поворота даже в точке перехода через 0°/360°.
        val smoothedAzimuthRad = atan2(newSmoothedSin, newSmoothedCos)
        var magneticHeadingDeg = Math.toDegrees(smoothedAzimuthRad.toDouble()).toFloat()
        magneticHeadingDeg = (magneticHeadingDeg + 360f) % 360f
        val heading = if (northMode == NorthMode.TRUE) {
            (magneticHeadingDeg + magneticDeclination + 360f) % 360f
        } else {
            magneticHeadingDeg
        }
        onHeadingChanged(heading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        // ~50 Гц — быстрее старого 20 Гц, т.к. TYPE_ROTATION_VECTOR даёт уже
        // очищенные данные и не требует экономии на частоте опроса ради шумоподавления.
        private const val SENSOR_DELAY_MICROS = 20_000
        // EMA: 92% старого значения + 8% нового — плавное сглаживание без
        // видимого дрожания от шума датчика, но с достаточно быстрой реакцией
        // на реальный поворот телефона.
        private const val EMA_OLD_WEIGHT = 0.92f
        private const val EMA_NEW_WEIGHT = 0.08f
    }
}
