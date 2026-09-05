package com.trailback.app.ui.map
import android.content.Context
import android.graphics.Bitmap as AndroidBitmapType
import android.graphics.Canvas as AndroidCanvasType
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.trailback.app.R
import com.trailback.app.data.db.EntryPoint
import com.trailback.app.data.db.TrackPoint
import com.trailback.app.data.db.MarkedPlace
import com.trailback.app.data.repository.TrackingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.download.TileDownloadLayer
import org.mapsforge.map.layer.download.tilesource.OpenStreetMapMapnik
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.InternalRenderTheme
import java.io.File
import kotlin.math.abs
/**
 * Инкапсулирует работу с Mapsforge: если офлайн-карта выбрана — рендерит её
 * тайлы; если нет ни офлайн-карты, ни интернета — показывает серую заглушку,
 * поверх которой всё равно рисуются маршрут и элементы управления.
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
    private var accuracyCircle: Circle? = null
    private var entryPointMarker: Marker? = null
    // НОВОЕ: "взятие направления" — независимые от точки входа маркер и
    // пунктирная линия (см. решение по ТЗ), живут своим циклом.
    private var navigationTargetMarker: Marker? = null
    private var navigationTargetLine: Polyline? = null
    /** Вызывается при долгом тапе по карте с экранными координатами тапа —
     * MapActivity сам решает, что показать (диалог выбора действия). */
    var onLongPress: ((screenX: Float, screenY: Float) -> Unit)? = null
    private val markedPlaceMarkers = mutableListOf<Marker>()
    private var lastMarkerHeading: Float? = null
    private val density = context.resources.displayMetrics.density
    private val markerSizePx: Int = (MARKER_SIZE_DP * density).toInt()
    /**
     * Следящий режим: пока включён, карта центрируется на каждое обновление
     * геопозиции (пользователь реально идёт). Отключается автоматически при
     * любом касании карты (пользователь просматривает/двигает карту вручную)
     * и включается заново по нажатию кнопки центрирования.
     */
    private var followModeEnabled = true
    // Тот же стиль, что и у имени точки входа ("Вход - сб. 05.09.26 г. 14:07"),
    // но без года и "г." — на карте компактнее, год избыточен (см. решение по ТЗ).
    private val entryPointDateFormat = java.text.SimpleDateFormat(
        "EEE'.' dd.MM HH:mm", java.util.Locale.getDefault()
    )
    init {
        AndroidGraphicFactory.createInstance(context.applicationContext as android.app.Application)
    }
    /**
     * КРИТИЧНО: копирование файла офлайн-карты (может весить сотни МБ)
     * раньше выполнялось СИНХРОННО на вызывающем потоке — а вызывалось это
     * из onCreate()/onResume(), то есть на главном UI-потоке. Для файла
     * ~700 МБ это гарантированный ANR ("вылетает при первом предоставлении
     * доступа к папке") — Android считает, что приложение зависло, и
     * убивает его. Второй раз "работает нормально", вероятно, потому что
     * чтение через SAF идёт уже из тёплого файлового кэша ОС и укладывается
     * в лимит ANR. Теперь копирование выполняется на Dispatchers.IO, а
     * заглушка показывается сразу — пользователь не видит зависания.
     */
    suspend fun setupMap(offlineMapsUri: String?, hasInternet: Boolean) {
        // Уничтожаем предыдущий MapView (если это переинициализация после смены
        // офлайн-карты в настройках, см. решение по ТЗ — раньше карта менялась
        // только после полного перезапуска приложения) и сбрасываем ссылки на
        // маркеры/линии, привязанные к старому (уже уничтоженному) MapView —
        // иначе updateTrackLine/updateHomeLine/updateUserPositionMarker будут
        // молча падать на несуществующие layers старой карты.
        tileRendererLayer?.let { it.mapDataStore.close() }
        tileDownloadLayer?.onDestroy()
        mapView?.destroyAll()
        mapView = null
        tileRendererLayer = null
        tileDownloadLayer = null
        trackPolyline = null
        homeLinePolyline = null
        userPositionMarker = null
        accuracyCircle = null
        entryPointMarker = null
        navigationTargetMarker = null // НОВОЕ
        navigationTargetLine = null // НОВОЕ
        markedPlaceMarkers.clear()
        lastMarkerHeading = null
        followModeEnabled = true
        container.removeAllViews()
        if (offlineMapsUri != null) {
            // Показываем заглушку сразу — пока в фоне может идти копирование
            // большого файла карты, пользователь не видит зависший экран.
            showPlaceholder()
            val mapFile = withContext(Dispatchers.IO) { resolveFirstMapFile(offlineMapsUri) }
            if (mapFile != null) {
                container.removeAllViews()
                showOfflineMap(mapFile)
                return
            }
            // Офлайн-карта не найдена/не читается — пробуем онлайн или заглушку.
            container.removeAllViews()
        }
        if (hasInternet) {
            showOnlineMap()
        } else {
            showPlaceholder()
        }
    }
    /**
     * Выполняется на Dispatchers.IO (см. вызывающий код). Пропускает
     * повторное копирование, если в кэше уже лежит файл с тем же именем и
     * размером — иначе при каждом пересоздании экрана карты (например,
     * после смены ориентации) 700-мегабайтный файл копировался бы заново.
     */
    private fun resolveFirstMapFile(treeUriString: String): File? {
        return try {
            val treeUri = Uri.parse(treeUriString)
            val documentFile = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val mapDoc = documentFile.listFiles().firstOrNull { it.name?.endsWith(".map") == true }
                ?: return null
            val cacheFile = File(context.cacheDir, mapDoc.name ?: "offline.map")
            if (cacheFile.exists() && cacheFile.length() == mapDoc.length()) {
                return cacheFile
            }
            context.contentResolver.openInputStream(mapDoc.uri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            }
            cacheFile
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
            InternalRenderTheme.OSMARENDER,
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
        attachManualPanDetector(newMapView)
        container.addView(newMapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }
    /**
     * Онлайн-тайлы через OpenStreetMap Mapnik — простая, бесплатная,
     * некоммерческая карта без спутниковых снимков (см. решение по ТЗ:
     * "простые карты, без сильной детализации, без спутника"). Используется,
     * только если офлайн-карта не выбрана, но есть интернет.
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
        attachManualPanDetector(newMapView)
        container.addView(newMapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }
    /**
     * Любое касание карты пользователем (пан, зум жестом, простой тап)
     * отключает следящий режим — дальше карта не будет "убегать" обратно
     * к маркеру, пока пользователь её рассматривает. Слушатель ничего не
     * потребляет (return false), чтобы Mapsforge продолжал штатно
     * обрабатывать сами жесты.
     */
    // НОВОЕ: GestureDetector для долгого тапа ("взятие направления"/
    // "сохранить место") — заведён в ОДИН OnTouchListener вместе со старой
    // логикой отключения следящего режима, т.к. setOnTouchListener на View
    // всегда ПЕРЕЗАПИСЫВАЕТ предыдущий листенер, второй вызов был бы
    // невозможен. Возврат false в обоих случаях сохранён — иначе Mapsforge
    // перестанет обрабатывать собственные жесты (пан/зум).
    private val longPressGestureDetector by lazy {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                onLongPress?.invoke(e.x, e.y)
            }
        })
    }
    private fun attachManualPanDetector(mapView: MapView) {
        mapView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                followModeEnabled = false
            }
            longPressGestureDetector.onTouchEvent(event)
            false
        }
    }
    private fun showPlaceholder() {
        val placeholder = View(context).apply {
            setBackgroundColor(Color.parseColor("#3A3A3A"))
        }
        container.addView(placeholder, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }
    /**
     * Сплошная чёрная линия трека толщиной 4dp, только пока идёт запись.
     * ВАЖНО: без явного paint.setStyle(Style.STROKE) полилиния не рисуется —
     * дефолтный стиль Mapsforge Paint это FILL (это и было причиной бага
     * "трек не виден").
     *
     * Сглаживание острых углов: у Mapsforge НЕТ готового флага/параметра для
     * сглаживания полилиний — Polyline.draw() строит путь только прямыми
     * сегментами (moveTo/lineTo). Сглаживание реализовано отдельно —
     * интерполяцией Catmull-Rom по исходным GPS-точкам перед тем, как
     * передать координаты в обычный Polyline. Сам Mapsforge не тронут.
     */
    fun updateTrackLine(points: List<TrackPoint>, mode: TrackingMode) {
        val mapView = this.mapView ?: return
        trackPolyline?.let { mapView.layerManager.layers.remove(it) }
        if (mode != TrackingMode.RECORDING || points.size < 2) return
        val paintStroke = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setStyle(Style.STROKE)
            color = 0xFF000000.toInt()
            strokeWidth = 4f * density
        }
        val polyline = Polyline(paintStroke, AndroidGraphicFactory.INSTANCE)
        val rawLatLongs = points.map { LatLong(it.latitude, it.longitude) }
        smoothTrackPoints(rawLatLongs).forEach { polyline.latLongs.add(it) }
        mapView.layerManager.layers.add(polyline)
        trackPolyline = polyline
    }
    /**
     * Сглаживание Catmull-Rom: между каждой парой реальных точек вставляет
     * несколько промежуточных, рассчитанных по кривой через 4 соседние
     * точки — визуально убирает резкие "изломы" на поворотах трека без
     * искажения самого маршрута (кривая проходит ровно через все исходные
     * точки, ничего не "срезает").
     */
    private fun smoothTrackPoints(points: List<LatLong>): List<LatLong> {
        if (points.size < 3) return points
        val padded = listOf(points.first()) + points + listOf(points.last())
        val result = mutableListOf<LatLong>()
        for (i in 0 until padded.size - 3) {
            val p0 = padded[i]
            val p1 = padded[i + 1]
            val p2 = padded[i + 2]
            val p3 = padded[i + 3]
            for (step in 0 until SMOOTHING_SEGMENTS) {
                val t = step / SMOOTHING_SEGMENTS.toFloat()
                result.add(catmullRomPoint(p0, p1, p2, p3, t))
            }
        }
        result.add(points.last())
        return result
    }
    private fun catmullRomPoint(p0: LatLong, p1: LatLong, p2: LatLong, p3: LatLong, t: Float): LatLong {
        val t2 = t * t
        val t3 = t2 * t
        fun interpolate(v0: Double, v1: Double, v2: Double, v3: Double): Double {
            return 0.5 * (
                (2 * v1) +
                (-v0 + v2) * t +
                (2 * v0 - 5 * v1 + 4 * v2 - v3) * t2 +
                (-v0 + 3 * v1 - 3 * v2 + v3) * t3
            )
        }
        val lat = interpolate(p0.latitude, p1.latitude, p2.latitude, p3.latitude)
        val lon = interpolate(p0.longitude, p1.longitude, p2.longitude, p3.longitude)
        return LatLong(lat, lon)
    }
    /**
     * Пунктирная линия от текущей позиции до точки старта — серая в обычном
     * режиме, акцентная и толще в режиме "Домой".
     */
    fun updateHomeLine(current: Location?, entryPoint: EntryPoint?, mode: TrackingMode) {
        val mapView = this.mapView ?: return
        homeLinePolyline?.let { mapView.layerManager.layers.remove(it) }
        if (current == null || entryPoint == null) return
        if (mode == TrackingMode.IDLE) return
        val isReturning = mode == TrackingMode.RETURNING
        val paintStroke = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setStyle(Style.STROKE)
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
    /**
     * Значок положения пользователя — стрелка размером 1.5x от исходного
     * (48dp вместо 32dp), поворачивается по курсу устройства. Обновляется
     * только при заметном изменении курса (>3°), чтобы не пересоздавать
     * битмап на каждый чих датчика.
     */
    fun updateUserPositionMarker(location: Location?, headingDegrees: Float) {
        val mapView = this.mapView ?: return
        if (location == null) return
        updateAccuracyCircle(mapView, location)
        val previousHeading = lastMarkerHeading
        val headingChanged = previousHeading == null || angularDifference(previousHeading, headingDegrees) > 3f
        if (userPositionMarker == null || headingChanged) {
            val bitmap = createRotatedMarkerBitmap(R.drawable.ic_user_position, markerSizePx, headingDegrees)
            bitmap.incrementRefCount()
            if (userPositionMarker == null) {
                val marker = Marker(LatLong(location.latitude, location.longitude), bitmap, 0, 0)
                mapView.layerManager.layers.add(marker)
                userPositionMarker = marker
                // Первая позиция — центрируем всегда, чтобы карта не
                // стартовала на "null island" (0,0), независимо от режима.
                mapView.model.mapViewPosition.center = LatLong(location.latitude, location.longitude)
            } else {
                userPositionMarker?.setBitmap(bitmap)
                userPositionMarker?.setLatLong(LatLong(location.latitude, location.longitude))
            }
            lastMarkerHeading = headingDegrees
        } else {
            userPositionMarker?.setLatLong(LatLong(location.latitude, location.longitude))
        }
        // Следящий режим (п.1 решения): пока пользователь не трогал карту
        // руками, она сама едет за его реальным перемещением. Как только он
        // коснулся карты (пан/зум/тап), followModeEnabled=false и центр
        // остаётся там, где пользователь его оставил, до нажатия кнопки
        // центрирования.
        if (followModeEnabled) {
            mapView.model.mapViewPosition.center = LatLong(location.latitude, location.longitude)
        }
    }
    /**
     * Визуализация точности позиционирования — полупрозрачный круг вокруг
     * маркера радиусом location.accuracy (в метрах), как принято в
     * навигационных приложениях (Google Maps, Яндекс.Карты). Обновляется на
     * месте через штатные setLatLong/setRadius Mapsforge — без пересоздания
     * слоя на каждый тик, чтобы не ломать порядок отрисовки (круг должен
     * оставаться под стрелкой положения).
     */
    private fun updateAccuracyCircle(mapView: MapView, location: Location) {
        val radiusMeters = location.accuracy.coerceAtLeast(1f)
        val existing = accuracyCircle
        if (existing == null) {
            val circle = Circle(
                LatLong(location.latitude, location.longitude),
                radiusMeters,
                accuracyFillPaint,
                accuracyStrokePaint
            )
            mapView.layerManager.layers.add(circle)
            accuracyCircle = circle
        } else {
            existing.setLatLong(LatLong(location.latitude, location.longitude))
            existing.setRadius(radiusMeters)
        }
    }
    private val accuracyFillPaint by lazy {
        AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setStyle(Style.FILL)
            color = 0x33E65100.toInt() // акцентный цвет приложения, ~20% непрозрачности
        }
    }
    private val accuracyStrokePaint by lazy {
        AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setStyle(Style.STROKE)
            color = 0x88E65100.toInt() // тот же цвет, контур более заметный
            strokeWidth = 1.5f * density
        }
    }
    private fun angularDifference(a: Float, b: Float): Float {
        var diff = abs(a - b) % 360f
        if (diff > 180f) diff = 360f - diff
        return diff
    }
    /** Рисует drawable повёрнутым на заданный угол в квадратный битмап нужного размера. */
    private fun createRotatedMarkerBitmap(drawableRes: Int, sizePx: Int, rotationDegrees: Float): org.mapsforge.core.graphics.Bitmap {
        val drawable = ContextCompat.getDrawable(context, drawableRes)
        val androidBitmap = AndroidBitmapType.createBitmap(sizePx, sizePx, AndroidBitmapType.Config.ARGB_8888)
        val canvas = AndroidCanvasType(androidBitmap)
        canvas.save()
        canvas.rotate(rotationDegrees, sizePx / 2f, sizePx / 2f)
        drawable?.setBounds(0, 0, sizePx, sizePx)
        drawable?.draw(canvas)
        canvas.restore()
        val mapsforgeBitmap = AndroidGraphicFactory.INSTANCE.createBitmap(sizePx, sizePx, true)
        val targetAndroidBitmap = AndroidGraphicFactory.getBitmap(mapsforgeBitmap)
        AndroidCanvasType(targetAndroidBitmap).drawBitmap(androidBitmap, 0f, 0f, null)
        androidBitmap.recycle()
        return mapsforgeBitmap
    }
    /** Булавка с подписью названия сверху для каждого отмеченного места. */
    fun updateMarkedPlaces(places: List<MarkedPlace>) {
        val mapView = this.mapView ?: return
        markedPlaceMarkers.forEach { mapView.layerManager.layers.remove(it) }
        markedPlaceMarkers.clear()
        val pinSizePx = (36 * density).toInt()
        places.forEach { place ->
            val bitmap = createLabeledIconBitmap(R.drawable.ic_marked_place_pin, pinSizePx, place.name)
            bitmap.incrementRefCount()
            // Вертикальный офсет: острие булавки должно "стоять" на координате,
            // а не центр всего составного битмапа (текст+булавка) — сдвигаем
            // вверх на половину общей высоты композиции.
            val marker = Marker(
                LatLong(place.latitude, place.longitude), bitmap, 0, -bitmap.height / 2
            )
            mapView.layerManager.layers.add(marker)
            markedPlaceMarkers.add(marker)
        }
    }
    /**
     * Векторный читаемый крестик на точке входа с подписью даты создания
     * (например "27.авг. 7:40") — см. решение по ТЗ.
     */
    fun updateEntryPointMarker(entryPoint: EntryPoint?) {
        val mapView = this.mapView ?: return
        entryPointMarker?.let { mapView.layerManager.layers.remove(it) }
        entryPointMarker = null
        if (entryPoint == null) return
        val dateLabel = entryPointDateFormat.format(java.util.Date(entryPoint.timestamp))
        val crossSizePx = (32 * density).toInt()
        val bitmap = createLabeledIconBitmap(R.drawable.ic_entry_point_marker, crossSizePx, dateLabel)
        bitmap.incrementRefCount()
        val marker = Marker(
            LatLong(entryPoint.latitude, entryPoint.longitude), bitmap, 0, -bitmap.height / 2
        )
        mapView.layerManager.layers.add(marker)
        entryPointMarker = marker
    }
    /** Рисует подпись текстом сверху и значок снизу в одном битмапе. */
    private fun createLabeledIconBitmap(iconRes: Int, iconSizePx: Int, label: String): org.mapsforge.core.graphics.Bitmap {
        val iconDrawable = ContextCompat.getDrawable(context, iconRes)
        val textPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 18f * density // увеличено для читаемости (было 13f, см. решение по ТЗ)
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(3f * density, 0f, 0f, android.graphics.Color.BLACK)
        }
        val textWidth = textPaint.measureText(label)
        val horizontalPadding = 8f * density
        val canvasWidth = maxOf(iconSizePx.toFloat(), textWidth + horizontalPadding * 2).toInt()
        val textMetrics = textPaint.fontMetrics
        val textHeight = (textMetrics.descent - textMetrics.ascent)
        val textGap = 2f * density
        val totalHeight = (textHeight + textGap + iconSizePx).toInt()
        val androidBitmap = AndroidBitmapType.createBitmap(canvasWidth, totalHeight, AndroidBitmapType.Config.ARGB_8888)
        val canvas = AndroidCanvasType(androidBitmap)
        canvas.drawText(label, canvasWidth / 2f, -textMetrics.ascent, textPaint)
        val iconLeft = (canvasWidth - iconSizePx) / 2
        val iconTop = (textHeight + textGap).toInt()
        iconDrawable?.setBounds(iconLeft, iconTop, iconLeft + iconSizePx, iconTop + iconSizePx)
        iconDrawable?.draw(canvas)
        val mapsforgeBitmap = AndroidGraphicFactory.INSTANCE.createBitmap(canvasWidth, totalHeight, true)
        val targetAndroidBitmap = AndroidGraphicFactory.getBitmap(mapsforgeBitmap)
        AndroidCanvasType(targetAndroidBitmap).drawBitmap(androidBitmap, 0f, 0f, null)
        androidBitmap.recycle()
        return mapsforgeBitmap
    }
    // === НОВОЕ: "взятие направления" ===
    /**
     * Переводит экранные координаты долгого тапа в географические.
     * ВНИМАНИЕ: имя метода API у Mapsforge может отличаться между версиями
     * библиотеки — в 0.20.0 ожидается MapView.mapViewProjection.fromPixels().
     * Если сборка не пройдёт именно на этой строке — это единственное
     * место, которое нужно поправить под точную сигнатуру установленной
     * версии org.mapsforge:mapsforge-map-android.
     */
    fun screenToLatLong(screenX: Float, screenY: Float): Pair<Double, Double>? {
        val projected = mapView?.mapViewProjection?.fromPixels(screenX.toDouble(), screenY.toDouble())
            ?: return null
        return projected.latitude to projected.longitude
    }
    /** Пунктирная бирюзовая линия от текущей позиции до цели навигации —
     * независима от homeLinePolyline, рисуется одновременно с ней. */
    fun updateNavigationTargetLine(current: Location?, target: Pair<Double, Double>?) {
        val mapView = this.mapView ?: return
        navigationTargetLine?.let { mapView.layerManager.layers.remove(it) }
        navigationTargetLine = null
        if (current == null || target == null) return
        val paintStroke = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setStyle(Style.STROKE)
            color = NAVIGATION_TARGET_COLOR
            strokeWidth = 6f * density
            setDashPathEffect(floatArrayOf(12f * density, 8f * density))
        }
        val polyline = Polyline(paintStroke, AndroidGraphicFactory.INSTANCE)
        polyline.latLongs.add(LatLong(current.latitude, current.longitude))
        polyline.latLongs.add(LatLong(target.first, target.second))
        mapView.layerManager.layers.add(polyline)
        navigationTargetLine = polyline
    }
    /** Маркер цели навигации — отдельная бирюзовая иконка, не связана с
     * маркером точки входа или отмеченных мест. */
    fun updateNavigationTargetMarker(target: Pair<Double, Double>?) {
        val mapView = this.mapView ?: return
        navigationTargetMarker?.let { mapView.layerManager.layers.remove(it) }
        navigationTargetMarker = null
        if (target == null) return
        val pinSizePx = (36 * density).toInt()
        // Поворот 0° — createRotatedMarkerBitmap здесь используется просто
        // как готовый helper "нарисовать drawable в битмап нужного размера".
        val bitmap = createRotatedMarkerBitmap(R.drawable.ic_navigation_target_marker, pinSizePx, 0f)
        bitmap.incrementRefCount()
        val marker = Marker(LatLong(target.first, target.second), bitmap, 0, 0)
        mapView.layerManager.layers.add(marker)
        navigationTargetMarker = marker
    }
    fun canZoomIn(): Boolean {
        val position = mapView?.model?.mapViewPosition ?: return false
        return position.zoomLevel < position.zoomLevelMax
    }
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
        followModeEnabled = true
        mapView?.model?.mapViewPosition?.center = LatLong(location.latitude, location.longitude)
    }
    fun onDestroy() {
        tileRendererLayer?.let { it.mapDataStore.close() }
        tileDownloadLayer?.onDestroy()
        mapView?.destroyAll()
        AndroidGraphicFactory.clearResourceMemoryCache()
    }
    companion object {
        private const val ACCENT_COLOR_MAPSFORGE = 0xFFE65100.toInt()
        // НОВОЕ: цвет для "взятия направления" — бирюзовый, чтобы визуально
        // не путать с оранжевой линией/стрелкой режима "Домой".
        private const val NAVIGATION_TARGET_COLOR = 0xFF40E0D0.toInt()
        private const val ZOOM_LEVEL_MIN: Byte = 2
        // Сколько промежуточных точек генерируется на один отрезок трека при
        // сглаживании Catmull-Rom. Больше — плавнее кривая, но больше точек
        // на длинных маршрутах (влияет на нагрузку перерисовки при частых
        // обновлениях во время активной записи).
        private const val SMOOTHING_SEGMENTS = 6
        private const val ZOOM_LEVEL_MAX: Byte = 20
        private const val ZOOM_LEVEL_MAX_ONLINE: Byte = 18
        private const val DEFAULT_ZOOM_LEVEL: Byte = 15
        // 1.5x от исходных 32dp (см. решение по ТЗ)
        private const val MARKER_SIZE_DP = 48
    }
}
