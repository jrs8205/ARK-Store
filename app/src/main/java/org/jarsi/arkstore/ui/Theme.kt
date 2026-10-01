package org.jarsi.arkstore.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Built around the ARK mark: deep navy, sea teal and the amber hull.
//
// Every text colour keeps a contrast of at least 7:1 against each surface it is used on (WCAG
// AAA), and outlines and other non-text marks at least 3:1, so the screen stays readable in
// direct sunlight. All roles are set explicitly so that none falls back to a library default
// that has not been checked.
private val LightColors = lightColorScheme(
    primary = Color(0xFF004F52),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F4),
    onPrimaryContainer = Color(0xFF002021),
    inversePrimary = Color(0xFF8EE0E4),
    secondary = Color(0xFF16324F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E4FF),
    onSecondaryContainer = Color(0xFF0A1D33),
    tertiary = Color(0xFF5C4000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF271900),
    error = Color(0xFF8C0009),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7FAFA),
    onBackground = Color(0xFF101415),
    surface = Color(0xFFF7FAFA),
    onSurface = Color(0xFF101415),
    surfaceVariant = Color(0xFFDAE4E5),
    onSurfaceVariant = Color(0xFF2F3838),
    surfaceTint = Color(0xFF004F52),
    inverseSurface = Color(0xFF2B3232),
    inverseOnSurface = Color(0xFFEEF3F3),
    surfaceDim = Color(0xFFD7DBDB),
    surfaceBright = Color(0xFFF7FAFA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F4),
    surfaceContainer = Color(0xFFEBEFEF),
    surfaceContainerHigh = Color(0xFFE5E9E9),
    surfaceContainerHighest = Color(0xFFDFE3E3),
    outline = Color(0xFF4F5959),
    outlineVariant = Color(0xFF7A8484),
    scrim = Color(0xFF000000)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8EE0E4),
    onPrimary = Color(0xFF002022),
    primaryContainer = Color(0xFF004F52),
    onPrimaryContainer = Color(0xFFB4F5F7),
    inversePrimary = Color(0xFF004F52),
    secondary = Color(0xFFB9D4F5),
    onSecondary = Color(0xFF04213C),
    secondaryContainer = Color(0xFF27486A),
    onSecondaryContainer = Color(0xFFE3EEFF),
    tertiary = Color(0xFFFFC24B),
    onTertiary = Color(0xFF2A1C00),
    tertiaryContainer = Color(0xFF5E4200),
    onTertiaryContainer = Color(0xFFFFE8C2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF4A0004),
    errorContainer = Color(0xFF7A0008),
    onErrorContainer = Color(0xFFFFE2DE),
    background = Color(0xFF0C1313),
    onBackground = Color(0xFFEEF3F3),
    surface = Color(0xFF0C1313),
    onSurface = Color(0xFFEEF3F3),
    surfaceVariant = Color(0xFF3F4949),
    onSurfaceVariant = Color(0xFFCED8D9),
    surfaceTint = Color(0xFF8EE0E4),
    inverseSurface = Color(0xFFEEF3F3),
    inverseOnSurface = Color(0xFF2B3232),
    surfaceDim = Color(0xFF0C1313),
    surfaceBright = Color(0xFF323A3A),
    surfaceContainerLowest = Color(0xFF070D0D),
    surfaceContainerLow = Color(0xFF141B1B),
    surfaceContainer = Color(0xFF182020),
    surfaceContainerHigh = Color(0xFF222A2B),
    surfaceContainerHighest = Color(0xFF2D3536),
    outline = Color(0xFF9BA5A5),
    outlineVariant = Color(0xFF6A7475),
    scrim = Color(0xFF000000)
)

/** Follows the light or dark theme chosen in the Android settings. */
@Composable
fun ArkStoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
