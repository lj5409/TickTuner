package com.gtool.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0E1210)
val Surface1 = Color(0xFF161D19)
val Surface2 = Color(0xFF1F2A25)
val Surface3 = Color(0xFF2A3832)
val Accent = Color(0xFF2EE6A8)
val AccentDim = Color(0xFF1B8F68)
val InfoBlue = Color(0xFF7CC4FF)
val Warm = Color(0xFFFFB86B)
val Danger = Color(0xFFFF5A5F)
val TextMain = Color(0xFFE9F2ED)
val TextMuted = Color(0xFF90A69B)
val RootRed = Color(0xFFFF4D6D)
val ChordBlue = Color(0xFF4DA3FF)
val FretWire = Color(0xFF3A4A43)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04211A),
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFE9FFF6),
    secondary = InfoBlue,
    onSecondary = Color(0xFF04121F),
    tertiary = Warm,
    onTertiary = Color(0xFF2A1600),
    background = Bg,
    onBackground = TextMain,
    surface = Surface1,
    onSurface = TextMain,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    outline = FretWire,
    error = Danger,
    onError = Color(0xFF2A0004)
)

@Composable
fun GToolTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 乐器工具在暗环境下使用更多，固定深色主题
    MaterialTheme(
        colorScheme = DarkColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
