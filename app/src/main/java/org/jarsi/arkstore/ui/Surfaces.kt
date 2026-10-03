package org.jarsi.arkstore.ui

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

/**
 * Surfaces the graphics processor draws as a material rather than a flat colour: brushed
 * metal, lit from above the top left corner, for the cards. The light, the grain and the
 * bevelled edge are computed for every pixel by a shader, so there is no image to scale.
 * Needs Android 13; on older versions the flat surface stays. An experiment behind a setting.
 */
object Surfaces {

    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * The metal, in AGSL. "base" is the colour of the flat surface it replaces: the text on
     * top is set against that colour, and the material stays within a few percent of it, so
     * that the text keeps its contrast.
     */
    internal const val METAL = """
        uniform float2 resolution;
        uniform float2 light;
        layout(color) uniform half4 base;

        float hash(float2 p) {
            return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
        }

        half4 main(float2 p) {
            float2 uv = p / resolution;
            // The grain of brushing: fine streaks along the surface, each row its own.
            float grain = hash(float2(floor(p.y), floor(p.x / 64.0))) - 0.5;
            float brush = grain * 0.05;
            // The light: a soft highlight where it falls, and a sheen band across the surface.
            float2 d = (p - light) / resolution.x;
            float spec = exp(-dot(d, d) * 2.5) * 0.12;
            float band = uv.x * 0.8 - uv.y * 0.5 + 0.1;
            float sheen = smoothstep(0.3, 0.5, band) * (1.0 - smoothstep(0.5, 0.7, band)) * 0.05;
            // A bevelled edge: lit at the top and the left, in shadow at the bottom and the right.
            float e = 2.0;
            float edge = 0.0;
            if (p.y < e || p.x < e) edge += 0.18;
            if (p.y > resolution.y - e || p.x > resolution.x - e) edge -= 0.12;
            // A faint fall-off downwards, as on a surface that curves away from the light.
            float curve = (0.5 - uv.y) * 0.04;
            half3 color = base.rgb * (1.0 + brush + sheen + edge + curve) + half3(spec);
            return half4(color, 1.0);
        }
    """
}

/**
 * Draws brushed metal of the colour [base] behind the content when [enabled] and the device
 * can; otherwise draws nothing, and the content's own background shows.
 */
fun Modifier.metal(base: Color, enabled: Boolean): Modifier =
    if (enabled && Surfaces.available) metalSurface(base) else this

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.metalSurface(base: Color): Modifier = drawWithCache {
    val shader = RuntimeShader(Surfaces.METAL)
    shader.setFloatUniform("resolution", size.width, size.height)
    shader.setFloatUniform("light", size.width * 0.15f, -size.height * 0.6f)
    shader.setColorUniform("base", base.toArgb())
    val brush = ShaderBrush(shader)
    onDrawBehind { drawRect(brush) }
}
