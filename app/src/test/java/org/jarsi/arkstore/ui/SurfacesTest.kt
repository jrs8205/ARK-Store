package org.jarsi.arkstore.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfacesTest {

    @Test
    fun theShaderIsHeldToTheLimitsThePalettesCountOn() {
        assertTrue(Surfaces.METAL.contains("half up = mix(lum > 0.5 ? 0.2 : 0.02, 0.25, rim);"))
        assertTrue(Surfaces.METAL.contains("half down = mix(lum > 0.5 ? 0.05 : 0.2, 0.25, rim);"))
    }

    @Test
    fun aSurfaceUnderTextIsAtTheEndOfItsRangeNearestToTheText() {
        // A dark surface carries light text and is at its brightest; a light one at its darkest.
        assertEquals(Color(0xFF152535), Surfaces.underText(Color(0xFF102030)))
        assertEquals(Color(0xFFD3D3D3), Surfaces.underText(Color(0xFFE0E0E0)))
    }

    @Test
    fun pureBlackIsNotDrawnAsMetal() {
        // The highlight of the metal would light a black surface, and the pure black theme is
        // there to keep the pixels of an OLED screen off. The cards and the sheets on it are
        // not black, and stay metal.
        assertFalse(Surfaces.takes(Color.Black))
        assertTrue(Surfaces.takes(Color(0xFF0C1313)))
        for (palette in Palette.entries.filter { it != Palette.WALLPAPER }) {
            val scheme = Palettes.colorScheme(palette, TonalPalettes.of(palette), dark = true, black = true)
            assertFalse("$palette background", Surfaces.takes(scheme.background))
            assertTrue("$palette cards", Surfaces.takes(scheme.surfaceContainer))
            assertTrue("$palette sheets", Surfaces.takes(scheme.surfaceContainerLow))
        }
    }
}
