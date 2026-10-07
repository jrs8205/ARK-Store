package org.jarsi.arkstore.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jarsi.arkstore.data.Http

/** The icons the index publishes: small, and many on screen at once. */
val IconLoader = ImageLoader("icons", maxBytes = 512 * 1024, maxPixels = 192, memoryBytes = 6 * 1024 * 1024, parallel = 4)

/** Screenshots in the strip the details show, decoded at about half of a phone's screen. */
val ScreenshotLoader = ImageLoader(
    "screenshots",
    maxBytes = 2 * 1024 * 1024,
    maxPixels = 720,
    memoryBytes = 16 * 1024 * 1024,
    parallel = 2,
    config = Bitmap.Config.RGB_565
)

/** The same screenshots filling the screen, from the same copies on disk. */
val ScreenshotViewerLoader = ImageLoader(
    "screenshots",
    maxBytes = 2 * 1024 * 1024,
    maxPixels = 1440,
    memoryBytes = 12 * 1024 * 1024,
    parallel = 2,
    config = Bitmap.Config.RGB_565
)

/**
 * Fetches images from the web and keeps them: in memory for what is on screen, on disk
 * for the next start. An image is decoded no larger than [maxPixels] on a side, in
 * [config]. An address that could not be had is not asked for again until the app
 * starts again.
 */
class ImageLoader(
    folder: String,
    maxBytes: Int,
    private val maxPixels: Int,
    memoryBytes: Int,
    parallel: Int,
    private val config: Bitmap.Config = Bitmap.Config.ARGB_8888
) {
    sealed class Loaded(val bytes: Int) {
        class Image(val bitmap: ImageBitmap, bytes: Int) : Loaded(bytes)
        class Vector(val image: ImageVector) : Loaded(4 * 1024)
    }

    private val files = CachedFiles(folder, maxBytes, parallel)
    private val memory = object : LruCache<String, Loaded>(memoryBytes) {
        override fun sizeOf(key: String, value: Loaded): Int = value.bytes
    }
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun cached(address: String): Loaded? = memory.get(address)

    suspend fun load(context: Context, address: String): Loaded? {
        memory.get(address)?.let { return it }
        if (address in failed) return null
        val loaded = withContext(Dispatchers.IO) {
            try {
                decode(address, files.bytes(context, address))
            } catch (e: IOException) {
                Log.w(TAG, "No image from $address: $e")
                null
            } catch (e: RuntimeException) {
                // A file that is not what it should be: JSON that is not an icon, say.
                Log.w(TAG, "Odd image from $address", e)
                null
            }
        }
        if (loaded == null) failed += address else memory.put(address, loaded)
        return loaded
    }

    private fun decode(address: String, bytes: ByteArray): Loaded? {
        if (address.substringBefore('?').endsWith(".json")) {
            return VectorDrawables.fromJson(String(bytes, Charsets.UTF_8))?.let { Loaded.Vector(it) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = config
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?.let { Loaded.Image(it.asImageBitmap(), it.byteCount) }
    }

    /**
     * The fraction, as a power of two, at which an image [width] by [height] is decoded: its
     * longer side ends up under twice [maxPixels], so that neither a large image nor a long
     * strip of one takes more memory than its place on screen is worth.
     */
    fun sampleSize(width: Int, height: Int): Int {
        var size = 1
        while (maxOf(width, height) / (size * 2) >= maxPixels) size *= 2
        return size
    }

    private companion object {
        const val TAG = "ImageLoader"
    }
}

/**
 * Files fetched from the web and kept under [folder] of the cache directory, at most
 * [maxBytes] each and [parallel] fetches at a time.
 */
class CachedFiles(private val folder: String, private val maxBytes: Int, parallel: Int) {
    private val fetching = Semaphore(parallel)

    /** The file at [address], from the copy on disk or, failing that, the network. */
    suspend fun bytes(context: Context, address: String): ByteArray {
        val file = File(context.cacheDir, "$folder/" + digest(address))
        try {
            if (file.exists()) return file.readBytes()
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the copy of $address", e)
        }
        val bytes = fetching.withPermit { Http.getBytes(address, maxBytes) }
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

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    private companion object {
        const val TAG = "CachedFiles"
    }
}
