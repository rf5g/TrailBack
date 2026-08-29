package com.trailback.app.ui.compass

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.trailback.app.R
import com.trailback.app.TrailBackApp
import com.trailback.app.data.db.EntryPoint
import com.trailback.app.data.repository.NorthMode
import com.trailback.app.data.repository.TrackingMode
import com.trailback.app.databinding.ActivityCompassBinding
import com.trailback.app.service.TrackingService
import com.trailback.app.util.DistanceFormatter
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class CompassActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompassBinding
    private lateinit var sensorManager: CompassSensorManager
    private var trackingService: TrackingService? = null
    private var isServiceBound = false

    // Кэшируется один раз при входе в режим "Домой", чтобы пересчёт азимута
    // на каждый тик компаса не требовал обращения к БД.
    private var homeEntryPoint: EntryPoint? = null
    private var lastLocation: Location? = null
    private var currentHeadingDegrees: Float = 0f
    private var locationObserverJob: kotlinx.coroutines.Job? = null

    // Повторное EMA-сглаживание ИТОГОВОГО угла стрелки (азимут минус курс).
    // Нужно отдельно от сглаживания курса в CompassSensorManager: GPS-азимут
    // на точку старта приходит рывками (координаты "прилетают" пакетами), и
    // разностный угол (bearing - heading) может заново давать скачок через
    // 0°/360°, даже если сам курс телефона уже сглажен.
    private var smoothedArrowSin: Float? = null
    private var smoothedArrowCos: Float? = null

    /**
     * Панель с метрами и спутниками должна дублироваться на экране компаса
     * (см. решение по ТЗ) — раньше layout её подключал, но данные в неё
     * никогда не писались, отсюда пустые значения. Логика ниже — та же, что
     * в MapActivity, только без привязки к карте.
     */
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var gpsCount = 0
            var glonassCount = 0
            for (i in 0 until status.satelliteCount) {
                when (status.getConstellationType(i)) {
                    GnssStatus.CONSTELLATION_GPS -> gpsCount++
                    GnssStatus.CONSTELLATION_GLONASS -> glonassCount++
                }
            }
            binding.topInfoPanel.satellitesText.text =
                getString(R.string.satellites_label, gpsCount, glonassCount)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as TrackingService.LocalBinder).getService()
            trackingService = service
            observeLocation(service)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            trackingService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompassBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as TrailBackApp
        val isReturning = app.trackingStateStore.mode == TrackingMode.RETURNING
        binding.compassView.mode = if (isReturning) CompassView.Mode.RETURNING else CompassView.Mode.NORMAL

        if (isReturning) {
            lifecycleScope.launch {
                homeEntryPoint = app.trackingRepository.getActiveEntryPoint()
                recomputeArrow()
            }
        }

        sensorManager = CompassSensorManager(this) { heading ->
            currentHeadingDegrees = heading
            binding.compassView.headingDegrees = heading
            // Пересчитываем на каждый тик компаса (не только на редкие
            // location-апдейты), чтобы стрелка визуально реагировала мгновенно.
            recomputeArrow()
        }
        sensorManager.northMode = app.settingsStore.northMode

        updateRouteCounter()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, TrackingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isServiceBound = true
        }
    }

    override fun onStop() {
        locationObserverJob?.cancel()
        locationObserverJob = null
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        sensorManager.start()
        registerGnssStatusCallback()
        // Страховка: пересчитываем склонение по последней известной позиции
        // сразу при возобновлении, не дожидаясь следующего GPS-тика.
        lastLocation?.let { sensorManager.updateLocationForDeclination(it) }
        updateRouteCounter()
    }

    override fun onPause() {
        sensorManager.stop()
        unregisterGnssStatusCallback()
        smoothedArrowSin = null
        smoothedArrowCos = null
        super.onPause()
    }

    private fun registerGnssStatusCallback() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.registerGnssStatusCallback(gnssStatusCallback, null)
    }

    private fun unregisterGnssStatusCallback() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
    }

    /**
     * КРИТИЧНО: отменяем предыдущую подписку перед созданием новой.
     * onServiceConnected() срабатывает заново при каждом bindService() —
     * то есть при каждом onStart() (в т.ч. после сворачивания приложения).
     * Без отмены старой корутины на каждый цикл сворачивания/разворачивания
     * накапливалась ещё одна параллельная подписка на тот же поток
     * геопозиции — несколько корутин одновременно писали в общие поля
     * сглаживания (smoothedArrowSin/Cos), что и приводило к странному,
     * трудновоспроизводимому поведению стрелки после сворачивания.
     */
    private fun observeLocation(service: TrackingService) {
        locationObserverJob?.cancel()
        locationObserverJob = lifecycleScope.launch {
            service.currentLocation.collect { location ->
                if (location != null) {
                    lastLocation = location
                    sensorManager.updateLocationForDeclination(location)
                    recomputeArrow()
                    updateDistanceToHome(location)
                    updateRouteCounter()
                }
            }
        }
    }

    /** Счётчик пути — то же значение, что и на карте, читается напрямую из stateStore. */
    private fun updateRouteCounter() {
        val app = application as TrailBackApp
        binding.topInfoPanel.distanceTraveledText.text =
            DistanceFormatter.format(app.trackingStateStore.distanceMeters)
    }

    /** Дистанция до точки старта — актуальна только в режиме "Домой". */
    private fun updateDistanceToHome(current: Location) {
        val entryPoint = homeEntryPoint
        if (binding.compassView.mode != CompassView.Mode.RETURNING || entryPoint == null) {
            binding.topInfoPanel.distanceToHomeText.text = ""
            return
        }
        val distanceResult = FloatArray(1)
        Location.distanceBetween(
            current.latitude, current.longitude,
            entryPoint.latitude, entryPoint.longitude,
            distanceResult
        )
        binding.topInfoPanel.distanceToHomeText.text = DistanceFormatter.format(distanceResult[0])
    }

    /**
     * Считает финальный угол стрелки: азимут на точку старта (в системе
     * отсчёта, соответствующей текущему режиму компаса) минус курс телефона,
     * затем повторно сглаживает результат через sin/cos, чтобы убрать рывки
     * от GPS-джиттера и переход через 0°/360° уже в разностном угле.
     */
    private fun recomputeArrow() {
        if (binding.compassView.mode != CompassView.Mode.RETURNING) return
        val current = lastLocation ?: return
        val entryPoint = homeEntryPoint ?: return

        val trueBearing = current.bearingTo(
            Location("home").apply {
                latitude = entryPoint.latitude
                longitude = entryPoint.longitude
            }
        )
        // Location.bearingTo() всегда относительно истинного севера. Если
        // циферблат сейчас в режиме "магнитный север", приводим азимут к той
        // же системе отсчёта — иначе стрелка и циферблат рассинхронизированы
        // на величину магнитного склонения.
        val app = application as TrailBackApp
        val adjustedBearing = if (app.settingsStore.northMode == NorthMode.MAGNETIC) {
            trueBearing - sensorManager.currentDeclination
        } else {
            trueBearing
        }
        val bearingDeg = (adjustedBearing + 360f) % 360f

        val rawArrowAngleDeg = (bearingDeg - currentHeadingDegrees + 360f) % 360f
        val rawArrowAngleRad = Math.toRadians(rawArrowAngleDeg.toDouble())

        val rawSin = sin(rawArrowAngleRad).toFloat()
        val rawCos = cos(rawArrowAngleRad).toFloat()

        val previousSin = smoothedArrowSin
        val previousCos = smoothedArrowCos
        val newSin: Float
        val newCos: Float
        if (previousSin == null || previousCos == null) {
            newSin = rawSin
            newCos = rawCos
        } else {
            newSin = EMA_OLD_WEIGHT * previousSin + EMA_NEW_WEIGHT * rawSin
            newCos = EMA_OLD_WEIGHT * previousCos + EMA_NEW_WEIGHT * rawCos
        }
        smoothedArrowSin = newSin
        smoothedArrowCos = newCos

        val smoothedArrowRad = atan2(newSin, newCos)
        var smoothedArrowDeg = Math.toDegrees(smoothedArrowRad.toDouble()).toFloat()
        smoothedArrowDeg = (smoothedArrowDeg + 360f) % 360f

        binding.compassView.arrowScreenAngleDegrees = smoothedArrowDeg
    }

    companion object {
        // Те же веса, что и для сглаживания курса в CompassSensorManager —
        // единообразное поведение для обоих слоёв сглаживания.
        private const val EMA_OLD_WEIGHT = 0.92f
        private const val EMA_NEW_WEIGHT = 0.08f
    }
}
