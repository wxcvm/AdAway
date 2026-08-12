package org.adaway.ui.compose.theme

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

/*
 * Material 3 theme for AdAway.
 *
 * Uses dynamic color (Monet) on Android 12+ like Shizuku and other
 * modern system tools; falls back to a fixed professional palette
 * (green accent, matching the "blocking = good" semantic) elsewhere.
 * Dark mode follows the system setting.
 */

// Fixed fallback palette (used pre-Android 12 or when dynamic color is off)
private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),        // Material Green 800
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7EFB4),
    onPrimaryContainer = Color(0xFF002105),
    secondary = Color(0xFF52634F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E8CF),
    onSecondaryContainer = Color(0xFF101F10),
    tertiary = Color(0xFF38656B),
    background = Color(0xFFFCFDF6),
    surface = Color(0xFFFCFDF6),
    surfaceVariant = Color(0xFFDEE5D9),
    onSurfaceVariant = Color(0xFF414941),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD599),
    onPrimary = Color(0xFF003909),
    primaryContainer = Color(0xFF16531E),
    onPrimaryContainer = Color(0xFFB7EFB4),
    secondary = Color(0xFFB9CCB4),
    onSecondary = Color(0xFF253424),
    secondaryContainer = Color(0xFF3B4B39),
    onSecondaryContainer = Color(0xFFD5E8CF),
    tertiary = Color(0xFF9CCDD3),
    background = Color(0xFF101410),
    surface = Color(0xFF101410),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC1C9BD),
    error = Color(0xFFFFB4AB),
)

@Composable
fun AdAwayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is the professional default on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}