package com.trailback.app.data.repository

import android.content.Context
import androidx.core.content.edit

enum class NorthMode { TRUE, MAGNETIC }

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** По умолчанию — магнитный север (решение изменено, было истинный). */
    var northMode: NorthMode
        get() = NorthMode.valueOf(prefs.getString(KEY_NORTH_MODE, NorthMode.MAGNETIC.name)!!)
        set(value) = prefs.edit { putString(KEY_NORTH_MODE, value.name) }

    /** "ru" или "en" — единственное место переключения языка во всём приложении. */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "ru")!!
        set(value) = prefs.edit { putString(KEY_LANGUAGE, value) }

    /** Путь (URI) к папке с офлайн-картами (.map), выбранной через SAF. Пусто — не выбрана. */
    var offlineMapsUri: String?
        get() = prefs.getString(KEY_OFFLINE_MAPS_URI, null)
        set(value) = prefs.edit { putString(KEY_OFFLINE_MAPS_URI, value) }

    companion object {
        private const val KEY_NORTH_MODE = "north_mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_OFFLINE_MAPS_URI = "offline_maps_uri"
    }
}
