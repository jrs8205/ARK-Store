package org.jarsi.arkstore.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Whether the surfaces of the screen are drawn as materials; set at the top of the screen. */
val LocalMaterial = compositionLocalOf { false }

/**
 * How a surface stands out of the screen: a plate lies nearly flat, a raised surface bulges
 * toward the viewer and catches the light along its top and left edges, a recessed one is
 * pressed in and shaded there instead.
 */
enum class Relief(internal val amount: Float) { PLATE(0.35f), RAISED(1f), RECESSED(-1f) }

/**
 * Surfaces the graphics processor draws as a material rather than a flat colour: brushed
 * metal, lit from above the top left corner. The light, the grain and the bevelled edge are
 * computed for every pixel by a shader, so there is no image to scale, and the edge follows
 * the rounded corners of whatever shape the surface has. Needs Android 13; on older versions
 * the flat colours stay. On by default, behind a setting that turns it off.
 *
 * Whether the device can draw them is found out once, by drawing a small sample off screen
 * and looking at the pixels ([ready]); a device whose graphics driver cannot is remembered,
 * and so is a run that began drawing them and never finished a frame, so that a driver that
 * crashes rather than fails is not tried again and again. On such a device the flat colours
 * stay, and the setting says why.
 */
object Surfaces {

    /** Whether the Android version has what the materials need; see [ready] for the device. */
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @Volatile
    private var ready: Boolean? = null

    @Volatile
    private var drawn = false

    /**
     * Whether the materials can be drawn here: [supported], not known to have failed, and
     * shown to work by a sample drawn off screen the first time this is asked in a run.
     * Asked only when the materials are wanted, since a run that asks and then never
     * finishes a frame counts as a failure the next time.
     */
    fun ready(context: Context): Boolean {
        if (!supported) return false
        ready?.let { return it }
        val prefs = prefs(context)
        val result = when {
            mark(prefs, FAILED) == stamp -> false
            mark(prefs, TRYING) == stamp -> {
                // The last run began drawing the materials and never got to say that a
                // frame went through.
                prefs.edit { putString(FAILED, stamp); remove(TRYING) }
                false
            }
            // The mark must be on disk before the drawing that may bring the run down, or
            // the next run would not see it: written and waited for, not merely applied.
            !prefs.edit().putString(TRYING, stamp).commit() -> false
            else -> {
                val works = try {
                    probe()
                } catch (_: Throwable) {
                    false
                }
                if (!works) prefs.edit { putString(FAILED, stamp); remove(TRYING) }
                works
            }
        }
        ready = result
        return result
    }

    /** Whether the device was found unable to draw the materials, as it is now. */
    fun failed(context: Context): Boolean = supported && mark(prefs(context), FAILED) == stamp

    /** The stamp stored under [key], or null; a value of another kind, from an older version, is none. */
    private fun mark(prefs: SharedPreferences, key: String): String? = try {
        prefs.getString(key, null)
    } catch (_: ClassCastException) {
        null
    }

