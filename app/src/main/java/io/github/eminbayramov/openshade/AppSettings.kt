package io.github.eminbayramov.openshade

import android.content.Context
import android.content.SharedPreferences

/** User-facing appearance options, shared between the setup screen and the service. */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    enum class ColorSource { WALLPAPER, SYSTEM }

    var colorSource: ColorSource
        get() = runCatching { ColorSource.valueOf(prefs.getString(KEY_COLORS, null)!!) }
            .getOrDefault(ColorSource.WALLPAPER)
        set(value) = prefs.edit().putString(KEY_COLORS, value.name).apply()

    /** Panel background opacity, 0.4..1.0. */
    var opacity: Float
        get() = prefs.getFloat(KEY_OPACITY, 0.85f)
        set(value) = prefs.edit().putFloat(KEY_OPACITY, value.coerceIn(MIN_OPACITY, 1f)).apply()

    /** Blur the content behind the panel where the device supports it. */
    var blur: Boolean
        get() = prefs.getBoolean(KEY_BLUR, true)
        set(value) = prefs.edit().putBoolean(KEY_BLUR, value).apply()

    /** Hide the stock quick settings panel while OpenShade runs (needs Shizuku). */
    var hideStockQuickSettings: Boolean
        get() = prefs.getBoolean(KEY_HIDE_STOCK_QS, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_STOCK_QS, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val MIN_OPACITY = 0.4f
        private const val KEY_COLORS = "color_source"
        private const val KEY_OPACITY = "opacity"
        private const val KEY_BLUR = "blur"
        private const val KEY_HIDE_STOCK_QS = "hide_stock_qs"
    }
}
