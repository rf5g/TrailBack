package com.trailback.app

import android.app.Application
import com.trailback.app.data.db.AppDatabase
import com.trailback.app.data.repository.SettingsStore
import com.trailback.app.data.repository.TrackingRepository
import com.trailback.app.data.repository.TrackingStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Корень простого ручного DI (без Hilt/Dagger — конструкторное внедрение,
 * как того требует п.11 ТЗ).
 */
class TrailBackApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var trackingStateStore: TrackingStateStore
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var trackingRepository: TrackingRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        trackingStateStore = TrackingStateStore(this)
        settingsStore = SettingsStore(this)
        trackingRepository = TrackingRepository(
            entryPointDao = database.entryPointDao(),
            trackPointDao = database.trackPointDao(),
            stateStore = trackingStateStore
        )

        // 1) Если после краша прошло больше 72 часов — трек считается брошенным
        //    и сбрасывается; 2) в любом случае подчищаем осиротевшие точки трека
        //    от предыдущих сессий (см. TrackingRepository).
        appScope.launch {
            trackingRepository.expireStaleSessionIfNeeded(System.currentTimeMillis())
            trackingRepository.purgeOrphanedTrackPoints()
        }
    }
}
