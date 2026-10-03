package org.jarsi.arkstore.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jarsi.arkstore.data.AppIcon
import org.jarsi.arkstore.data.CatalogRules
import org.jarsi.arkstore.data.Http
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

/** The icon at [address], once it has been loaded; null while it is loading or when it cannot be had. */
@Composable
private fun rememberIcon(address: String?): IconLoader.Loaded? {
    val context = LocalContext.current.applicationContext
    return produceState(initialValue = address?.let { IconLoader.cached(it) }, address) {
        if (address != null && value == null) value = IconLoader.load(context, address)
    }.value
}

@Composable
private fun IconLoader.Loaded.painter(): Painter = when (this) {
    is IconLoader.Loaded.Image -> remember(this) { BitmapPainter(bitmap) }
    is IconLoader.Loaded.Vector -> rememberVectorPainter(image)
}

/**
 * Fetches the icons the index publishes and keeps them: in memory for the rows on screen,
 * on disk for the next start. An address that could not be had is not asked for again
 * until the app starts again.
 */
object IconLoader {
    private const val TAG = "IconLoader"
    private const val MAX_BYTES = 512 * 1024
    /** An image is decoded no larger than this on a side: a row shows it at 48 dp. */
    private const val MAX_PIXELS = 192
    private const val MEMORY_BYTES = 6 * 1024 * 1024

    sealed class Loaded(val bytes: Int) {
        class Image(val bitmap: ImageBitmap) : Loaded(bitmap.width * bitmap.height * 4)
        class Vector(val image: ImageVector) : Loaded(4 * 1024)
    }

    private val memory = object : LruCache<String, Loaded>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Loaded): Int = value.bytes
    }
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val fetching = Semaphore(4)

    fun cached(address: String): Loaded? = memory.get(address)

    suspend fun load(context: Context, address: String): Loaded? {
        memory.get(address)?.let { return it }
        if (address in failed) return null
        val loaded = withContext(Dispatchers.IO) {
            try {
                decode(address, bytes(context, address))
            } catch (e: IOException) {
                Log.w(TAG, "No icon from $address: $e")
                null
            } catch (e: RuntimeException) {
                // A file that is not what it should be: JSON that is not an icon, say.
                Log.w(TAG, "Odd icon from $address", e)
                null
            }
        }
        if (loaded == null) failed += address else memory.put(address, loaded)
        return loaded
    }

    /** The file at [address], from the copy on disk or, failing that, the network. */
    private suspend fun bytes(context: Context, address: String): ByteArray {
        val file = File(context.cacheDir, "icons/" + digest(address))
        try {
            if (file.exists()) return file.readBytes()
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the copy of $address", e)
        }
        val bytes = fetching.withPermit { Http.getBytes(address, MAX_BYTES) }
        try {
            // Written beside and moved into place, so that a file cut short is never read.
            file.parentFile?.mkdirs()
            val part = File(file.path + ".part")
            part.writeBytes(bytes)
            if (!part.renameTo(file)) part.delete()
        } catch (e: IOException) {
            Log.w(TAG, "Could not keep a copy of $address", e)
        }
        return bytes
    }

    private fun decode(address: String, bytes: ByteArray): Loaded? {
        if (address.substringBefore('?').endsWith(".json")) {
            return VectorDrawables.fromJson(String(bytes, Charsets.UTF_8))?.let { Loaded.Vector(it) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decoded at a fraction of its size when it is far larger than a row shows it.
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (bounds.outWidth / (options.inSampleSize * 2) >= MAX_PIXELS &&
            bounds.outHeight / (options.inSampleSize * 2) >= MAX_PIXELS
        ) {
            options.inSampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { Loaded.Image(it.asImageBitmap()) }
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
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
        builder.add(root)
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
