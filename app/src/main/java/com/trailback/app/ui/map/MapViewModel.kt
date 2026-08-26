package com.trailback.app.ui.map

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trailback.app.TrailBackApp
import com.trailback.app.data.db.EntryPoint
import com.trailback.app.data.repository.TrackingMode
import com.trailback.app.data.repository.TrackingRepository
import com.trailback.app.data.repository.TrackingStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MapViewModel(
    private val repository: TrackingRepository,
    private val stateStore: TrackingStateStore
) : ViewModel() {

    private val _mode = MutableStateFlow(stateStore.mode)
    val mode: StateFlow<TrackingMode> = _mode.asStateFlow()

    private val _distanceMeters = MutableStateFlow(stateStore.distanceMeters)
    val distanceMeters: StateFlow<Float> = _distanceMeters.asStateFlow()

    private val _activeEntryPoint = MutableStateFlow<EntryPoint?>(null)
    val activeEntryPoint: StateFlow<EntryPoint?> = _activeEntryPoint.asStateFlow()

    init {
        viewModelScope.launch {
            _activeEntryPoint.value = repository.getActiveEntryPoint()
        }
    }

    fun onStartConfirmed(location: Location) {
        viewModelScope.launch {
            val name = "Вход ${java.text.SimpleDateFormat("dd.MM HH:mm").format(java.util.Date())}"
            repository.startNewRoute(location.latitude, location.longitude, name)
            _mode.value = TrackingMode.RECORDING
            _distanceMeters.value = 0f
            _activeEntryPoint.value = repository.getActiveEntryPoint()
        }
    }

    /** "Стоп": запись останавливается, кнопка переходит в состояние "Домой". */
    fun onStopConfirmed() {
        viewModelScope.launch {
            stateStore.mode = TrackingMode.STOPPED
            stateStore.lastUpdateTimestamp = System.currentTimeMillis()
            _mode.value = TrackingMode.STOPPED
        }
    }

    fun onHomeConfirmed() {
        viewModelScope.launch {
            repository.enterReturningMode()
            _mode.value = TrackingMode.RETURNING
        }
    }

    fun onArrivedConfirmed() {
        viewModelScope.launch {
            repository.confirmArrivedHome()
            _mode.value = TrackingMode.IDLE
            _distanceMeters.value = 0f
            _activeEntryPoint.value = null
        }
    }

    /** Вызывается после отмены восстановления трека (диалог краш-recovery) —
     * состояние в БД/prefs уже сброшено репозиторием, здесь синхронизируем UI. */
    fun resetToIdleState() {
        _mode.value = TrackingMode.IDLE
        _distanceMeters.value = 0f
        _activeEntryPoint.value = null
    }

    /** Вызывается при возврате на карту — активная точка и режим могли
     * измениться в другом экране (например, выбор точки входа переводит
     * в режим "Домой" — см. решение по ТЗ). */
    fun refreshActiveEntryPoint() {
        viewModelScope.launch {
            _mode.value = stateStore.mode
            _activeEntryPoint.value = repository.getActiveEntryPoint()
        }
    }

    class Factory(private val app: TrailBackApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MapViewModel(app.trackingRepository, app.trackingStateStore) as T
        }
    }
}
