package com.trailback.app.ui.compass

/** Единая нормализация угла в диапазон [0, 360) — используется обоими
 *  слоями компаса (курс устройства и стрелка на точку старта), чтобы
 *  не дублировать (x + 360f) % 360f в разных местах. */
object AzimuthNormalizer {
    fun normalize(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f
}
