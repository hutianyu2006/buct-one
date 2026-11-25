package com.buct.one.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 与 Android 版 ui/theme/Color.kt 共用同一套品牌色（iOS systemBlue）。 */
val BrandPrimary: Color = Color(0xFF0A84FF)

val WarningOrange: Color = Color(0xFFE58A00)
val DangerRed: Color = Color(0xFFC73A2F)
val SuccessGreen: Color = Color(0xFF1F845A)

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E9FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF5C6772),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF101828),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFECEFF5),
    onSurfaceVariant = Color(0xFF667085),
    outline = Color(0xFFD5DCE7),
    error = DangerRed,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6BB4FF),
    onPrimary = Color(0xFF002E5C),
    primaryContainer = Color(0xFF003F78),
    onPrimaryContainer = Color(0xFFD6E9FF),
    background = Color(0xFF0F1218),
    onBackground = Color(0xFFE8ECF3),
    surface = Color(0xFF171B22),
    onSurface = Color(0xFFE8ECF3),
    surfaceVariant = Color(0xFF222833),
    onSurfaceVariant = Color(0xFFB3BDCC),
    outline = Color(0xFF344054),
    error = Color(0xFFFF8A80),
)

@Composable
fun BuctOneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
