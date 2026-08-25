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
import com.trailback.app.data.repository.TrackingMode
import com.trailback.app.databinding.ActivityCompassBinding
import com.trailback.app.service.TrackingService
import kotlinx.coroutines.launch

class CompassActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompassBinding
    private lateinit var sensorManager: CompassSensorManager
    private var trackingService: TrackingService? = null
    private var isServiceBound = false

    // Кэшируется один раз при входе в режим "Домой", чтобы пересчёт азимута
    // на каждый тик компаса (20 Гц) не требовал обращения к БД — раньше
    // запрос к БД на каждый location-тик мог не успевать/сбоить, из-за чего
    // bearingToHomeDegrees оставался равен 0 и красная стрелка визуально
    // "прилипала" к циферблату (формула поворота совпадала с поворотом диска).
    private var homeEntryPoint: EntryPoint? = null
    private var lastLocation: Location? = null

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
                recomputeBearing()
            }
        }

        sensorManager = CompassSensorManager(this) { heading ->
            binding.compassView.headingDegrees = heading
            // Пересчитываем на каждый тик компаса (не только на редкие location-апдейты),
            // чтобы стрелка визуально реагировала мгновенно и не выглядела "залипшей".
            recomputeBearing()
        }
        sensorManager.northMode = app.settingsStore.northMode
    }

    override fun onStart() {
        super.onStart()
        Intent(this, TrackingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isServiceBound = true
        }
    }

    override fun onStop() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        sensorManager.start()
    }

    override fun onPause() {
        sensorManager.stop()
        super.onPause()
    }

    private fun observeLocation(service: TrackingService) {
        lifecycleScope.launch {
            service.currentLocation.collect { location ->
                if (location != null) {
                    lastLocation = location
                    sensorManager.updateLocationForDeclination(location)
                    recomputeBearing()
                }
            }
        }
    }

    /** Чистая геометрия, без обращений к БД — можно вызывать на каждый тик датчика. */
    private fun recomputeBearing() {
        if (binding.compassView.mode != CompassView.Mode.RETURNING) return
        val current = lastLocation ?: return
        val entryPoint = homeEntryPoint ?: return

        val bearing = current.bearingTo(
            Location("home").apply {
                latitude = entryPoint.latitude
                longitude = entryPoint.longitude
            }
        )
        binding.compassView.bearingToHomeDegrees = (bearing + 360f) % 360f
    }
}
