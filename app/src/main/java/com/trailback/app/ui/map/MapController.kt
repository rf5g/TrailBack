package com.trailback.app.ui.map

import android.content.Context
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import androidx.documentfile.provider.DocumentFile
import com.trailback.app.R
import com.trailback.app.data.db.EntryPoint
import com.trailback.app.data.db.TrackPoint
import com.trailback.app.data.repository.TrackingMode
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.download.TileDownloadLayer
import org.mapsforge.map.layer.download.tilesource.OpenStreetMapMapnik
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File

/**
 * Инкапсулирует работу с Mapsforge: если офлайн-карта выбрана — рендерит её
 * тайлы; если нет ни офлайн-карты, ни интернета — показывает серую заглушку
 * (см. решение по ТЗ), поверх которой всё равно рисуются маршрут и элементы
 * управления (мини-компас, кнопки остаются поверх FrameLayout).
 */
class MapController(
    private val context: Context,
    private val container: FrameLayout
) {
    private var mapView: MapView? = null
    private var tileRendererLayer: TileRendererLayer? = null
    private var tileDownloadLayer: TileDownloadLayer? = null

    private var trackPolyline: Polyline? = null
    private var homeLinePolyline: Polyline? = null
    private var userPositionMarker: Marker? = null

    init {
        AndroidGraphicFactory.createInstance(context.applicationContext as android.app.Application)
    }

    /**
     * Пытается загрузить офлайн-карту из выбранной SAF-папки. Если карта не
     * выбрана — показывает заглушку. Онлайн-тайлы (при наличии интернета)
     * подключаются отдельным вызовом [showOnlineFallback], если офлайн нет.
     */
    fun setupMap(offlineMapsUri: String?, hasInternet: Boolean) {
        container.removeAllViews()

        val mapFile = offlineMapsUri?.let { resolveFirstMapFile(it) }
        when {
            mapFile != null -> showOfflineMap(mapFile)
            hasInternet -> showOnlineMap()
            else -> showPlaceholder()
        }
    }

    private fun resolveFirstMapFile(treeUriString: String): File? {
        return try {
            val treeUri = Uri.parse(treeUriString)
            val documentFile = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val mapDoc = documentFile.listFiles().firstOrNull { it.name?.endsWith(".map") == true }
            mapDoc?.uri?.let { uri ->
                // Mapsforge MapFile требует java.io.File — копируем во внутреннее хранилище,
                // если файл пришёл через SAF-дерево без прямого файлового пути.
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val cacheFile = File(context.cacheDir, mapDoc.name ?: "offline.map")
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                    cacheFile
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun showOfflineMap(mapFile: File) {
        val newMapView = MapView(context)
        newMapView.setBuiltInZoomControls(false)
        newMapView.mapScaleBar.isVisible = true

        val tileCache: TileCache = AndroidUtil.createTileCache(
            context,
            "offline_tiles",
            newMapView.model.displayModel.tileSize,
            1f,
            newMapView.model.frameBufferModel.overdrawFactor
        )

        val mapDataStore: MapDataStore = MapFile(mapFile)
        val layer = AndroidUtil.createTileRendererLayer(
            tileCache,
            newMapView.model.mapViewPosition,
            mapDataStore,
            MapsforgeThemes.DEFAULT,
            false,
            true,
            false
        )
        newMapView.layerManager.layers.add(layer)
        newMapView.model.mapViewPosition.zoomLevelMin = ZOOM_LEVEL_MIN
        newMapView.model.mapViewPosition.zoomLevelMax = ZOOM_LEVEL_MAX
        newMapView.model.mapViewPosition.zoomLevel = DEFAULT_ZOOM_LEVEL

        tileRendererLayer = layer
        mapView = newMapView
        container.addView(newMapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    /**
     * Онлайн-тайлы через стандартный источник OpenStreetMap Mapnik —
     * используется, только если офлайн-карта не выбрана, но есть интернет
     * (см. решение по ТЗ). Кэш тайлов — на диске, чтобы не перекачивать
     * одни и те же участки повторно в рамках сессии.
     */
    private fun showOnlineMap() {
        val newMapView = MapView(context)
        newMapView.setBuiltInZoomControls(false)
        newMapView.mapScaleBar.isVisible = true

        val tileSource = OpenStreetMapMapnik.INSTANCE
        tileSource.userAgent = context.packageName

        val tileCache: TileCache = AndroidUtil.createTileCache(
            context,
            "online_tiles",
            newMapView.model.displayModel.tileSize,
            1f,
            newMapView.model.frameBufferModel.overdrawFactor
        )

        val downloadLayer = TileDownloadLayer(
            tileCache,
            newMapView.model.mapViewPosition,
            tileSource,
            AndroidGraphicFactory.INSTANCE
        )
        newMapView.layerManager.layers.add(downloadLayer)
        newMapView.model.mapViewPosition.zoomLevelMin = ZOOM_LEVEL_MIN
        newMapView.model.mapViewPosition.zoomLevelMax = ZOOM_LEVEL_MAX_ONLINE
        newMapView.model.mapViewPosition.zoomLevel = DEFAULT_ZOOM_LEVEL

        tileDownloadLayer = downloadLayer
        mapView = newMapView
        container.addView(newMapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun showPlaceholder() {
        val placeholder = View(context).apply {
            setBackgroundColor(Color.parseColor("#3A3A3A"))
        }
        container.addView(placeholder, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    /** Сплошная чёрная линия трека толщиной 4dp (п.6.1 ТЗ), только пока идёт запись. */
    fun updateTrackLine(points: List<TrackPoint>, mode: TrackingMode) {
        val mapView = this.mapView ?: return
        trackPolyline?.let { mapView.layerManager.layers.remove(it) }

        if (mode != TrackingMode.RECORDING || points.size < 2) return

        val paintStroke = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            color = 0xFF000000.toInt()
            strokeWidth = 4f * context.resources.displayMetrics.density
        }
        val polyline = Polyline(paintStroke, AndroidGraphicFactory.INSTANCE)
        points.forEach { polyline.latLongs.add(LatLong(it.latitude, it.longitude)) }

        mapView.layerManager.layers.add(polyline)
        trackPolyline = polyline
    }

    /**
     * Пунктирная линия от текущей позиции до точки старта — всегда серая
     * в обычном режиме, ярко-оранжевая/увеличенная в режиме "Домой"
     * (зелёный не используется, см. п.6.1 ТЗ).
     */
    fun updateHomeLine(current: Location?, entryPoint: EntryPoint?, mode: TrackingMode) {
        val mapView = this.mapView ?: return
        homeLinePolyline?.let { mapView.layerManager.layers.remove(it) }

        if (current == null || entryPoint == null) return
        if (mode == TrackingMode.IDLE) return

        val isReturning = mode == TrackingMode.RETURNING
        val density = context.resources.displayMetrics.density
        val paintStroke = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            color = if (isReturning) ACCENT_COLOR_MAPSFORGE else 0xFF9E9E9E.toInt()
            strokeWidth = (if (isReturning) 8f else 3f) * density
            setDashPathEffect(floatArrayOf(12f * density, 8f * density))
        }
        val polyline = Polyline(paintStroke, AndroidGraphicFactory.INSTANCE)
        polyline.latLongs.add(LatLong(current.latitude, current.longitude))
        polyline.latLongs.add(LatLong(entryPoint.latitude, entryPoint.longitude))

        mapView.layerManager.layers.add(polyline)
        homeLinePolyline = polyline
    }

    /** Красная (акцентная) стрелка текущего положения пользователя по курсу. */
    fun updateUserPositionMarker(location: Location?) {
        val mapView = this.mapView ?: return
        if (location == null) return

        userPositionMarker?.let { mapView.layerManager.layers.remove(it) }
        // Значок стрелки подставляется из drawable/ic_user_position.xml (плейсхолдер,
        // пользователь заменит своим значком — цвет #E65100 уже зашит в drawable).
        val bitmap = AndroidGraphicFactory.convertToBitmap(
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_user_position)
        )
        val marker = Marker(LatLong(location.latitude, location.longitude), bitmap, 0, 0)
        mapView.layerManager.layers.add(marker)
        userPositionMarker = marker

        mapView.model.mapViewPosition.center = LatLong(location.latitude, location.longitude)
    }

    /** true, если возможно приблизить дальше (кнопка "+" должна быть активна). */
    fun canZoomIn(): Boolean {
        val position = mapView?.model?.mapViewPosition ?: return false
        return position.zoomLevel < position.zoomLevelMax
    }

    /** true, если возможно отдалить дальше (кнопка "-" должна быть активна). */
    fun canZoomOut(): Boolean {
        val position = mapView?.model?.mapViewPosition ?: return false
        return position.zoomLevel > position.zoomLevelMin
    }

    fun zoomIn() {
        val position = mapView?.model?.mapViewPosition ?: return
        if (position.zoomLevel < position.zoomLevelMax) position.zoomIn()
    }

    fun zoomOut() {
        val position = mapView?.model?.mapViewPosition ?: return
        if (position.zoomLevel > position.zoomLevelMin) position.zoomOut()
    }

    fun recenterOn(location: Location?) {
        location ?: return
        mapView?.model?.mapViewPosition?.center = LatLong(location.latitude, location.longitude)
    }

    fun onDestroy() {
        tileRendererLayer?.let { it.mapDataStore?.close() }
        tileDownloadLayer?.onDestroy()
        mapView?.destroyAll()
        AndroidGraphicFactory.clearResourceMemoryCache()
    }

    companion object {
        // ARGB int, тот же цвет #E65100, что и везде в приложении
        private const val ACCENT_COLOR_MAPSFORGE = 0xFFE65100.toInt()

        // Разумные границы зума для рендерера Mapsforge: ниже 2 карта нечитаема
        // на масштабе всей страны, выше 20 офлайн-тайлы обычно не детализированы.
        private const val ZOOM_LEVEL_MIN: Byte = 2
        private const val ZOOM_LEVEL_MAX: Byte = 20
        private const val ZOOM_LEVEL_MAX_ONLINE: Byte = 19
        private const val DEFAULT_ZOOM_LEVEL: Byte = 15
    }
}
