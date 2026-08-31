package com.trailback.app.ui.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.trailback.app.R
import com.trailback.app.TrailBackApp
import com.trailback.app.data.db.EntryPoint
import com.trailback.app.databinding.ViewTopInfoPanelBinding
import com.trailback.app.util.DistanceFormatter

/**
 * Общая логика обновления панели с метрами и спутниками — используется и на
 * экране карты, и на экране компаса (панель дублируется на обоих экранах
 * по решению ТЗ). Сама разметка по-прежнему подключается через <include> в
 * каждый layout отдельно — два разных Activity/окна физически не могут
 * делить один и тот же View-инстанс без более крупного рефакторинга
 * (слияние экранов в один Activity с фрагментами). Но КОД обновления
 * данных теперь общий, не дублируется между экранами.
 */
class InfoPanelController(
    private val activity: AppCompatActivity,
    private val panel: ViewTopInfoPanelBinding
) {
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
            panel.satellitesText.text =
                activity.getString(R.string.satellites_label, gpsCount, glonassCount)
        }
    }

    /** Вызывать из onResume() вызывающего экрана. */
    fun start() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.registerGnssStatusCallback(gnssStatusCallback, null)
    }

    /** Вызывать из onPause() вызывающего экрана. */
    fun stop() {
        val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
    }

    /** Счётчик пути — единый источник правды: stateStore.distanceMeters. */
    fun updateRouteCounter() {
        val app = activity.application as TrailBackApp
        panel.distanceTraveledText.text =
            DistanceFormatter.format(app.trackingStateStore.distanceMeters)
    }

    /** Дистанция до цели — актуальна только когда цель задана (режим "Домой"). */
    fun updateDistanceToDestination(current: Location, target: EntryPoint?) {
        if (target == null) {
            panel.distanceToHomeText.text = ""
            return
        }
        val distanceResult = FloatArray(1)
        Location.distanceBetween(
            current.latitude, current.longitude,
            target.latitude, target.longitude,
            distanceResult
        )
        panel.distanceToHomeText.text = DistanceFormatter.format(distanceResult[0])
    }

    fun clearDistanceToDestination() {
        panel.distanceToHomeText.text = ""
    }
}
