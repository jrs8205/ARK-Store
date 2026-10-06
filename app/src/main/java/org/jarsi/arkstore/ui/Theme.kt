package org.jarsi.arkstore.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

internal const val PREFS_UI = "ui"
internal const val PREF_PALETTE = "palette"
internal const val PREF_BLACK = "black"

/** The theme chosen in the settings, kept up to date as the settings change. */
class ThemeSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)

    var palette by mutableStateOf(Palette.fromKey(preferences.getString(PREF_PALETTE, null)))
        private set

    var black by mutableStateOf(preferences.getBoolean(PREF_BLACK, false))
        private set

    // Held here because the preferences keep their listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            PREF_PALETTE -> palette = Palette.fromKey(prefs.getString(PREF_PALETTE, null))
            PREF_BLACK -> black = prefs.getBoolean(PREF_BLACK, false)
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }
}

/** The palette chosen in the settings, light or dark as the Android settings say. */
@Composable
fun ArkStoreTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = remember { ThemeSettings(context) }
    val dark = isSystemInDarkTheme()
    val scheme = remember(settings.palette, dark, settings.black) {
        colorSchemeOf(context, settings.palette, dark, settings.black)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** The scheme of [palette] on this device; the wallpaper's tones come from the system. */
fun colorSchemeOf(context: Context, palette: Palette, dark: Boolean, black: Boolean): ColorScheme {
    val tones = if (palette == Palette.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        TonalPalettes.system(context)
    } else {
        TonalPalettes.of(if (palette == Palette.WALLPAPER) Palette.ARK else palette)
    }
    return Palettes.colorScheme(palette, tones, dark, black)
}

/**
 * The background of the chosen theme, for the window to show until the first frame is drawn;
 * the window's own background is the ARK palette's.
 */
fun windowBackground(context: Context): Int {
    val preferences = context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return colorSchemeOf(
        context,
        Palette.fromKey(preferences.getString(PREF_PALETTE, null)),
        dark = night == Configuration.UI_MODE_NIGHT_YES,
        black = preferences.getBoolean(PREF_BLACK, false)
    ).background.toArgb()
}
