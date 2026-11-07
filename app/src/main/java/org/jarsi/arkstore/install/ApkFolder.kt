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
    /** The name of the downloads folder, as [Environment.DIRECTORY_DOWNLOADS] gives it on every Android. */
    private const val DOWNLOADS = "Download"
    /** Shows the device's own storage among the places of the Files app of Android 8 and 9. */
    private const val EXTRA_SHOW_ADVANCED = "android.provider.extra.SHOW_ADVANCED"

    /** The folder of one restore, under [FOLDER]. */
    fun restoreSubfolder(date: LocalDate): String = "restore-$date"

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
        val target = reserve(dir, displayName)
        try {
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        return Uri.fromFile(target)
    }

    /**
     * Makes an empty file in [dir] named [displayName], or with a number when that is taken,
     * so that two saves at once never write to the same file.
     */
    internal fun reserve(dir: File, displayName: String): File {
        val base = displayName.removeSuffix(".apk")
        var target = File(dir, displayName)
        var n = 1
        while (!target.createNewFile()) target = File(dir, "$base (${n++}).apk")
        return target
    }

    private fun relative(subfolder: String?): String = if (subfolder == null) FOLDER else "$FOLDER/$subfolder"

    /**
     * Whether the file the store saved at [uri] is still there, in the folder: one the user
     * moved elsewhere keeps its entry in MediaStore, and is theirs now.
     */
    fun exists(context: Context, uri: String): Boolean {
        val parsed = uri.toUri()
        return stillSaved(
            isFile = parsed.scheme == "file",
            fileExists = { parsed.path?.let { File(it).exists() } == true },
            relativePath = { relativePath(context, parsed) }
        )
    }

    /** [exists] apart from Android: a file of its own on Android 8 and 9, an entry of MediaStore later. */
    internal fun stillSaved(isFile: Boolean, fileExists: () -> Boolean, relativePath: () -> String?): Boolean =
        if (isFile) fileExists() else SavedApks.inFolder(relativePath())

    /**
     * Deletes the file the store saved at [uri]; one already gone, not the store's to delete, or
     * moved out of the folder by the user, which keeps its entry in MediaStore, is left.
     */
    fun delete(context: Context, uri: String) {
        val parsed = uri.toUri()
        try {
            if (parsed.scheme == "file") {
                parsed.path?.let { File(it).delete() }
            } else if (SavedApks.inFolder(relativePath(context, parsed))) {
                context.contentResolver.delete(parsed, null, null)
            } else {
                Log.i(TAG, "Not deleting $uri: it is no longer in the folder")
            }
        } catch (e: Exception) {
            Log.i(TAG, "Could not delete $uri: ${e.message}")
        }
    }

    /** Where MediaStore has the file at [uri], or null when it cannot tell. */
    private fun relativePath(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** The folder as the Files app shows its path, for the texts that point the user to it. */
    fun displayPath(context: Context, subfolder: String? = null): String =
        pathOf(Build.VERSION.SDK_INT, context.packageName, subfolder)

    /** [displayPath] on Android [sdk], for the store [packageName]. */
    internal fun pathOf(sdk: Int, packageName: String, subfolder: String?): String =
        if (sdk >= Build.VERSION_CODES.Q) {
            "$DOWNLOADS/${relative(subfolder)}"
        } else {
            "Android/data/$packageName/files/$DOWNLOADS/${relative(subfolder)}"
        }

    /** One way of asking the Files app to show something: [action], on [uri] of [type]. */
    internal data class FolderView(val action: String, val uri: String?, val type: String?, val showAdvanced: Boolean = false)

    /**
     * What the Files app is asked to show, in turn, on Android [sdk]: from Android 10 on the
     * folder itself; before that, its Files app takes no folder to open and closes on one, so
     * the device's storage, from where the path told leads to the folder. Then the downloads.
     */
    internal fun folderViews(sdk: Int, packageName: String, subfolder: String?): List<FolderView> {
        val first = if (sdk >= Build.VERSION_CODES.Q) {
            val document = "primary:" + pathOf(sdk, packageName, subfolder)
            FolderView(
                Intent.ACTION_VIEW,
                "content://$EXTERNAL_STORAGE/document/" + document.replace(":", "%3A").replace("/", "%2F"),
                DocumentsContract.Document.MIME_TYPE_DIR
            )
        } else {
            FolderView(Intent.ACTION_VIEW, "content://$EXTERNAL_STORAGE/root/primary", DocumentsContract.Root.MIME_TYPE_ITEM, showAdvanced = true)
        }
        return listOf(first, FolderView(DownloadManager.ACTION_VIEW_DOWNLOADS, null, null))
    }

    /**
     * Opens the folder in the Files app, see [folderViews]; failing that, says where it is, as
     * it does too where the Files app cannot open the folder itself. The store sees no app that
     * is not in the launcher, so it cannot ask beforehand which of these works: it tries them
     * in turn.
     */
    fun open(context: Context, subfolder: String? = null) {
        val sdk = Build.VERSION.SDK_INT
        var opened = false
        for (view in folderViews(sdk, context.packageName, subfolder)) {
            val intent = Intent(view.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (view.uri != null) intent.setDataAndType(view.uri.toUri(), view.type)
            if (view.showAdvanced) intent.putExtra(EXTRA_SHOW_ADVANCED, true)
            try {
                context.startActivity(intent)
                opened = true
                break
            } catch (e: ActivityNotFoundException) {
                Log.i(TAG, "Nothing opened ${view.action}")
            } catch (e: SecurityException) {
                Log.i(TAG, "Not allowed to open ${view.action}")
            }
        }
        if (!opened || sdk < Build.VERSION_CODES.Q) {
            Toast.makeText(
                context,
                context.getString(R.string.open_folder_by_hand, displayPath(context, subfolder)),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
