package org.jarsi.arkstore.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * the flat colours stay. An experiment behind a setting.
 */
object Surfaces {

    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * The metal, in AGSL. "base" is the colour of the flat surface it replaces: the text on
     * top is set against that colour, and the material stays within a few percent of it, so
     * that the text keeps its contrast.
     *
     * The surface is lit as a brushed metal is: a normal is made up for every pixel (flat in
     * the middle, doming gently, and turning over along the bevel, which is found from the
     * distance to the rounded rectangle's edge), and the light reflects off it with Ward's
     * anisotropic model, so that the highlight stretches along the brushing and the bevel
     * facing the light catches it. A little Fresnel brightening along the edge and the grain
     * of the brushing finish it.
     */
    internal const val METAL = """
        uniform float2 resolution;
        uniform float radius;
        uniform float relief;
        uniform float bevel;
        uniform float grain;
        layout(color) uniform half4 base;

        float hash(float2 p) {
            return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
        }

        half4 main(float2 p) {
            float2 center = resolution * 0.5;
            float2 q = p - center;
            float2 inner = max(center - float2(radius), float2(0.0));
            float2 away = q - clamp(q, -inner, inner);
            float d = length(away) - radius;
            float2 outward = length(away) > 0.5 ? normalize(away) : float2(0.0);
            float rim = smoothstep(-bevel, 0.0, d);
            // The normal: a raised surface domes toward the viewer and turns down over its
            // bevel, a recessed one the other way round.
            float2 uv = p / resolution;
            float2 dome = (uv - 0.5) * float2(0.5, 0.7) * relief;
            float3 N = normalize(float3(dome + outward * rim * 0.9 * relief, 1.0));
            float3 V = float3(0.0, 0.0, 1.0);
            float3 L = normalize(float3(-0.45, -0.55, 0.7));
            float3 H = normalize(V + L);
            // The brushing runs along x: broad highlight along it, tight across it.
            float3 B = normalize(cross(N, float3(1.0, 0.0, 0.0)));
            float3 T = cross(B, N);
            float LN = max(dot(L, N), 0.0);
            float VN = max(dot(V, N), 0.001);
            float HN = max(dot(H, N), 0.001);
            float HT = dot(H, T) / 0.55;
            float HB = dot(H, B) / 0.18;
            float ward = sqrt(LN / VN) * exp(-2.0 * (HT * HT + HB * HB) / (1.0 + HN));
            // Most of the colour comes through whatever the light does; the shading moves it
            // by a few percent only, and the edge brightens a little where it turns away.
            float diffuse = 0.94 + 0.09 * LN;
            float fresnel = pow(1.0 - VN, 3.0) * 0.12 * rim;
            float streak = (hash(float2(floor(p.y), floor(p.x / 64.0))) - 0.5) * 0.06 * grain;
            half3 color = base.rgb * (diffuse + streak + fresnel) + half3(ward * 0.14);
            return half4(color, 1.0);
        }
    """
}

/**
 * Draws the surface as brushed metal of the colour [base] with the given [relief], when
 * materials are on and the device can draw them; otherwise draws nothing, and the content's
 * own background shows. [radius] is the corner radius of the surface's shape, null for a pill;
 * the bevel follows it. [grain] is how strongly the brushing shows.
 */
@Composable
fun Modifier.material(base: Color, relief: Relief = Relief.PLATE, radius: Dp? = 12.dp, grain: Float = 1f): Modifier {
    if (!LocalMaterial.current || !Surfaces.available) return this
    val density = LocalDensity.current
    val radiusPx = radius?.let { with(density) { it.toPx() } }
    val bevelPx = with(density) { 3.dp.toPx() }
    return then(Modifier.metalSurface(base, relief, radiusPx, bevelPx, grain))
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.metalSurface(base: Color, relief: Relief, radius: Float?, bevel: Float, grain: Float): Modifier =
    drawWithCache {
        val shader = RuntimeShader(Surfaces.METAL)
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("radius", radius ?: (minOf(size.width, size.height) / 2f))
        shader.setFloatUniform("relief", relief.amount)
        shader.setFloatUniform("bevel", bevel)
        shader.setFloatUniform("grain", grain)
        shader.setColorUniform("base", base.toArgb())
        val brush = ShaderBrush(shader)
        onDrawBehind { drawRect(brush) }
    }
