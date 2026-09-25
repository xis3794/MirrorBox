package io.github.xis3794.mirrorbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.Prefs

private val DarkScheme = darkColorScheme(
    primary = IceBlue,
    onPrimary = Color(0xFF00272F),
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = Violet,
    onSecondary = Color(0xFF1A1030),
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = Color(0xFFE2DAFF),
    tertiary = Mint,
    onTertiary = Color(0xFF00281C),
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = Color(0xFFB6F5DF),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = Coral,
    onError = Color(0xFF2A0A0E),
)

private val LightScheme = lightColorScheme(
    primary = IceBlueDeep,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = VioletDeep,
    onSecondary = Color.White,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = Color(0xFF251A4D),
    tertiary = MintDeep,
    onTertiary = Color.White,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = Color(0xFF00382A),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = CoralDeep,
    onError = Color.White,
)

val MbShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun MirrorBoxTheme(content: @Composable () -> Unit) {
    val dark = when (Prefs.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = MbTypography,
        shapes = MbShapes,
        content = content,
    )
}

/** True when the current scheme is a dark one (used by the glass surfaces). */
@Composable
fun isDarkScheme(): Boolean {
    val bg = MaterialTheme.colorScheme.background
    return bg.red * 0.299f + bg.green * 0.587f + bg.blue * 0.114f < 0.5f
}