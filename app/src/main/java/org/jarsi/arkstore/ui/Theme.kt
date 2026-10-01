package org.jarsi.arkstore.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Built around the ARK mark: deep navy, sea teal and the amber hull.
private val LightColors = lightColorScheme(
    primary = Color(0xFF00696D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F4),
    onPrimaryContainer = Color(0xFF002021),
    secondary = Color(0xFF16324F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E4FF),
    onSecondaryContainer = Color(0xFF0A1D33),
    tertiary = Color(0xFF7C5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF271900),
    background = Color(0xFFF7FAFA),
    onBackground = Color(0xFF171D1D),
    surface = Color(0xFFF7FAFA),
    onSurface = Color(0xFF171D1D),
    surfaceVariant = Color(0xFFDAE4E5),
    onSurfaceVariant = Color(0xFF3F4949),
    surfaceContainer = Color(0xFFEBEFEF),
    surfaceContainerHigh = Color(0xFFE5E9E9),
    surfaceContainerLow = Color(0xFFF1F4F4),
    outline = Color(0xFF6F7979),
    outlineVariant = Color(0xFFBEC8C9)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D4D8),
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF004F52),
    onPrimaryContainer = Color(0xFF9CF1F4),
    secondary = Color(0xFFA9C8F0),
    onSecondary = Color(0xFF0B3152),
    secondaryContainer = Color(0xFF27486A),
    onSecondaryContainer = Color(0xFFD3E4FF),
    tertiary = Color(0xFFFFC24B),
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF5E4200),
    onTertiaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFF0F1717),
    onBackground = Color(0xFFDEE4E4),
    surface = Color(0xFF0F1717),
    onSurface = Color(0xFFDEE4E4),
    surfaceVariant = Color(0xFF3F4949),
    onSurfaceVariant = Color(0xFFBEC8C9),
    surfaceContainer = Color(0xFF1B2323),
    surfaceContainerHigh = Color(0xFF252D2E),
    surfaceContainerLow = Color(0xFF171F1F),
    outline = Color(0xFF899393),
    outlineVariant = Color(0xFF3F4949)
)

@Composable
fun ArkStoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
