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
import com.trailback.app.data.repository.TrackingMode
import com.trailback.app.databinding.ActivityCompassBinding
import com.trailback.app.service.TrackingService
import kotlinx.coroutines.launch

class CompassActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompassBinding
    private lateinit var sensorManager: CompassSensorManager
    private var trackingService: TrackingService? = null
    private var isServiceBound = false

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

        binding.compassView.mode = if (app.trackingStateStore.mode == TrackingMode.RETURNING) {
            CompassView.Mode.RETURNING
        } else {
            CompassView.Mode.NORMAL
        }

        sensorManager = CompassSensorManager(this) { heading ->
            binding.compassView.headingDegrees = heading
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
                    sensorManager.updateLocationForDeclination(location)
                    updateBearingToHome(location)
                }
            }
        }
    }

    private fun updateBearingToHome(current: Location) {
        val app = application as TrailBackApp
        if (binding.compassView.mode != CompassView.Mode.RETURNING) return

        lifecycleScope.launch {
            val entryPoint = app.trackingRepository.getActiveEntryPoint() ?: return@launch
            val bearing = current.bearingTo(
                Location("home").apply {
                    latitude = entryPoint.latitude
                    longitude = entryPoint.longitude
                }
            )
            binding.compassView.bearingToHomeDegrees = (bearing + 360f) % 360f
        }
    }
}
