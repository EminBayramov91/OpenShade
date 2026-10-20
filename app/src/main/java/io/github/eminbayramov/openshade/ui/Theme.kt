package io.github.eminbayramov.openshade.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme

/** Wallpaper-based Material You colors on Android 12+, a neutral dark scheme otherwise. */
fun shadeColors(context: Context): ColorScheme =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else darkColorScheme()
