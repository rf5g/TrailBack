package com.trailback.app.ui.compass
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
/**
 * Мини-компас в левом верхнем углу карты (см. решение по ТЗ):
 * только одна вращающаяся стрелка, указывающая на север — без циферблата,
 * делений, цифр и букв. По тапу открывает полноэкранный компас
 * (обработка тапа — в контейнере, см. MapActivity).
 */
class MiniCompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    /** Текущий курс устройства, градусы 0..360. Стрелка поворачивается на -heading. */
    var headingDegrees: Float = 0f
        set(value) { field = value; invalidate() }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT_COLOR
        style = Paint.Style.FILL
    }
    private val arrowShadedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xA08B0000.toInt()
        style = Paint.Style.FILL
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val length = minOf(width, height) / 2f * 0.9f
        val armWidth = length * 0.4f
        canvas.save()
        canvas.rotate(-headingDegrees, cx, cy)
        val path = Path().apply {
            moveTo(cx, cy - length)
            lineTo(cx - armWidth, cy + length * 0.5f)
            lineTo(cx, cy + length * 0.2f)
            lineTo(cx + armWidth, cy + length * 0.5f)
            close()
        }
        canvas.drawPath(path, arrowShadedPaint)
        canvas.drawPath(path, arrowPaint)
        canvas.restore()
    }
    companion object {
        private const val ACCENT_COLOR = 0xFFE65100.toInt()
    }
}
