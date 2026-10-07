package org.jarsi.arkstore.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.EmptyPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import org.jarsi.arkstore.data.AppIcon
import org.jarsi.arkstore.data.CatalogRules
import org.json.JSONException
import org.json.JSONObject

/**
 * The icon shown with a row of the list: the installed app's own, else the one the index
 * publishes (see [AppIcon]), else the first letter of [name].
 */
@Composable
fun RowIcon(name: String, packageName: String?, installed: Boolean, icon: AppIcon?, size: Dp = 48.dp) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(size / 4)
    val own = remember(packageName, installed) {
        if (!installed || packageName == null) {
            null
        } else {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(144, 144)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    when {
        own != null -> Image(
            bitmap = own,
            contentDescription = null,
            modifier = Modifier.size(size).clip(shape)
        )
        icon?.address != null -> {
            val painter = rememberIcon(icon.address)?.painter()
            if (painter != null) {
                Image(painter = painter, contentDescription = null, modifier = Modifier.size(size).clip(shape))
            } else {
                Initial(name, size)
            }
        }
        icon?.foreground != null -> Layered(icon, name, size, shape)
        else -> Initial(name, size)
    }
}

/** An icon drawn in layers: the middle 72 of a layer's 108 units show, as on a launcher. */
@Composable
private fun Layered(icon: AppIcon, name: String, size: Dp, shape: Shape) {
    val color = icon.background?.let { VectorDrawables.color(it) }
    val background = rememberIcon(if (color == null) icon.background else null)?.painter()
    val foreground = rememberIcon(icon.foreground)?.painter()
    if (foreground == null) {
        Initial(name, size)
        return
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .then(if (color != null) Modifier.background(color) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (background != null) {
            Image(painter = background, contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
        }
        Image(painter = foreground, contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
    }
}

@Composable
private fun Initial(name: String, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.secondary, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = CatalogRules.initial(name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondary
        )
    }
}

/**
 * The icon at [address], once it has been loaded; null while it is loading or when it cannot
 * be had. The state belongs to the address: a row keeps its place in the list while the
 * icon of its app changes, and then shows the new one.
 */
@Composable
private fun rememberIcon(address: String?): ImageLoader.Loaded? = rememberImage(IconLoader, address)

/** The image at [address] as [loader] has it, once it has been loaded; see [rememberIcon]. */
@Composable
internal fun rememberImage(loader: ImageLoader, address: String?): ImageLoader.Loaded? {
    val context = LocalContext.current.applicationContext
    val loaded = remember(loader, address) { mutableStateOf(address?.let { loader.cached(it) }) }
    LaunchedEffect(loader, address) {
        if (address != null && loaded.value == null) loaded.value = loader.load(context, address)
    }
    return loaded.value
}

@Composable
internal fun ImageLoader.Loaded.painter(): Painter = when (this) {
    is ImageLoader.Loaded.Image -> remember(this) { BitmapPainter(bitmap) }
    is ImageLoader.Loaded.Vector -> rememberVectorPainter(image)
}

/**
 * Vector drawables as the index describes them, built for Compose to draw. The description
 * is JSON written by the index builder: the width and height of the canvas and a tree of
 * nodes under "root", each node a path ("path", "fill", "stroke", "strokeWidth",
 * "fillAlpha", "strokeAlpha", "fillType", "cap", "join") or a group ("rotation", "pivotX",
 * "pivotY", "scaleX", "scaleY", "translateX", "translateY", "clip", "nodes"). Colours are
 * written as "#aarrggbb". A path that cannot be read is left out.
 */
object VectorDrawables {

    fun fromJson(text: String): ImageVector? {
        val json = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return null
        }
        val width = json.optDouble("width").toFloat()
        val height = json.optDouble("height").toFloat()
        val root = json.optJSONObject("root")
        if (!(width > 0f && height > 0f) || root == null) return null
        val builder = ImageVector.Builder(
            defaultWidth = 108.dp,
            defaultHeight = 108.dp,
            viewportWidth = width,
            viewportHeight = height
        )
        // The root may be clipped like any group; its clip needs a group of its own.
        val clip = root.optString("clip").takeIf { it.isNotEmpty() }?.let { pathNodes(it) }
        if (clip != null) builder.group(clipPathData = clip) { add(root) } else builder.add(root)
        return builder.build()
    }

    private fun ImageVector.Builder.add(node: JSONObject) {
        val nodes = node.optJSONArray("nodes") ?: return
        for (index in 0 until nodes.length()) {
            val child = nodes.optJSONObject(index) ?: continue
            if (child.has("path")) {
                addPath(child)
            } else {
                group(
                    rotate = child.optDouble("rotation", 0.0).toFloat(),
                    pivotX = child.optDouble("pivotX", 0.0).toFloat(),
                    pivotY = child.optDouble("pivotY", 0.0).toFloat(),
                    scaleX = child.optDouble("scaleX", 1.0).toFloat(),
                    scaleY = child.optDouble("scaleY", 1.0).toFloat(),
                    translationX = child.optDouble("translateX", 0.0).toFloat(),
                    translationY = child.optDouble("translateY", 0.0).toFloat(),
                    clipPathData = child.optString("clip").takeIf { it.isNotEmpty() }?.let { pathNodes(it) } ?: EmptyPath
                ) {
                    add(child)
                }
            }
        }
    }

    private fun ImageVector.Builder.addPath(node: JSONObject) {
        val data = pathNodes(node.optString("path")) ?: return
        addPath(
            pathData = data,
            pathFillType = if (node.optInt("fillType") == 1) PathFillType.EvenOdd else PathFillType.NonZero,
            fill = color(node.optString("fill"))?.let { SolidColor(it) },
            fillAlpha = node.optDouble("fillAlpha", 1.0).toFloat(),
            stroke = color(node.optString("stroke"))?.let { SolidColor(it) },
            strokeAlpha = node.optDouble("strokeAlpha", 1.0).toFloat(),
            strokeLineWidth = node.optDouble("strokeWidth", 0.0).toFloat(),
            strokeLineCap = when (node.optInt("cap")) {
                1 -> StrokeCap.Round
                2 -> StrokeCap.Square
                else -> StrokeCap.Butt
            },
            strokeLineJoin = when (node.optInt("join")) {
                1 -> StrokeJoin.Round
                2 -> StrokeJoin.Bevel
                else -> StrokeJoin.Miter
            }
        )
    }

    private fun pathNodes(data: String): List<PathNode>? = try {
        PathParser().parsePathString(data).toNodes()
    } catch (_: RuntimeException) {
        null
    }

    /** A colour written as "#rrggbb" or "#aarrggbb", or null for anything else. */
    fun color(text: String): Color? {
        val hex = text.removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val value = hex.toLongOrNull(16) ?: return null
        return Color((if (hex.length == 6) 0xFF000000L or value else value).toInt())
    }
}
