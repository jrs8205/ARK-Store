package org.jarsi.arkstore.ui

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** The palettes the user can choose between; [key] is what the settings keep. */
enum class Palette(val key: String) {
    ARK("ark"),
    FOREST("forest"),
    GRAPHITE("graphite"),
    WINE("wine"),
    MIDNIGHT("midnight"),

    /** The colours of the device's wallpaper, which Android 12 and later provide. */
    WALLPAPER("wallpaper");

    companion object {
        /** The palette kept under [key], or ARK for none, an unknown one, or the wallpaper's on an Android without it. */
        fun fromKey(key: String?, sdk: Int = Build.VERSION.SDK_INT): Palette {
            val found = entries.firstOrNull { it.key == key } ?: ARK
            return if (found == WALLPAPER && sdk < Build.VERSION_CODES.S) ARK else found
        }
    }
}

/**
 * Thirteen tones of one hue, from 0 (black) to 100 (white). A tone is the CIE lightness L*,
 * so the contrast between two colours follows from their tones alone, whatever the hue: 30
 * against 90 or 99 reads at 7:1, and 50 against 99 at 3:1.
 */
class Tones private constructor(private val colors: Map<Int, Color>) {
    operator fun get(tone: Int): Color = colors.getValue(tone)

    /** The colour at a [tone] between two listed ones, mixed from its neighbours. */
    fun at(tone: Float): Color {
        val lower = TONES.last { it <= tone }
        val upper = TONES.first { it >= tone }
        if (lower == upper) return colors.getValue(lower)
        return lerp(colors.getValue(lower), colors.getValue(upper), (tone - lower) / (upper - lower))
    }

    companion object {
        val TONES = intArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)

        /** Tones given colour by colour, such as the ones Android reads from the wallpaper. */
        fun from(colors: Map<Int, Color>): Tones {
            require(TONES.all { it in colors }) { "every listed tone is needed" }
            return Tones(colors)
        }

        /** Tones of the [hue] (degrees, OKLCh) at the given [chroma], or less where sRGB has no room for it. */
        fun of(hue: Float, chroma: Float): Tones =
            Tones(TONES.associateWith { Oklch.tone(it, hue, chroma) })
    }
}

