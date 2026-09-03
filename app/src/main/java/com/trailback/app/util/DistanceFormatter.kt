package com.trailback.app.util
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import java.util.Locale
/**
 * Общая логика форматирования дистанции — используется и на экране карты,
 * и на экране компаса (см. решение по ТЗ: панель должна дублироваться на
 * обоих экранах с одинаковым видом). Единица измерения рисуется настоящим
 * надстрочным индексом ("0ᵐ"), как на референсе.
 */
object DistanceFormatter {
    /** м → км при >1000, с одним знаком после запятой (п.6.1 ТЗ). */
    fun format(meters: Float): Spannable {
        val (valueText, unitText) = if (meters > 1000f) {
            String.format(Locale.getDefault(), "%.1f", meters / 1000f) to "км"
        } else {
            String.format(Locale.getDefault(), "%.0f", meters) to "м"
        }
        val full = valueText + unitText
        return SpannableString(full).apply {
            val unitStart = valueText.length
            val unitEnd = full.length
            setSpan(SuperscriptSpan(), unitStart, unitEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.45f), unitStart, unitEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
