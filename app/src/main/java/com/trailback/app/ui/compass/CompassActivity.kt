package com.trailback.app.ui.compass
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.Location
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.trailback.app.TrailBackApp
import com.trailback.app.data.db.EntryPoint
import com.trailback.app.data.repository.NorthMode
import com.trailback.app.data.repository.TrackingMode
import com.trailback.app.databinding.ActivityCompassBinding
import com.trailback.app.service.TrackingService
import com.trailback.app.ui.common.InfoPanelController
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
class CompassActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCompassBinding
    private lateinit var sensorManager: CompassSensorManager
    private lateinit var infoPanelController: InfoPanelController
    private var trackingService: TrackingService? = null
    private var isServiceBound = false
    // Кэшируется один раз при входе в режим "Домой", чтобы пересчёт азимута
    // на каждый тик компаса не требовал обращения к БД.
    private var homeEntryPoint: EntryPoint? = null
    private var lastLocation: Location? = null
    private var currentHeadingDegrees: Float = 0f
    private var locationObserverJob: kotlinx.coroutines.Job? = null
    // Повторное EMA-сглаживание ИТОГОВОГО угла стрелки (азимут минус курс).
    // Нужно отдельно от курса телефона (там фильтр убран — см. решение по
    // CompassSensorManager): GPS-азимут на точку старта приходит рывками
    // (координаты "прилетают" пакетами), и разностный угол (bearing -
    // heading) может заново давать скачок через 0°/360°, даже когда сам
    // курс телефона уже стабилен. Этот слой НЕ трогаем — он решает другую
    // задачу (сглаживание GPS-джиттера, а не шума датчика ориентации).
    private var smoothedArrowSin: Float? = null
    private var smoothedArrowCos: Float? = null
    // Взводится при каждом onResume — на первом валидном кадре после сна/
    // разблокировки стрелка на дом встаёт мгновенно, без "доплывания" из
    // замороженной за время паузы позиции.
    private var isFirstArrowFrame = true
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
        infoPanelController = InfoPanelController(this, binding.topInfoPanel)
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
        infoPanelController.updateRouteCounter()
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
        infoPanelController.start()
        isFirstArrowFrame = true // холодный старт стрелки на дом
        // Страховка: пересчитываем склонение по последней известной позиции
        // сразу при возобновлении, не дожидаясь следующего GPS-тика.
        lastLocation?.let { sensorManager.updateLocationForDeclination(it) }
        infoPanelController.updateRouteCounter()
    }
    override fun onPause() {
        sensorManager.stop()
        infoPanelController.stop()
        smoothedArrowSin = null
        smoothedArrowCos = null
        isFirstArrowFrame = true // сброс флага вместе с очисткой фильтра
        super.onPause()
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
                    val entryPointForDistance = homeEntryPoint.takeIf {
                        binding.compassView.mode == CompassView.Mode.RETURNING
                    }
                    infoPanelController.updateDistanceToDestination(location, entryPointForDistance)
                    infoPanelController.updateRouteCounter()
                }
            }
        }
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
        // Защита от NaN: bearingTo/currentDeclination могут дать NaN на
        // битых location-фиксах (нередко сразу после выхода из сна) — не
        // пускаем такие значения в фильтр, иначе он замерзает навсегда.
        if (trueBearing.isNaN() || currentHeadingDegrees.isNaN()) return
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
        if (adjustedBearing.isNaN()) return
        val bearingDeg = AzimuthNormalizer.normalize(adjustedBearing)
        val rawArrowAngleDeg = AzimuthNormalizer.normalize(bearingDeg - currentHeadingDegrees)
        val rawArrowAngleRad = Math.toRadians(rawArrowAngleDeg.toDouble())
        val rawSin = sin(rawArrowAngleRad).toFloat()
        val rawCos = cos(rawArrowAngleRad).toFloat()
        if (rawSin.isNaN() || rawCos.isNaN()) return
        val previousSin = smoothedArrowSin
        val previousCos = smoothedArrowCos
        val newSin: Float
        val newCos: Float
        if (isFirstArrowFrame || previousSin == null || previousCos == null) {
            // Холодный старт — мгновенная установка без сглаживания.
            newSin = rawSin
            newCos = rawCos
            isFirstArrowFrame = false
        } else {
            newSin = EMA_OLD_WEIGHT * previousSin + EMA_NEW_WEIGHT * rawSin
            newCos = EMA_OLD_WEIGHT * previousCos + EMA_NEW_WEIGHT * rawCos
        }
        smoothedArrowSin = newSin
        smoothedArrowCos = newCos
        val smoothedArrowRad = atan2(newSin, newCos)
        var smoothedArrowDeg = Math.toDegrees(smoothedArrowRad.toDouble()).toFloat()
        smoothedArrowDeg = AzimuthNormalizer.normalize(smoothedArrowDeg)
        binding.compassView.arrowScreenAngleDegrees = smoothedArrowDeg
    }
    companion object {
        // Веса EMA только для второго слоя (стрелка на точку старта) —
        // курс устройства теперь берётся из CompassSensorManager без фильтра.
        private const val EMA_OLD_WEIGHT = 0.92f
        private const val EMA_NEW_WEIGHT = 0.08f
    }
}
