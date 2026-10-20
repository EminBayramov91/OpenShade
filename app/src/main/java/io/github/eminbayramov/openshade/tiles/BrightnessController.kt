package io.github.eminbayramov.openshade.tiles

import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import kotlin.math.pow

/**
 * Maps the slider position (0..1) to the system brightness setting.
 * A gamma curve is used so the slider feels linear to the eye, like the stock one.
 */
class BrightnessController(private val context: Context) {

    private val maxBrightness: Int = run {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        if (id != 0) res.getInteger(id).coerceAtLeast(1) else 255
    }

    val canWrite: Boolean get() = Settings.System.canWrite(context)

    var isAuto: Boolean
        get() = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        set(value) {
            if (!canWrite) return
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (value) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }

    fun read(): Float {
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, maxBrightness / 2)
        return (raw.toFloat() / maxBrightness).coerceIn(0f, 1f).pow(1f / GAMMA)
    }

    fun write(position: Float) {
        if (!canWrite) return
        // Manual mode is required for the value to take effect on most devices.
        if (isAuto) isAuto = false
        val raw = (position.coerceIn(0f, 1f).pow(GAMMA) * maxBrightness).toInt().coerceIn(1, maxBrightness)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
    }

    private companion object {
        const val GAMMA = 2.2f
    }
}
