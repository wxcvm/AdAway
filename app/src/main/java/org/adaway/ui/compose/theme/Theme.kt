package org.adaway.ui.compose.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/*
 * Material 3 theme for AdAway.
 *
 * Uses dynamic color (Monet) on Android 12+; falls back to a fixed
 * professional palette (teal accent, "blocking = good") elsewhere.
 * Dark mode follows the system setting.
 *
 * 界面美化：统一 8/12/16/24dp 圆角，并把中性色/容器色补齐，
 * 卡片、芯片、对话框不再是一整片直角灰块。
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B7F6B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA8F2DE),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8DF),
    onSecondaryContainer = Color(0xFF062019),
    tertiary = Color(0xFF456179),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCBE6FF),
    onTertiaryContainer = Color(0xFF001E31),
    background = Color(0xFFF6FBF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF6FBF8),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F6F3),
    surfaceContainer = Color(0xFFEAF1ED),
    surfaceContainerHigh = Color(0xFFE4EBE7),
    surfaceContainerHighest = Color(0xFFDEE5E1),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C4),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD5C2),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Color(0xFFA8F2DE),
    secondary = Color(0xFFB1CCC3),
    onSecondary = Color(0xFF1C352E),
    secondaryContainer = Color(0xFF334B45),
    onSecondaryContainer = Color(0xFFCCE8DF),
    tertiary = Color(0xFFADCAE5),
    onTertiary = Color(0xFF143349),
    tertiaryContainer = Color(0xFF2C4A61),
    onTertiaryContainer = Color(0xFFCBE6FF),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDDE4E0),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFDDE4E0),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C4),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B29),
    surfaceContainerHighest = Color(0xFF303634),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** 统一圆角：小控件 12dp、卡片 16dp、大容器/对话框 24dp。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AdAwayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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
        shapes = AppShapes,
        content = content,
    )
}
