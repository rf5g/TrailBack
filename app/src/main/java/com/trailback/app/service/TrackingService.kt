package com.trailback.app.service

import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.trailback.app.R
import com.trailback.app.TrailBackApp
import com.trailback.app.data.repository.TrackingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Использует связку двух источников геоданных (см. решение по ТЗ):
 * - FusedLocationProviderClient — координаты и точность (использует и GPS,
 *   и ГЛОНАСС на уровне Android/чипсета, без раздельного отображения в UI);
 * - частоты обновления берутся из п.7.4: при записи — каждые 5с/5м (что чаще),
 *   в режиме "Домой" — каждые 10с, для экономии батареи.
 */
class TrackingService : LifecycleService() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var notificationHelper: NotificationHelper
    private val arrivalDetector = HomeArrivalDetector()

    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _arrivedHomeEvent = MutableStateFlow(false)
    val arrivedHomeEvent: StateFlow<Boolean> = _arrivedHomeEvent.asStateFlow()

    private val binder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            handleNewLocation(location)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationHelper = NotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = application as TrailBackApp
        val mode = app.trackingStateStore.mode

        val notification = notificationHelper.buildForegroundNotification(
            contentText = statusTextForMode(mode)
        )
        ServiceCompat.startForeground(
            this,
            NotificationHelper.FOREGROUND_NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        startLocationUpdates(mode)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun updateMode(mode: TrackingMode) {
        if (mode == TrackingMode.RETURNING) {
            arrivalDetector.reset()
        }
        notificationHelper.updateForegroundStatus(statusTextForMode(mode))
        startLocationUpdates(mode)
    }

    private fun statusTextForMode(mode: TrackingMode): String = when (mode) {
        TrackingMode.RECORDING -> getString(R.string.tracking_notification_recording)
        TrackingMode.RETURNING -> getString(R.string.tracking_notification_returning)
        TrackingMode.STOPPED, TrackingMode.IDLE -> getString(R.string.tracking_notification_idle)
    }

    fun triggerManualArrivalCheck() {
        arrivalDetector.onManualTrigger(System.currentTimeMillis())
        _arrivedHomeEvent.value = true
    }

    fun onArrivalDialogDismissed(confirmed: Boolean) {
        _arrivedHomeEvent.value = false
        if (!confirmed) {
            arrivalDetector.onDialogDismissed(System.currentTimeMillis())
        }
    }

    /**
     * Экономия батареи (см. решение по ТЗ): в режимах IDLE/STOPPED нет ни
     * активного маршрута, ни активного возврата — фоновый опрос GPS с
     * PRIORITY_HIGH_ACCURACY каждые 10 сек не даёт функциональной пользы
     * (handleNewLocation для этих режимов и так ничего не делает с данными),
     * а расходует батарею круглосуточно, даже когда приложение не
     * используется. В этих режимах сервис вообще не запрашивает геопозицию.
     * Свежую позицию для карты и кнопки "Старт" вместо этого запрашивает
     * сама MapActivity — лёгким запросом, активным только пока экран
     * открыт и приложение на переднем плане (см. MapActivity).
     */
    private fun startLocationUpdates(mode: TrackingMode) {
        fusedLocationClient.removeLocationUpdates(locationCallback)

        if (mode == TrackingMode.IDLE || mode == TrackingMode.STOPPED) {
            return
        }

        val intervalMillis = if (mode == TrackingMode.RECORDING) {
            RECORDING_INTERVAL_MILLIS
        } else {
            RETURNING_INTERVAL_MILLIS
        }
        val minDistanceMeters = if (mode == TrackingMode.RECORDING) RECORDING_MIN_DISTANCE_METERS else 0f

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateDistanceMeters(minDistanceMeters)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (e: SecurityException) {
            // Разрешение на геолокацию не выдано — Activity должна была
            // запросить его до запуска сервиса; здесь просто не запускаем обновления.
        }
    }

    private fun handleNewLocation(location: Location) {
        _currentLocation.value = location
        val app = application as TrailBackApp
        val stateStore = app.trackingStateStore

        lifecycleScope.launch {
            when (stateStore.mode) {
                TrackingMode.RECORDING -> {
                    app.trackingRepository.appendTrackPoint(
                        location.latitude, location.longitude, location.accuracy
                    )
                }
                TrackingMode.RETURNING -> {
                    val entryPoint = app.trackingRepository.getActiveEntryPoint() ?: return@launch
                    val shouldPrompt = arrivalDetector.onLocationUpdate(
                        location, entryPoint.latitude, entryPoint.longitude,
                        System.currentTimeMillis()
                    )
                    if (shouldPrompt) {
                        _arrivedHomeEvent.value = true
                    }
                }
                TrackingMode.STOPPED, TrackingMode.IDLE -> Unit
            }
        }
    }

    override fun onDestroy() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    companion object {
        // п.7.4: при записи — каждые 5с или при смещении на 5м (что чаще)
        const val RECORDING_INTERVAL_MILLIS = 5_000L
        const val RECORDING_MIN_DISTANCE_METERS = 5f

        // п.7.4: в режиме "Домой" — каждые 10с, для экономии батареи
        const val RETURNING_INTERVAL_MILLIS = 10_000L
    }
}
