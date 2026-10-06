package org.jarsi.arkstore.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class PalettesTest {

    // The WCAG 2 relative luminance, computed here on its own so that the test does not lean
    // on the code it checks.
    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val seeds = listOf(150f to 0.09f, 240f to 0.05f, 10f to 0.12f, 275f to 0.12f, 150f to 0.01f)

    @Test
    fun toneIsTheLightnessOfTheColor() {
        // Tone 50 is L* 50, whose luminance is 0.184; the darkest and lightest tones are black
        // and white; the tones in between get lighter all the way.
        for ((hue, chroma) in seeds) {
            val tones = Tones.of(hue, chroma)
            assertEquals("tone 50 of $hue", 0.184, luminance(tones[50]), 0.006)
            assertEquals("tone 10 of $hue", 0.0113, luminance(tones[10]), 0.002)
            assertEquals("tone 90 of $hue", 0.763, luminance(tones[90]), 0.008)
            assertEquals(Color.Black, tones[0])
            assertEquals(Color.White, tones[100])
            Tones.TONES.toList().zipWithNext { lower, upper ->
                assertTrue("$lower < $upper of $hue", luminance(tones[lower]) < luminance(tones[upper]))
            }
        }
    }

    @Test
    fun aToneBetweenTwoListedOnesLiesBetweenThem() {
        val tones = Tones.of(240f, 0.05f)
        val between = luminance(tones.at(92f))
        assertTrue(between > luminance(tones[90]) && between < luminance(tones[95]))
    }

    private fun assertReadable(name: String, scheme: ColorScheme) {
        fun text(what: String, fg: Color, bg: Color) {
            val ratio = contrast(fg, bg)
            assertTrue("$name: $what is ${"%.2f".format(ratio)}:1, below 7:1", ratio >= 7.0)
        }
        fun mark(what: String, fg: Color, bg: Color) {
            val ratio = contrast(fg, bg)
            assertTrue("$name: $what is ${"%.2f".format(ratio)}:1, below 3:1", ratio >= 3.0)
        }
        text("onPrimary/primary", scheme.onPrimary, scheme.primary)
        text("onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer)
        text("onSecondary/secondary", scheme.onSecondary, scheme.secondary)
        text("onSecondaryContainer/secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer)
        text("onTertiary/tertiary", scheme.onTertiary, scheme.tertiary)
        text("onTertiaryContainer/tertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer)
        text("onError/error", scheme.onError, scheme.error)
        text("onErrorContainer/errorContainer", scheme.onErrorContainer, scheme.errorContainer)
        text("onSurfaceVariant/surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant)
        text("inverseOnSurface/inverseSurface", scheme.inverseOnSurface, scheme.inverseSurface)
        text("inversePrimary/inverseSurface", scheme.inversePrimary, scheme.inverseSurface)
        text("onBackground/background", scheme.onBackground, scheme.background)
        val surfaces = mapOf(
            "background" to scheme.background,
            "surface" to scheme.surface,
            "surfaceDim" to scheme.surfaceDim,
            "surfaceBright" to scheme.surfaceBright,
            "surfaceContainerLowest" to scheme.surfaceContainerLowest,
            "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainer" to scheme.surfaceContainer,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest
        )
        for ((surface, color) in surfaces) {
            text("onSurface/$surface", scheme.onSurface, color)
            text("onSurfaceVariant/$surface", scheme.onSurfaceVariant, color)
            text("primary/$surface", scheme.primary, color)
            text("secondary/$surface", scheme.secondary, color)
            text("tertiary/$surface", scheme.tertiary, color)
            text("error/$surface", scheme.error, color)
            mark("outline/$surface", scheme.outline, color)
        }
        mark("outlineVariant/background", scheme.outlineVariant, scheme.background)
        mark("outlineVariant/surface", scheme.outlineVariant, scheme.surface)
    }

    @Test
    fun everyPaletteReadsAtTripleAInLightAndDark() {
        for (palette in Palette.entries.filter { it != Palette.WALLPAPER }) {
            val tones = TonalPalettes.of(palette)
            for (dark in listOf(false, true)) {
                for (black in listOf(false, true)) {
                    assertReadable(
                        "$palette dark=$dark black=$black",
                        Palettes.colorScheme(palette, tones, dark = dark, black = black)
                    )
                }
            }
        }
    }

    @Test
    fun theArkPaletteKeepsItsOwnColors() {
        val tones = TonalPalettes.of(Palette.ARK)
        assertEquals(Color(0xFF004F52), Palettes.colorScheme(Palette.ARK, tones, dark = false, black = false).primary)
        assertEquals(Color(0xFF8EE0E4), Palettes.colorScheme(Palette.ARK, tones, dark = true, black = false).primary)
    }

    @Test
    fun pureBlackPutsTheDarkThemeOnBlackAndLeavesTheLightOne() {
        for (palette in Palette.entries.filter { it != Palette.WALLPAPER }) {
            val tones = TonalPalettes.of(palette)
            val dark = Palettes.colorScheme(palette, tones, dark = true, black = true)
            assertEquals("$palette background", Color.Black, dark.background)
            assertEquals("$palette surface", Color.Black, dark.surface)
            assertEquals("$palette lowest", Color.Black, dark.surfaceContainerLowest)
            val plain = Palettes.colorScheme(palette, tones, dark = true, black = false)
            assertTrue(
                "$palette containers stay apart",
                luminance(dark.surfaceContainerLow) < luminance(dark.surfaceContainer) &&
                    luminance(dark.surfaceContainer) < luminance(dark.surfaceContainerHigh) &&
                    luminance(dark.surfaceContainerHigh) < luminance(dark.surfaceContainerHighest) &&
                    luminance(dark.surfaceContainerHighest) < luminance(plain.surfaceContainerHighest)
            )
            val light = Palettes.colorScheme(palette, tones, dark = false, black = true)
            val plainLight = Palettes.colorScheme(palette, tones, dark = false, black = false)
            assertEquals("$palette light background", plainLight.background, light.background)
            assertEquals("$palette light containers", plainLight.surfaceContainerHighest, light.surfaceContainerHighest)
        }
    }

    @Test
    fun systemTonesReadAtTripleAThroughTheSameRoles() {
        // The wallpaper palette arrives as thirteen tones per hue from the system, and goes
        // through the same role mapping; a palette of the same shape stands in for it here.
        val source = Tones.of(185f, 0.06f)
        val listed = Tones.from(Tones.TONES.associateWith { source[it] })
        val neutral = Tones.of(185f, 0.012f)
        val system = TonalPalettes(listed, listed, listed, neutral, neutral)
        for (dark in listOf(false, true)) {
            assertReadable("system dark=$dark", Palettes.colorScheme(Palette.WALLPAPER, system, dark = dark, black = false))
        }
    }

    @Test
    fun theSavedPaletteIsReadBackAndTheWallpaperOneNeedsAndroid12() {
        assertEquals(Palette.FOREST, Palette.fromKey("forest", sdk = 26))
        assertEquals(Palette.WALLPAPER, Palette.fromKey("wallpaper", sdk = 31))
        assertEquals(Palette.ARK, Palette.fromKey("wallpaper", sdk = 30))
        assertEquals(Palette.ARK, Palette.fromKey("plaid", sdk = 31))
        assertEquals(Palette.ARK, Palette.fromKey(null, sdk = 31))
    }
}
