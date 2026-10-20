@file:SuppressLint("RestrictedApi")

package io.github.eminbayramov.openshade.ui

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.graphics.Color
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.TonalPalette
import io.github.eminbayramov.openshade.AppSettings

/**
 * Picks the panel colors.
 *
 * Some ROMs (XOS included) feed the system Material You palette from their theme engine
 * rather than from the wallpaper, so "wallpaper" mode builds its own Tonal Spot scheme
 * from the wallpaper's dominant color, the same way launchers like Lawnchair do.
 */
fun shadeColors(context: Context, source: AppSettings.ColorSource = AppSettings(context).colorSource): ColorScheme {
    if (source == AppSettings.ColorSource.WALLPAPER) {
        wallpaperSeed(context)?.let { return schemeFromSeed(it) }
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else darkColorScheme()
}

private fun wallpaperSeed(context: Context): Int? = runCatching {
    WallpaperManager.getInstance(context)
        .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        ?.primaryColor
        ?.toArgb()
}.getOrNull()

/** Dark Material 3 scheme using the Tonal Spot palette rules. */
fun schemeFromSeed(seed: Int): ColorScheme {
    val hue = Hct.fromInt(seed).hue
    val primary = TonalPalette.fromHueAndChroma(hue, 36.0)
    val secondary = TonalPalette.fromHueAndChroma(hue, 16.0)
    val tertiary = TonalPalette.fromHueAndChroma((hue + 60.0) % 360.0, 24.0)
    val neutral = TonalPalette.fromHueAndChroma(hue, 6.0)
    val neutralVariant = TonalPalette.fromHueAndChroma(hue, 8.0)
    fun TonalPalette.c(tone: Int) = Color(tone(tone))

    return darkColorScheme(
        primary = primary.c(80),
        onPrimary = primary.c(20),
        primaryContainer = primary.c(30),
        onPrimaryContainer = primary.c(90),
        secondary = secondary.c(80),
        onSecondary = secondary.c(20),
        secondaryContainer = secondary.c(30),
        onSecondaryContainer = secondary.c(90),
        tertiary = tertiary.c(80),
        onTertiary = tertiary.c(20),
        tertiaryContainer = tertiary.c(30),
        onTertiaryContainer = tertiary.c(90),
        background = neutral.c(6),
        onBackground = neutral.c(90),
        surface = neutral.c(6),
        onSurface = neutral.c(90),
        surfaceVariant = neutralVariant.c(30),
        onSurfaceVariant = neutralVariant.c(80),
        surfaceContainerLowest = neutral.c(4),
        surfaceContainerLow = neutral.c(10),
        surfaceContainer = neutral.c(12),
        surfaceContainerHigh = neutral.c(17),
        surfaceContainerHighest = neutral.c(22),
        outline = neutralVariant.c(60),
        outlineVariant = neutralVariant.c(30),
    )
}
