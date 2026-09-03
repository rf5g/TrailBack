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
import com.trailback.app.ui.compass.CompassSensorManager
import com.trailback.app.ui.common.InfoPanelController
import com.trailback.app.ui.menu.MenuActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
class MapActivity : AppCompatActivity() {
    companion object {
        const val ACTION_SHOW_ARRIVED_DIALOG = "com.trailback.app.SHOW_ARRIVED_DIALOG"
        const val LOW_ACCURACY_THRESHOLD_METERS = 50f
    }
    private lateinit var binding: ActivityMapBinding
    private lateinit var viewModel: MapViewModel
    private lateinit var mapController: MapController
    private lateinit var compassSensorManager: CompassSensorManager
    private var trackingService: TrackingService? = null
    private var isServiceBound = false
    private var lastKnownLocation: Location? = null
    private var currentHeading: Float = 0f
    private var lastAppliedOfflineMapsUri: String? = null
    private var serviceObserverJob: kotlinx.coroutines.Job? = null
    private lateinit var infoPanelController: InfoPanelController
    // Собственный лёгкий запрос геопозиции для отображения на карте и
    // кнопки "Старт", пока экран открыт — НЕЗАВИСИМО от фонового сервиса.
    // Сервис теперь не опрашивает GPS в IDLE/STOPPED (экономия батареи,
    // см. решение по ТЗ), поэтому карта в эти моменты питается отсюда, а не
    // от TrackingService. В RECORDING/RETURNING работает параллельно с
    // сервисом — не мешает, координаты берутся из того же GPS-чипа.
    private val foregroundLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }
    private val foregroundLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastKnownLocation = location
            onLocationUpdated(location)
        }
    }
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
        lastAppliedOfflineMapsUri = app.settingsStore.offlineMapsUri
        lifecycleScope.launch {
            mapController.setupMap(lastAppliedOfflineMapsUri, hasInternetConnection())
            // Пересчёт состояния зум-кнопок ПОСЛЕ готовности mapView — иначе
            // при офлайн-карте (реальная suspend-точка на Dispatchers.IO)
            // canZoomIn()/canZoomOut() читают ещё null mapView и кнопки
            // навсегда остаются disabled/полупрозрачными (баг: кнопки
            // недоступны только когда загружена офлайн-карта).
            updateZoomButtonsState()
        }
        compassSensorManager = CompassSensorManager(this) { heading ->
            currentHeading = heading
            binding.miniCompassView.headingDegrees = heading
        }
        compassSensorManager.northMode = app.settingsStore.northMode
        infoPanelController = InfoPanelController(this, binding.topInfoPanel)
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
    override fun onResume() {
        super.onResume()
        compassSensorManager.start()
        infoPanelController.start()
        viewModel.refreshActiveEntryPoint()
        reapplyOfflineMapIfChanged()
        startForegroundOnlyLocationUpdates()
    }
    override fun onPause() {
        compassSensorManager.stop()
        infoPanelController.stop()
        foregroundLocationClient.removeLocationUpdates(foregroundLocationCallback)
        super.onPause()
    }
    /**
     * Лёгкий запрос геопозиции, активный только пока экран открыт (item 4) —
     * компенсирует то, что фоновый сервис больше не опрашивает GPS в
     * IDLE/STOPPED. Экономия батареи здесь — за счёт ОГРАНИЧЕНИЯ ВРЕМЕНИ
     * работы (только пока Activity видима, через onResume/onPause), а НЕ за
     * счёт снижения точности: PRIORITY_BALANCED_POWER_ACCURACY в основном
     * использует Wi-Fi/сотовую сеть вместо GPS-чипа и даёт точность порядка
     * сотен метров — этим объяснялось ухудшение показа позиции. Пока
     * пользователь реально смотрит на экран (и так расходует батарею на сам
     * экран), полноценная точность GPS оправдана и нужна — в том числе для
     * корректной проверки перед "Старт" (LOW_ACCURACY_THRESHOLD_METERS).
     */
    private fun startForegroundOnlyLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).build()
        try {
            foregroundLocationClient.requestLocationUpdates(request, foregroundLocationCallback, mainLooper)
        } catch (e: SecurityException) {
            // Разрешение не выдано — просто не запускаем обновления.
        }
    }
    /**
     * Раньше выбор офлайн-карты в настройках применялся только после полного
     * перезапуска приложения — MapController.setupMap() вызывался лишь один
     * раз в onCreate. Теперь при каждом возврате на экран карты сверяем,
     * не изменился ли путь к офлайн-картам, и переинициализируем карту.
     */
    private fun reapplyOfflineMapIfChanged() {
        val app = application as TrailBackApp
        val currentUri = app.settingsStore.offlineMapsUri
        if (currentUri != lastAppliedOfflineMapsUri) {
            lastAppliedOfflineMapsUri = currentUri
            lifecycleScope.launch {
                mapController.setupMap(currentUri, hasInternetConnection())
                updateZoomButtonsState() // та же гонка, что и в onCreate()
            }
        }
    }
    override fun onStop() {
        serviceObserverJob?.cancel()
        serviceObserverJob = null
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
    /**
     * КРИТИЧНО: отменяем предыдущую подписку перед созданием новой.
     * onServiceConnected() срабатывает заново на каждый bindService()
     * (то есть на каждый onStart(), в т.ч. после сворачивания приложения) —
     * без отмены старой корутины подписки накапливались бы при каждом
     * цикле сворачивания/разворачивания (тот же класс бага, что чинили
     * в CompassActivity — там он путал сглаживание компаса, здесь мог бы
     * приводить к повторным показам диалога "Вы вернулись!").
     */
    private fun observeService(service: TrackingService) {
        serviceObserverJob?.cancel()
        serviceObserverJob = lifecycleScope.launch {
            launch {
                service.currentLocation.collect { location ->
                    if (location != null) {
                        lastKnownLocation = location
                        onLocationUpdated(location)
                    }
                }
            }
            launch {
                service.arrivedHomeEvent.collect { shouldShow ->
                    if (shouldShow) showArrivedDialog()
                }
            }
        }
    }
    private fun onLocationUpdated(location: Location) {
        mapController.updateUserPositionMarker(location, currentHeading)
        val entryPoint = viewModel.activeEntryPoint.value
        mapController.updateHomeLine(location, entryPoint, viewModel.mode.value)
        infoPanelController.updateDistanceToDestination(location, entryPoint)
        // Счётчик пути (п.6): TrackingService копит дистанцию в
        // TrackingStateStore напрямую (не через ViewModel), поэтому UI
        // синхронизируется с реальным значением на каждый тик геопозиции,
        // а не полагается только на разовые сбросы во ViewModel.
        if (viewModel.mode.value == TrackingMode.RECORDING) {
            infoPanelController.updateRouteCounter()
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
                TrackingMode.RECORDING -> confirmHome()
                TrackingMode.RETURNING -> confirmManualArrival()
            }
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
    /**
     * Ручное подтверждение через объединённую кнопку (п.3): два РАЗНЫХ
     * диалога подряд, а не троекратное повторение одного и того же — сперва
     * обычное подтверждение прибытия, затем отдельное предупреждение о
     * сбросе режима возврата (п.4).
     */
    private fun confirmManualArrival() {
        AlertDialog.Builder(this)
            .setTitle(R.string.arrived_dialog_title)
            .setMessage(R.string.arrived_dialog_message)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> confirmManualArrivalReset() }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }
    private fun confirmManualArrivalReset() {
        AlertDialog.Builder(this)
            .setMessage(R.string.manual_arrival_reset_confirm_message)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ ->
                viewModel.onArrivedConfirmed()
                trackingService?.onArrivalDialogDismissed(confirmed = true)
                trackingService?.updateMode(TrackingMode.IDLE)
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
            }
        }
        lifecycleScope.launch {
            viewModel.distanceMeters.collect {
                infoPanelController.updateRouteCounter()
            }
        }
        lifecycleScope.launch {
            viewModel.activeEntryPoint.collect { entryPoint ->
                mapController.updateEntryPointMarker(entryPoint)
            }
        }
        lifecycleScope.launch {
            // ВАЖНО: flatMapLatest, а не вложенный .collect { ... .collect {} }.
            // Вложенный collect блокировал внешний поток навсегда на первой
            // же точке входа — при повторной записи маршрута (новая точка
            // входа после "Я на месте") внешний коллектор не мог "дойти" до
            // новой подписки, поэтому трек переставал обновляться до полного
            // перезапуска приложения (см. решение по багу — п.5).
            // flatMapLatest сам отменяет предыдущую внутреннюю подписку при
            // каждой новой активной точке входа.
            viewModel.activeEntryPoint
                .flatMapLatest { entryPoint ->
                    if (entryPoint == null) {
                        flowOf(emptyList())
                    } else {
                        val app = application as TrailBackApp
                        app.trackingRepository.observeTrackForEntryPoint(entryPoint.id)
                    }
                }
                .collect { points ->
                    mapController.updateTrackLine(points, viewModel.mode.value)
                }
        }
        lifecycleScope.launch {
            val app = application as TrailBackApp
            app.database.markedPlaceDao().observeAll().collect { places ->
                mapController.updateMarkedPlaces(places)
            }
        }
    }
    private fun updateButtonForMode(mode: TrackingMode) {
        binding.mainActionButton.text = when (mode) {
            TrackingMode.IDLE -> getString(R.string.button_start)
            TrackingMode.RECORDING -> getString(R.string.button_home)
            TrackingMode.RETURNING -> getString(R.string.manual_arrival_button)
        }
    }
}
