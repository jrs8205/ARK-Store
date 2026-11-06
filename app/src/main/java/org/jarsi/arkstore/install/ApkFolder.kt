package org.jarsi.arkstore.install

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jarsi.arkstore.R

/**
 * The folder the store saves checked files to for the user to install from the Files app:
 * Download/ARK-Store, or on Android 8 and 9, where that would take a permission, the store's
 * own folder of downloads, which the Files app shows there as well. Nothing here needs a
 * permission, and nothing here deletes a file the store did not save.
 */
object ApkFolder {
    private const val TAG = "ApkFolder"
    const val FOLDER = "ARK-Store"
    private const val MIME = "application/vnd.android.package-archive"
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /** The folder of one restore, under [FOLDER]. */
    fun restoreSubfolder(date: LocalDate): String = "restore-$date"

    private val viaMediaStore: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Copies [source] to the folder, or to its [subfolder], as [displayName], and returns where
     * it went. A file of that name already there is kept, and the copy is given another name.
     */
    suspend fun save(context: Context, source: File, displayName: String, subfolder: String? = null): Uri =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(context, source, displayName, subfolder)
            } else {
                saveToFile(context, source, displayName, subfolder)
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(context: Context, source: File, displayName: String, subfolder: String?): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${relative(subfolder)}/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("The folder of downloads took no new file")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("The new file could not be opened")
            output.use { out -> source.inputStream().use { it.copyTo(out, 64 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e as? IOException ?: IOException(e)
        }
        return uri
    }

    private fun saveToFile(context: Context, source: File, displayName: String, subfolder: String?): Uri {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("No shared storage to save to")
        val dir = File(root, relative(subfolder))
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Could not make $dir")
        val base = displayName.removeSuffix(".apk")
        var target = File(dir, displayName)
        var n = 1
        while (target.exists()) target = File(dir, "$base (${n++}).apk")
        try {
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        return Uri.fromFile(target)
    }

    private fun relative(subfolder: String?): String = if (subfolder == null) FOLDER else "$FOLDER/$subfolder"

    /** Whether the file the store saved at [uri] is still there. */
    fun exists(context: Context, uri: String): Boolean {
        val parsed = uri.toUri()
        if (parsed.scheme == "file") return parsed.path?.let { File(it).exists() } == true
        return try {
            context.contentResolver.query(parsed, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.count > 0 } == true
        } catch (e: Exception) {
            false
        }
    }

    /** Deletes the file the store saved at [uri]; one already gone, or not the store's to delete, is left. */
    fun delete(context: Context, uri: String) {
        val parsed = uri.toUri()
        try {
            if (parsed.scheme == "file") {
                parsed.path?.let { File(it).delete() }
            } else {
                context.contentResolver.delete(parsed, null, null)
            }
        } catch (e: Exception) {
            Log.i(TAG, "Could not delete $uri: ${e.message}")
        }
    }

    /** The folder as the Files app shows its path, for the texts that point the user to it. */
    fun displayPath(context: Context, subfolder: String? = null): String =
        if (viaMediaStore) {
            "${Environment.DIRECTORY_DOWNLOADS}/${relative(subfolder)}"
        } else {
            "Android/data/${context.packageName}/files/${Environment.DIRECTORY_DOWNLOADS}/${relative(subfolder)}"
        }

    /**
     * Opens the folder in the Files app; failing that, the downloads; failing that, says where
     * the folder is. The store sees no app that is not in the launcher, so it cannot ask
     * beforehand which of these works: it tries them in turn.
     */
    fun open(context: Context, subfolder: String? = null) {
        val document = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:${displayPath(context, subfolder)}")
        val tries = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(document, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        )
        for (intent in tries) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "Nothing opened ${intent.action}")
            } catch (e: SecurityException) {
                Log.i(TAG, "Not allowed to open ${intent.action}")
            }
        }
        Toast.makeText(
            context,
            context.getString(R.string.open_folder_by_hand, displayPath(context, subfolder)),
            Toast.LENGTH_LONG
        ).show()
    }
}
