package com.trailback.app.ui.compass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.trailback.app.R

/**
 * Полноэкранный компас (п.6.2 ТЗ), реализующий подтверждённую механику:
 * - вращающийся диск (градусы + буквы N/E/S/W или С/Ю/З/В) — угол = -heading;
 * - фиксированный треугольник курса статичен сверху в обоих режимах;
 * - в обычном режиме в центре текст курса+буквы, в режиме "Домой" центр пуст;
 * - красная стрелка на точку старта — только в режиме "Домой", независимый угол
 *   (bearingToHome - heading), исчезает по достижении точки.
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { NORMAL, RETURNING }

    var mode: Mode = Mode.NORMAL
        set(value) { field = value; invalidate() }

    /** Текущий курс устройства, градусы 0..360. */
    var headingDegrees: Float = 0f
        set(value) { field = value; invalidate() }

    /**
     * Финальный угол поворота стрелки на экране (уже с учётом курса и
     * повторного сглаживания перехода через 0°/360° — см. CompassActivity).
     * Актуально только в режиме RETURNING.
     */
    var arrowScreenAngleDegrees: Float = 0f
        set(value) { field = value; invalidate() }

    private val isRussian = context.resources.configuration.locales[0].language == "ru"

    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        strokeWidth = 3f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 85f
    }
    private val headingTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 125f
        isFakeBoldText = true
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textAlign = Paint.Align.CENTER
        textSize = 65f
    }
    private val courseTrianglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val homeArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT_COLOR
        style = Paint.Style.FILL
    }
    private val homeArrowShadedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 139, 0, 0)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f * 0.85f

        canvas.save()
        canvas.rotate(-headingDegrees, cx, cy)
        drawDial(canvas, cx, cy, radius)
        canvas.restore()

        drawFixedCourseTriangle(canvas, cx, cy, radius)

        if (mode == Mode.NORMAL) {
            drawCenterHeadingText(canvas, cx, cy)
        } else {
            drawHomeArrow(canvas, cx, cy, radius)
        }
    }

    private fun drawDial(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, dialPaint)

        for (deg in 0 until 360 step 10) {
            val angleRad = Math.toRadians((deg - 90).toDouble())
            val outer = radius
            val inner = if (deg % 90 == 0) radius - 30f else radius - 15f
            val x1 = cx + (outer * Math.cos(angleRad)).toFloat()
            val y1 = cy + (outer * Math.sin(angleRad)).toFloat()
            val x2 = cx + (inner * Math.cos(angleRad)).toFloat()
            val y2 = cy + (inner * Math.sin(angleRad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        val labels = if (isRussian) listOf("С", "В", "Ю", "З") else listOf("N", "E", "S", "W")
        val labelAngles = listOf(0, 90, 180, 270)
        for (i in labelAngles.indices) {
            val angleRad = Math.toRadians((labelAngles[i] - 90).toDouble())
            val labelRadius = radius - 55f
            val x = cx + (labelRadius * Math.cos(angleRad)).toFloat()
            val y = cy + (labelRadius * Math.sin(angleRad)).toFloat() + 14f
            canvas.save()
            canvas.rotate(headingDegrees, x, y) // буквы остаются читаемыми (не переворачиваются)
            canvas.drawText(labels[i], x, y, labelPaint)
            canvas.restore()
        }
    }

    private fun drawFixedCourseTriangle(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val path = Path().apply {
            moveTo(cx, cy - radius - 10f)
            lineTo(cx - 20f, cy - radius + 25f)
            lineTo(cx + 20f, cy - radius + 25f)
            close()
        }
        canvas.drawPath(path, courseTrianglePaint)
    }

    private fun drawCenterHeadingText(canvas: Canvas, cx: Float, cy: Float) {
        val roundedHeading = ((headingDegrees + 0.5f).toInt()) % 360
        canvas.drawText(roundedHeading.toString(), cx, cy, headingTextPaint)
        canvas.drawText(compassPointLabel(headingDegrees), cx, cy + 80f, subLabelPaint)
    }

    private fun drawHomeArrow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        canvas.save()
        canvas.rotate(arrowScreenAngleDegrees, cx, cy)

        val length = radius * 0.75f
        val width = radius * 0.35f
        val path = Path().apply {
            moveTo(cx, cy - length)
            lineTo(cx - width, cy + length * 0.4f)
            lineTo(cx, cy + length * 0.15f)
            lineTo(cx + width, cy + length * 0.4f)
            close()
        }
        canvas.drawPath(path, homeArrowShadedPaint)
        canvas.drawPath(path, homeArrowPaint)
        canvas.restore()
    }

    private fun compassPointLabel(heading: Float): String {
        val pointsRu = listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
        val pointsEn = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val points = if (isRussian) pointsRu else pointsEn
        val index = (((heading + 22.5f) / 45f).toInt()) % 8
        return points[index]
    }

    companion object {
        private const val ACCENT_COLOR = 0xFFE65100.toInt()
    }
}