    /**
     * Called when a surface has been drawn: once the run has lived through a couple of
     * seconds after that, it has not been brought down by the drawing.
     */
    internal fun drawn(context: Context) {
        if (drawn) return
        drawn = true
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ prefs(app).edit { remove(TRYING) } }, SETTLE_MS)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * What a failure is remembered for: this version of the drawing on this build of this
     * device. A new version of the app or of the system is given a new try, and a failure
     * restored from a backup onto another device means nothing there.
     */
    private val stamp: String
        get() = "$DRAWING:${Build.FINGERPRINT}"

    private const val PREFS = "surfaces"
    private const val FAILED = "failed_on"
    private const val TRYING = "trying_on"
    private const val SETTLE_MS = 2000L

    /** The version of the shader and the drawing around it; raise it when they change. */
    private const val DRAWING = 2

    /**
     * How far the shading may move a colour where text can be, of the whole range of a
     * channel: a dark surface, which carries light text, may brighten by [LIFT], and a light
     * one, which carries dark text, may darken by [SHADE]. The shader holds itself to these,
     * and the palettes leave their text that much room; PalettesTest checks them for it.
     */
    internal const val LIFT = 0.02f
    internal const val SHADE = 0.05f

    /** Whether the shader takes [base] for a light surface, one that carries dark text. */
    internal fun light(base: Color): Boolean =
        0.2126f * base.red + 0.7152f * base.green + 0.0722f * base.blue > 0.5f

    /**
     * The colour nearest to its text that the metal shows of [base] where text can be: a
     * light surface at its darkest, a dark one at its brightest.
     */
    internal fun underText(base: Color): Color {
        val shift = if (light(base)) -SHADE else LIFT
        return Color(
            (base.red + shift).coerceIn(0f, 1f),
            (base.green + shift).coerceIn(0f, 1f),
            (base.blue + shift).coerceIn(0f, 1f)
        )
    }

    /**
     * Whether a surface of the colour [base] is drawn as metal. Pure black is left as it is:
     * the highlight would light it, and the pure black theme is there to keep the pixels of
     * an OLED screen off.
     */
    internal fun takes(base: Color): Boolean = base != Color.Black

    /**
     * Draws a small plate of a grey off screen and looks at a pixel of it: the shader must
     * compile for this driver and draw a colour near the grey, not nothing or garbage.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun probe(): Boolean {
        val grey = 0xFF808080.toInt()
        val shader = shader(PROBE_SIZE.toFloat(), PROBE_SIZE.toFloat(), 0f, Relief.PLATE, 3f, 0.3f, grey)
        val drawn = raster(PROBE_SIZE, PROBE_SIZE, shader) ?: return false
        val pixels = drawn.copy(Bitmap.Config.ARGB_8888, false) ?: return false
        val pixel = pixels.getPixel(PROBE_SIZE / 2, PROBE_SIZE / 2)
        pixels.recycle()
        if (pixel ushr 24 != 0xFF) return false
        for (shift in intArrayOf(16, 8, 0)) {
            if (abs(((pixel shr shift) and 0xFF) - 0x80) > PROBE_TOLERANCE) return false
        }
        return true
    }

    private const val PROBE_SIZE = 16
    private const val PROBE_TOLERANCE = 0x50

    /**
     * The metal shader with its uniforms set for a surface of the given size and look. Making
     * one compiles the program again, which takes a good part of a millisecond, so the
     * shaders are kept and shared: the chips, the buttons and the badges are all alike, and
     * cards of the same height and colour are too. A shader is read-only once made, so
     * sharing one between surfaces is safe.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    internal fun shader(
        width: Float,
        height: Float,
        radius: Float?,
        relief: Relief,
        bevel: Float,
        grain: Float,
        base: Int
    ): RuntimeShader {
        val key = ShaderKey(width, height, radius, relief, bevel, grain, base)
        synchronized(shaders) {
            shaders[key]?.let { return it }
        }
        val shader = RuntimeShader(METAL).apply {
            setFloatUniform("resolution", width, height)
            setFloatUniform("radius", radius ?: (minOf(width, height) / 2f))
            setFloatUniform("relief", relief.amount)
            setFloatUniform("bevel", bevel)
            setFloatUniform("grain", grain)
            setColorUniform("base", base)
        }
        synchronized(shaders) { shaders[key] = shader }
        return shader
    }

    private data class ShaderKey(
        val width: Float,
        val height: Float,
        val radius: Float?,
        val relief: Relief,
        val bevel: Float,
        val grain: Float,
        val base: Int
    )

    private val shaders = object : LinkedHashMap<ShaderKey, RuntimeShader>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ShaderKey, RuntimeShader>?) =
            size > SHADERS_KEPT
    }

    private const val SHADERS_KEPT = 96

    /**
     * The image of a surface that does not change from frame to frame, drawn once by the
     * graphics processor ([raster]) and kept, shared by every surface of the same size and
     * look: the chips, the buttons and the badges are all alike. A large surface, the
     * screen's background, is drawn at half resolution, which is enough for a brushing and
     * a quarter of the memory. Null when the device could not draw it, and the shader is
     * run instead. The images kept are bounded by their size in memory.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    internal fun still(
        width: Float,
        height: Float,
        radius: Float?,
        relief: Relief,
        bevel: Float,
        grain: Float,
        base: Int
    ): ImageBitmap? {
        val scale = if (width * height > LARGE_SURFACE) 0.5f else 1f
        val columns = (width * scale).roundToInt().coerceAtLeast(1)
        val rows = (height * scale).roundToInt().coerceAtLeast(1)
        val key = ShaderKey(columns.toFloat(), rows.toFloat(), radius?.let { it * scale }, relief, bevel * scale, grain, base)
        synchronized(stills) {
            stills[key]?.let { return it }
        }
        val image = try {
            raster(columns, rows, shader(key.width, key.height, key.radius, relief, key.bevel, grain, base))
                ?.asImageBitmap()
        } catch (_: RuntimeException) {
            null
        } ?: return null
        synchronized(stills) {
            stills[key] = image
            stillBytes += columns * rows * 4L
            val oldest = stills.entries.iterator()
            while (stillBytes > STILL_BYTES && oldest.hasNext()) {
                val entry = oldest.next()
                if (entry.value === image) continue
                stillBytes -= entry.key.width.toLong() * entry.key.height.toLong() * 4L
                oldest.remove()
            }
        }
        return image
    }

    private val stills = LinkedHashMap<ShaderKey, ImageBitmap>(32, 0.75f, true)
    private var stillBytes = 0L

    /** Pixels above which a still surface is drawn at half resolution. */
    private const val LARGE_SURFACE = 400_000f

    /** How much memory the still images may take together: a background and the controls. */
    private const val STILL_BYTES = 8L shl 20

    /**
     * [shader] drawn by the graphics processor into an image of [width] by [height] pixels
     * that stays in its memory, or null when that fails. For a surface that does not change
     * from frame to frame, such as the screen's background, drawing the image is far cheaper
     * than running the shader for every pixel every frame.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    internal fun raster(width: Int, height: Int, shader: RuntimeShader): Bitmap? {
        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val renderer = HardwareRenderer()
        try {
            val node = RenderNode("metal")
            node.setPosition(0, 0, width, height)
            val canvas = node.beginRecording()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().also { it.shader = shader })
            node.endRecording()
            renderer.setContentRoot(node)
            renderer.setSurface(reader.surface)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage() ?: return null
            try {
                val buffer = image.hardwareBuffer ?: return null
                // The bitmap keeps a reference of its own to the buffer; this handle is
                // let go of at once rather than left to the garbage collector.
                return buffer.use { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
            } finally {
                image.close()
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    /**
     * The metal, in AGSL. "base" is the colour of the flat surface it replaces, and the text
     * on top is set against that colour. The shading may therefore move the colour only a
     * little where text can be: a dark surface, which carries light text, may brighten by a
     * few percent, a light one, which carries dark text, may darken by a few percent. The
     * bevel band along the edge carries no text and may shine freely.
     *
     * The surface is lit as a brushed metal is: a normal is made up for every pixel (flat in
     * the middle, doming gently, and turning over along the bevel, which is found from the
     * distance to the rounded rectangle's edge), and the light reflects off it with Ward's
     * anisotropic model, so that the highlight stretches along the brushing and the bevel
     * facing the light catches it. A little Fresnel brightening along the edge and the grain
     * of the brushing finish it. The distance is computed in full precision, since it spans
     * the screen; the lighting in half precision, which mobile graphics processors run
     * faster and which is plenty for a colour.
     */
    internal const val METAL = """
        uniform float2 resolution;
        uniform float radius;
        uniform float relief;
        uniform float bevel;
        uniform float grain;
        layout(color) uniform half4 base;

        // The grain: a hash of the row and the stretch of the row, without a sine.
        half hash(float2 p) {
            float3 q = fract(float3(p.xyx) * float3(0.1031, 0.1030, 0.0973));
            q += dot(q, q.yzx + 33.33);
            return half(fract((q.x + q.y) * q.z));
        }

        half4 main(float2 p) {
            float2 center = resolution * 0.5;
            float2 q = p - center;
            float2 inner = max(center - float2(radius), float2(0.0));
            float2 away = q - clamp(q, -inner, inner);
            float d = length(away) - radius;
            half2 outward = half2(length(away) > 0.5 ? normalize(away) : float2(0.0));
            half rim = half(smoothstep(-bevel, 0.0, d));
            // The normal: a raised surface domes toward the viewer and turns down over its
            // bevel, a recessed one the other way round.
            half2 uv = half2(p / resolution);
            half2 dome = (uv - 0.5) * half2(0.5, 0.7) * half(relief);
            half3 N = normalize(half3(dome + outward * rim * 0.9 * half(relief), 1.0));
            half3 V = half3(0.0, 0.0, 1.0);
            half3 L = normalize(half3(-0.45, -0.55, 0.7));
            half3 H = normalize(V + L);
            // The brushing runs along x: broad highlight along it, tight across it.
            half3 B = normalize(cross(N, half3(1.0, 0.0, 0.0)));
            half3 T = cross(B, N);
            half LN = max(dot(L, N), 0.0);
            half VN = max(dot(V, N), 0.001);
            half HN = max(dot(H, N), 0.001);
            half HT = dot(H, T) / 0.55;
            half HB = dot(H, B) / 0.18;
            half ward = sqrt(LN / VN) * exp(-2.0 * (HT * HT + HB * HB) / (1.0 + HN));
            half diffuse = 0.94 + 0.09 * LN;
            half edge = 1.0 - VN;
            half fresnel = edge * edge * edge * 0.12 * rim;
            half streak = (hash(float2(floor(p.y), floor(p.x / 64.0))) - 0.5) * 0.06 * half(grain);
            half3 shaded = base.rgb * (diffuse + streak + fresnel) + half3(ward * 0.14);
            // Within reach of the text, the colour stays where the text keeps its contrast.
            half lum = dot(base.rgb, half3(0.2126, 0.7152, 0.0722));
            half up = mix(lum > 0.5 ? 0.2 : $LIFT, 0.25, rim);
            half down = mix(lum > 0.5 ? $SHADE : 0.2, 0.25, rim);
            half3 color = base.rgb + clamp(shaded - base.rgb, half3(-down), half3(up));
            return half4(color, 1.0);
        }
    """
}