/** The five tonal palettes a colour scheme is built from, named as Material names them. */
class TonalPalettes(
    val accent1: Tones,
    val accent2: Tones,
    val accent3: Tones,
    val neutral1: Tones,
    val neutral2: Tones
) {
    companion object {
        private val known = java.util.EnumMap<Palette, TonalPalettes>(Palette::class.java)

        /** The tones of one of the store's own palettes; the wallpaper's come from [system]. */
        fun of(palette: Palette): TonalPalettes = known.getOrPut(palette) { build(palette) }

        // Three accents, then the two neutrals that the surfaces and the outlines are made of.
        // The neutrals keep enough of the palette's hue for the background, the cards and the
        // sheets to show which palette is in use; graphite is the grey one, and stays close to it.
        private fun build(palette: Palette): TonalPalettes = when (palette) {
            // Deep navy, sea teal and the amber hull; only the neutrals are used, for pure black.
            Palette.ARK -> seeds(185f, 0.06f, 240f, 0.05f, 75f, 0.10f, 185f, 0.012f, 185f, 0.02f)
            // Spruce, moss and copper.
            Palette.FOREST -> seeds(150f, 0.09f, 130f, 0.04f, 55f, 0.10f, 150f, 0.030f, 150f, 0.045f)
            // Steel with a cool blue.
            Palette.GRAPHITE -> seeds(240f, 0.05f, 240f, 0.03f, 250f, 0.10f, 240f, 0.014f, 240f, 0.022f)
            // Wine, rose and old gold.
            Palette.WINE -> seeds(10f, 0.12f, 10f, 0.05f, 70f, 0.09f, 10f, 0.030f, 10f, 0.045f)
            // Indigo, violet and lavender; the surfaces lean to the violet, away from graphite's blue.
            Palette.MIDNIGHT -> seeds(275f, 0.12f, 300f, 0.06f, 290f, 0.08f, 285f, 0.035f, 285f, 0.050f)
            Palette.WALLPAPER -> throw IllegalArgumentException("the wallpaper's tones come from the system")
        }

        private fun seeds(
            h1: Float, c1: Float, h2: Float, c2: Float, h3: Float, c3: Float,
            n1h: Float, n1c: Float, n2h: Float, n2c: Float
        ) = TonalPalettes(
            Tones.of(h1, c1), Tones.of(h2, c2), Tones.of(h3, c3), Tones.of(n1h, n1c), Tones.of(n2h, n2c)
        )

        /**
         * The tones Android 12 and later derive from the wallpaper. The surfaces are made of
         * the second accent rather than of the system's first neutral, which is all but grey,
         * so that the wallpaper's colour shows on them as a palette of the store's own does.
         */
        @RequiresApi(Build.VERSION_CODES.S)
        fun system(context: Context): TonalPalettes {
            val accent2 = systemTones(context, ACCENT2)
            return TonalPalettes(
                systemTones(context, ACCENT1), accent2, systemTones(context, ACCENT3),
                accent2, systemTones(context, NEUTRAL2)
            )
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun systemTones(context: Context, ids: IntArray): Tones =
            Tones.from(Tones.TONES.indices.associate { Tones.TONES[it] to Color(context.getColor(ids[it])) })

        // Darkest first, in the order of Tones.TONES: the system names its tones by darkness.
        @RequiresApi(Build.VERSION_CODES.S)
        private val ACCENT1 = intArrayOf(
            android.R.color.system_accent1_1000, android.R.color.system_accent1_900,
            android.R.color.system_accent1_800, android.R.color.system_accent1_700,
            android.R.color.system_accent1_600, android.R.color.system_accent1_500,
            android.R.color.system_accent1_400, android.R.color.system_accent1_300,
            android.R.color.system_accent1_200, android.R.color.system_accent1_100,
            android.R.color.system_accent1_50, android.R.color.system_accent1_10,
            android.R.color.system_accent1_0
        )

        @RequiresApi(Build.VERSION_CODES.S)
        private val ACCENT2 = intArrayOf(
            android.R.color.system_accent2_1000, android.R.color.system_accent2_900,
            android.R.color.system_accent2_800, android.R.color.system_accent2_700,
            android.R.color.system_accent2_600, android.R.color.system_accent2_500,
            android.R.color.system_accent2_400, android.R.color.system_accent2_300,
            android.R.color.system_accent2_200, android.R.color.system_accent2_100,
            android.R.color.system_accent2_50, android.R.color.system_accent2_10,
            android.R.color.system_accent2_0
        )

        @RequiresApi(Build.VERSION_CODES.S)
        private val ACCENT3 = intArrayOf(
            android.R.color.system_accent3_1000, android.R.color.system_accent3_900,
            android.R.color.system_accent3_800, android.R.color.system_accent3_700,
            android.R.color.system_accent3_600, android.R.color.system_accent3_500,
            android.R.color.system_accent3_400, android.R.color.system_accent3_300,
            android.R.color.system_accent3_200, android.R.color.system_accent3_100,
            android.R.color.system_accent3_50, android.R.color.system_accent3_10,
            android.R.color.system_accent3_0
        )

        @RequiresApi(Build.VERSION_CODES.S)
        private val NEUTRAL2 = intArrayOf(
            android.R.color.system_neutral2_1000, android.R.color.system_neutral2_900,
            android.R.color.system_neutral2_800, android.R.color.system_neutral2_700,
            android.R.color.system_neutral2_600, android.R.color.system_neutral2_500,
            android.R.color.system_neutral2_400, android.R.color.system_neutral2_300,
            android.R.color.system_neutral2_200, android.R.color.system_neutral2_100,
            android.R.color.system_neutral2_50, android.R.color.system_neutral2_10,
            android.R.color.system_neutral2_0
        )
    }
}

/**
 * The colour schemes of the palettes. Every text colour keeps a contrast of at least 7:1
 * against each surface it is used on (WCAG AAA), and outlines at least 3:1, so the screen
 * stays readable in direct sunlight; PalettesTest checks every palette for it. All roles are
 * set explicitly so that none falls back to a library default that has not been checked.
 */
object Palettes {
    /** The scheme of [palette] built from [tones], dark or light, on pure black when [black] and dark. */
    fun colorScheme(palette: Palette, tones: TonalPalettes, dark: Boolean, black: Boolean): ColorScheme {
        val scheme = when {
            palette == Palette.ARK -> if (dark) ArkDark else ArkLight
            else -> scheme(tones, dark)
        }
        return if (dark && black) blackened(scheme, tones.neutral1) else scheme
    }

    // The tones of each role. Text sits 60 tones or more from its surface for 7:1, outlines
    // 40 or more for 3:1; the surface containers step between the listed tones. The light
    // surfaces start at 96 rather than next to white, where there is no room for a colour.
    // A surface drawn as metal is lit and shaded under its text (Surfaces.LIFT and SHADE), so
    // the pairs that would sit at 60 exactly are set 65 or more apart: the text on an accent
    // and on a container in the dark scheme, the accents themselves in the light one.
    private fun scheme(p: TonalPalettes, dark: Boolean): ColorScheme = if (dark) darkColorScheme(
        primary = p.accent1[80],
        onPrimary = p.accent1[10],
        primaryContainer = p.accent1[30],
        onPrimaryContainer = p.accent1[95],
        inversePrimary = p.accent1[30],
        secondary = p.accent2[80],
        onSecondary = p.accent2[10],
        secondaryContainer = p.accent2[30],
        onSecondaryContainer = p.accent2[95],
        tertiary = p.accent3[80],
        onTertiary = p.accent3[10],
        tertiaryContainer = p.accent3[30],
        onTertiaryContainer = p.accent3[95],
        error = ArkDark.error,
        onError = ArkDark.onError,
        errorContainer = ArkDark.errorContainer,
        onErrorContainer = ArkDark.onErrorContainer,
        background = p.neutral1[10],
        onBackground = p.neutral1[90],
        surface = p.neutral1[10],
        onSurface = p.neutral1[90],
        surfaceVariant = p.neutral2[30],
        onSurfaceVariant = p.neutral2[90],
        surfaceTint = p.accent1[80],
        inverseSurface = p.neutral1[90],
        inverseOnSurface = p.neutral1[20],
        surfaceDim = p.neutral1[10],
        surfaceBright = p.neutral1[20],
        surfaceContainerLowest = p.neutral1.at(4f),
        surfaceContainerLow = p.neutral1.at(8f),
        surfaceContainer = p.neutral1.at(12f),
        surfaceContainerHigh = p.neutral1.at(16f),
        surfaceContainerHighest = p.neutral1[20],
        outline = p.neutral2[60],
        outlineVariant = p.neutral2[50],
        scrim = Color.Black
    ) else lightColorScheme(
        primary = p.accent1.at(25f),
        onPrimary = p.accent1[100],
        primaryContainer = p.accent1[90],
        onPrimaryContainer = p.accent1[10],
        inversePrimary = p.accent1[80],
        secondary = p.accent2.at(25f),
        onSecondary = p.accent2[100],
        // Darker than the surfaces, which share its hue, so that a chosen chip shows among the others.
        secondaryContainer = p.accent2.at(85f),
        onSecondaryContainer = p.accent2[10],
        tertiary = p.accent3.at(25f),
        onTertiary = p.accent3[100],
        tertiaryContainer = p.accent3[90],
        onTertiaryContainer = p.accent3[10],
        error = ArkLight.error,
        onError = ArkLight.onError,
        errorContainer = ArkLight.errorContainer,
        onErrorContainer = ArkLight.onErrorContainer,
        background = p.neutral1.at(96f),
        onBackground = p.neutral1[10],
        surface = p.neutral1.at(96f),
        onSurface = p.neutral1[10],
        surfaceVariant = p.neutral2[90],
        onSurfaceVariant = p.neutral2[20],
        surfaceTint = p.accent1.at(25f),
        inverseSurface = p.neutral1[20],
        inverseOnSurface = p.neutral1[95],
        surfaceDim = p.neutral1[90],
        surfaceBright = p.neutral1.at(96f),
        surfaceContainerLowest = p.neutral1[100],
        surfaceContainerLow = p.neutral1[95],
        surfaceContainer = p.neutral1.at(93f),
        surfaceContainerHigh = p.neutral1.at(91.5f),
        surfaceContainerHighest = p.neutral1[90],
        outline = p.neutral2[50],
        outlineVariant = p.neutral2.at(55f),
        scrim = Color.Black
    )

    /** The dark [scheme] on pure black, its surface containers stepped down with it. */
    private fun blackened(scheme: ColorScheme, neutral: Tones): ColorScheme = scheme.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = neutral.at(4f),
        surfaceContainer = neutral.at(8f),
        surfaceContainerHigh = neutral.at(12f),
        surfaceContainerHighest = neutral.at(16f)
    )
}

