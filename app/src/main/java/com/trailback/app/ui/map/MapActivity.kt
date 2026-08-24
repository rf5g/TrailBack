package com.trailback.app.ui.map

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.trailback.app.R
import com.trailback.app.TrailBackApp
import com.trailback.app.data.repository.TrackingMode
import com.trailback.app.databinding.ActivityMapBinding
import com.trailback.app.service.TrackingService
import com.trailback.app.ui.compass.CompassActivity
import com.trailback.app.ui.menu.MenuActivity
import kotlinx.coroutines.launch
import java.util.Locale

class MapActivity : AppCompatActivity() {

    companion object {
        const val ACTION_SHOW_ARRIVED_DIALOG = "com.trailback.app.SHOW_ARRIVED_DIALOG"
        const val LOW_ACCURACY_THRESHOLD_METERS = 50f
    }

    private lateinit var binding: ActivityMapBinding
    private lateinit var viewModel: MapViewModel
    private lateinit var mapController: MapController
    private var trackingService: TrackingService? = null
    private var isServiceBound = false
    private var lastKnownLocation: Location? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as TrackingService.LocalBinder).getService()
            trackingService = service
            observeService(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            trackingService = null
        }
    }

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            startTrackingServiceIfNeeded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as TrailBackApp
        viewModel = ViewModelProvider(this, MapViewModel.Factory(app))[MapViewModel::class.java]
        mapController = MapController(this, binding.mapContainer)
        mapController.setupMap(app.settingsStore.offlineMapsUri, hasInternetConnection())

        checkCrashRecoveryThenStart(app)
        setupButtons()
        setupMapControlButtons()
        observeViewModel()
        handleArrivalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleArrivalIntent(intent)
    }

    private fun handleArrivalIntent(intent: Intent?) {
        if (intent?.action == ACTION_SHOW_ARRIVED_DIALOG) {
            showArrivedDialog()
        }
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

    override fun onDestroy() {
        mapController.onDestroy()
        super.onDestroy()
    }

    private fun hasInternetConnection(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun observeService(service: TrackingService) {
        lifecycleScope.launch {
            service.currentLocation.collect { location ->
                if (location != null) {
                    lastKnownLocation = location
                    onLocationUpdated(location)
                }
            }
        }
        lifecycleScope.launch {
            service.arrivedHomeEvent.collect { shouldShow ->
                if (shouldShow) showArrivedDialog()
            }
        }
    }

    private fun onLocationUpdated(location: Location) {
        mapController.updateUserPositionMarker(location)
        val app = application as TrailBackApp
        val entryPoint = viewModel.activeEntryPoint.value
        mapController.updateHomeLine(location, entryPoint, viewModel.mode.value)

        if (entryPoint != null) {
            val distanceResult = FloatArray(1)
            Location.distanceBetween(
                location.latitude, location.longitude,
                entryPoint.latitude, entryPoint.longitude,
                distanceResult
            )
            binding.topInfoPanel.distanceToHomeText.text =
                getString(R.string.distance_to_home_label) + ": " + formatDistance(distanceResult[0])
        }
    }

    private fun setupMapControlButtons() {
        binding.zoomInButton.setOnClickListener {
            mapController.zoomIn()
            updateZoomButtonsState()
        }
        binding.zoomOutButton.setOnClickListener {
            mapController.zoomOut()
            updateZoomButtonsState()
        }
        binding.recenterButton.setOnClickListener { mapController.recenterOn(lastKnownLocation) }
        updateZoomButtonsState()
    }

    private fun updateZoomButtonsState() {
        binding.zoomInButton.isEnabled = mapController.canZoomIn()
        binding.zoomOutButton.isEnabled = mapController.canZoomOut()
        binding.zoomInButton.alpha = if (binding.zoomInButton.isEnabled) 1f else 0.4f
        binding.zoomOutButton.alpha = if (binding.zoomOutButton.isEnabled) 1f else 0.4f
    }

    private fun ensurePermissionsAndStartService() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted) {
            startTrackingServiceIfNeeded()
        } else {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestLocationPermission.launch(permissions.toTypedArray())
        }
    }

    private fun startTrackingServiceIfNeeded() {
        val intent = Intent(this, TrackingService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun checkCrashRecoveryThenStart(app: TrailBackApp) {
        val mode = app.trackingStateStore.mode
        val isActiveMode = mode == TrackingMode.RECORDING || mode == TrackingMode.RETURNING
        val serviceAlreadyRunning = isServiceRunning(TrackingService::class.java)

        // Диалог восстановления нужен, только если процесс/сервис реально
        // погибли (краш) — при обычном сворачивании foreground-сервис жив,
        // и повторно спрашивать пользователя не нужно.
        val needsRecoveryPrompt = isActiveMode &&
            !serviceAlreadyRunning &&
            app.trackingStateStore.hasRecoverableTrack(System.currentTimeMillis())

        if (needsRecoveryPrompt) {
            showRecoveryDialog(app)
        } else {
            ensurePermissionsAndStartService()
        }
    }

    private fun showRecoveryDialog(app: TrailBackApp) {
        AlertDialog.Builder(this)
            .setTitle(R.string.recovery_dialog_title)
            .setMessage(R.string.recovery_dialog_message)
            .setCancelable(false)
            .setPositiveButton(R.string.recovery_dialog_continue) { _, _ ->
                ensurePermissionsAndStartService()
            }
            .setNegativeButton(R.string.recovery_dialog_cancel) { _, _ ->
                lifecycleScope.launch {
                    app.trackingRepository.cancelRecovery()
                    viewModel.resetToIdleState()
                    ensurePermissionsAndStartService()
                }
            }
            .show()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceClass.name }
    }

    private fun setupButtons() {
        binding.menuButton.setOnClickListener {
            startActivity(Intent(this, MenuActivity::class.java))
        }

        binding.miniCompassView.setOnClickListener {
            startActivity(Intent(this, CompassActivity::class.java))
        }

        binding.mainActionButton.setOnClickListener {
            when (viewModel.mode.value) {
                TrackingMode.IDLE -> confirmStart()
                TrackingMode.RECORDING -> confirmStop()
                TrackingMode.STOPPED -> confirmHome()
                TrackingMode.RETURNING -> Unit // основное действие в этом режиме — диалог прибытия, не кнопка
            }
        }

        binding.manualArrivalButton.setOnClickListener {
            trackingService?.triggerManualArrivalCheck()
        }

        binding.quickMarkButton.setOnClickListener {
            showMarkPlaceDialog()
        }
    }

    private fun confirmStart() {
        val location = lastKnownLocation
        if (location != null && location.accuracy > LOW_ACCURACY_THRESHOLD_METERS) {
            AlertDialog.Builder(this)
                .setMessage(R.string.low_accuracy_warning)
                .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> showStartConfirmDialog() }
                .setNegativeButton(R.string.arrived_dialog_no, null)
                .show()
        } else {
            showStartConfirmDialog()
        }
    }

    private fun showStartConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_start_title)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ ->
                lastKnownLocation?.let {
                    viewModel.onStartConfirmed(it)
                    trackingService?.updateMode(TrackingMode.RECORDING)
                }
            }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_stop_title)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ ->
                viewModel.onStopConfirmed()
                trackingService?.updateMode(TrackingMode.STOPPED)
            }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    private fun confirmHome() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_home_title)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ ->
                viewModel.onHomeConfirmed()
                trackingService?.updateMode(TrackingMode.RETURNING)
            }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    private fun showArrivedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.arrived_dialog_title)
            .setMessage(R.string.arrived_dialog_message)
            .setCancelable(false)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ ->
                viewModel.onArrivedConfirmed()
                trackingService?.onArrivalDialogDismissed(confirmed = true)
                trackingService?.updateMode(TrackingMode.IDLE)
            }
            .setNegativeButton(R.string.arrived_dialog_no) { _, _ ->
                trackingService?.onArrivalDialogDismissed(confirmed = false)
            }
            .show()
    }

    private fun showMarkPlaceDialog() {
        val input = androidx.appcompat.widget.AppCompatEditText(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.mark_place_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                val location = lastKnownLocation
                if (name.isNotEmpty() && location != null) {
                    val app = application as TrailBackApp
                    lifecycleScope.launch {
                        app.database.markedPlaceDao().insert(
                            com.trailback.app.data.db.MarkedPlace(
                                name = name,
                                latitude = location.latitude,
                                longitude = location.longitude,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.mode.collect { mode ->
                updateButtonForMode(mode)
                binding.manualArrivalButton.visibility =
                    if (mode == TrackingMode.RETURNING) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.distanceMeters.collect { meters ->
                binding.topInfoPanel.distanceTraveledText.text =
                    getString(R.string.distance_traveled_label) + ": " + formatDistance(meters)
            }
        }
        lifecycleScope.launch {
            viewModel.activeEntryPoint.collect { entryPoint ->
                if (entryPoint == null) {
                    mapController.updateTrackLine(emptyList(), viewModel.mode.value)
                    return@collect
                }
                val app = application as TrailBackApp
                app.trackingRepository.observeTrackForEntryPoint(entryPoint.id).collect { points ->
                    mapController.updateTrackLine(points, viewModel.mode.value)
                }
            }
        }
    }

    private fun updateButtonForMode(mode: TrackingMode) {
        binding.mainActionButton.text = when (mode) {
            TrackingMode.IDLE -> getString(R.string.button_start)
            TrackingMode.RECORDING -> getString(R.string.button_stop)
            TrackingMode.STOPPED, TrackingMode.RETURNING -> getString(R.string.button_home)
        }
    }

    /** м → км при >1000, с одним знаком после запятой (п.6.1 ТЗ). */
    private fun formatDistance(meters: Float): String {
        return if (meters > 1000f) {
            String.format(Locale.getDefault(), "%.1f км", meters / 1000f)
        } else {
            String.format(Locale.getDefault(), "%.0f м", meters)
        }
    }
}