/**
 * Draws the surface as brushed metal of the colour [base] with the given [relief], when
 * materials are on, the device can draw them and the colour takes them ([Surfaces.takes]);
 * otherwise draws nothing, and the content's own background shows. [radius] is the corner
 * radius of the surface's shape, null for a pill; the bevel follows it. [grain] is how
 * strongly the brushing shows. With [reachBelow] the metal is drawn that far below the
 * surface's own bounds as well, for a surface at the top of something larger that clips it
 * to its shape. With [still] the surface is drawn once into an image, at half resolution,
 * and the image is drawn from then on: for a large surface that does not change, such as
 * the screen's background.
 */
@Composable
fun Modifier.material(
    base: Color,
    relief: Relief = Relief.PLATE,
    radius: Dp? = 12.dp,
    grain: Float = 1f,
    reachBelow: Dp = 0.dp,
    still: Boolean = false
): Modifier {
    if (!LocalMaterial.current || !Surfaces.supported || !Surfaces.takes(base)) return this
    val density = LocalDensity.current
    val context = LocalContext.current
    val radiusPx = radius?.let { with(density) { it.toPx() } }
    val bevelPx = with(density) { 3.dp.toPx() }
    val reachPx = with(density) { reachBelow.toPx() }
    return then(Modifier.metalSurface(base, relief, radiusPx, bevelPx, grain, reachPx, still) { Surfaces.drawn(context) })
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.metalSurface(
    base: Color,
    relief: Relief,
    radius: Float?,
    bevel: Float,
    grain: Float,
    reach: Float,
    still: Boolean,
    onDrawn: () -> Unit
): Modifier = drawWithCache {
    val height = size.height + reach
    val image: ImageBitmap? = if (still) {
        Surfaces.still(size.width, height, radius, relief, bevel, grain, base.toArgb())
    } else {
        null
    }
    val brush = if (image == null) {
        ShaderBrush(Surfaces.shader(size.width, height, radius, relief, bevel, grain, base.toArgb()))
    } else {
        null
    }
    onDrawBehind {
        if (image != null) {
            drawImage(
                image,
                dstSize = IntSize(size.width.roundToInt(), height.roundToInt()),
                filterQuality = FilterQuality.Low
            )
        } else if (brush != null) {
            drawRect(brush, size = Size(size.width, height))
        }
        onDrawn()
    }
}