// The ARK palette, built around the ARK mark: deep navy, sea teal and the amber hull. Tuned by
// hand before the generated palettes, and kept as it is but for a shade here and there that
// the contrast tests asked for.
internal val ArkLight = lightColorScheme(
    primary = Color(0xFF004C4F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F4),
    onPrimaryContainer = Color(0xFF002021),
    inversePrimary = Color(0xFF8EE0E4),
    secondary = Color(0xFF16324F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E4FF),
    onSecondaryContainer = Color(0xFF0A1D33),
    tertiary = Color(0xFF5B3F00),
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
    surfaceTint = Color(0xFF004C4F),
    inverseSurface = Color(0xFF2B3232),
    inverseOnSurface = Color(0xFFEEF3F3),
    surfaceDim = Color(0xFFDFE3E3),
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

internal val ArkDark = darkColorScheme(
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
    surfaceVariant = Color(0xFF353F40),
    onSurfaceVariant = Color(0xFFCED8D9),
    surfaceTint = Color(0xFF8EE0E4),
    inverseSurface = Color(0xFFEEF3F3),
    inverseOnSurface = Color(0xFF2B3232),
    surfaceDim = Color(0xFF0C1313),
    surfaceBright = Color(0xFF2D3536),
    surfaceContainerLowest = Color(0xFF070D0D),
    surfaceContainerLow = Color(0xFF141B1B),
    surfaceContainer = Color(0xFF182020),
    surfaceContainerHigh = Color(0xFF222A2B),
    surfaceContainerHighest = Color(0xFF2D3536),
    outline = Color(0xFF9BA5A5),
    outlineVariant = Color(0xFF6A7475),
    scrim = Color(0xFF000000)
)

/**
 * Colours by hue and chroma in OKLCh, with the lightness found so that the colour has the
 * luminance of the asked tone (CIE L*). OKLCh keeps a hue looking the same from dark to
 * light; L* is what contrast is computed from.
 */
private object Oklch {
    fun tone(tone: Int, hue: Float, chroma: Float): Color {
        if (tone <= 0) return Color.Black
        if (tone >= 100) return Color.White
        val target = luminanceOf(tone)
        val radians = Math.toRadians(hue.toDouble())
        val a = cos(radians)
        val b = sin(radians)
        var low = 0.0
        var high = 1.0
        var rgb = doubleArrayOf(0.0, 0.0, 0.0)
        repeat(32) {
            val mid = (low + high) / 2
            rgb = inGamut(mid, chroma.toDouble(), a, b)
            if (luminance(rgb) < target) low = mid else high = mid
        }
        return Color(encode(rgb[0]), encode(rgb[1]), encode(rgb[2]))
    }

    /** The luminance of the lightness [tone], the inverse of the L* formula. */
    private fun luminanceOf(tone: Int): Double =
        if (tone > 8) ((tone + 16.0) / 116.0).pow(3) else tone / 903.3

    private fun luminance(rgb: DoubleArray): Double =
        0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]

    // Linear sRGB for the lightness and hue, at the chroma asked or the most sRGB holds.
    private fun inGamut(l: Double, chroma: Double, a: Double, b: Double): DoubleArray {
        var rgb = linear(l, chroma * a, chroma * b)
        if (rgb.all { it in 0.0..1.0 }) return rgb
        var low = 0.0
        var high = chroma
        rgb = linear(l, 0.0, 0.0)
        repeat(20) {
            val mid = (low + high) / 2
            val candidate = linear(l, mid * a, mid * b)
            if (candidate.all { it in 0.0..1.0 }) {
                low = mid
                rgb = candidate
            } else {
                high = mid
            }
        }
        return DoubleArray(3) { rgb[it].coerceIn(0.0, 1.0) }
    }

    // OKLab to linear sRGB, after Björn Ottosson.
    private fun linear(l: Double, a: Double, b: Double): DoubleArray {
        val l1 = l + 0.3963377774 * a + 0.2158037573 * b
        val m1 = l - 0.1055613458 * a - 0.0638541728 * b
        val s1 = l - 0.0894841775 * a - 1.2914855480 * b
        val l3 = l1 * l1 * l1
        val m3 = m1 * m1 * m1
        val s3 = s1 * s1 * s1
        return doubleArrayOf(
            4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
            -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
            -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3
        )
    }

    private fun encode(linear: Double): Float {
        val c = linear.coerceIn(0.0, 1.0)
        return (if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055).toFloat()
    }
}
