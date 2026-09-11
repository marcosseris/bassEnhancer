package com.marcos.bassenhancer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Accent = Color(0xFF7C5CFF)
private val AccentDark = Color(0xFFB9A6FF)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    secondary = Color(0xFF6EE7C8),
    tertiary = Color(0xFFFF8A65),
)

private val LightColors = lightColorScheme(
    primary = Accent,
    secondary = Color(0xFF00806A),
    tertiary = Color(0xFFC04A22),
)

@Composable
fun BassEnhancerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Pixels ship Material You; borrow the wallpaper palette where it exists.
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) DarkColors else LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
