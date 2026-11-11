package org.jarsi.arkstore.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A document in a folder the user has granted, as the folder's listing tells it. */
class Doc(val id: String, val name: String, val mime: String, val size: Long) {
    val isDirectory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}

/**
 * A rename the provider carried out otherwise than asked: the document is now [id], under a
 * name that is not the one asked for, or that could not be read.
 */
internal class RenamedOtherwise(val id: String, message: String) : IOException(message)

/**
 * Whether a listing is all there is: a provider may answer with what it has at hand and say
 * that the rest is still [loading], as one that keeps its files somewhere else does, or say
 * that an [error] kept it from listing everything.
 */
internal fun wholeListing(loading: Boolean, error: String?): Boolean = !loading && error == null

/**
 * The folder the user chose, through the documents provider that holds it. Nothing in it is
 * reached by a path: every document is found by listing its parent and named by the id the
 * provider gives it, so that whatever a file in the folder says, it can point nowhere else.
 */
internal class Documents(private val resolver: ContentResolver, val tree: Uri) {

    val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** The documents in the folder [parentId], or null when it cannot be listed. */
    fun children(parentId: String): List<Doc>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        return try {
            resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                val list = ArrayList<Doc>(cursor.count)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2).orEmpty()
                    val size = if (cursor.isNull(3)) -1L else cursor.getLong(3)
                    list += Doc(id, name, mime, size)
                }
                list
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list a folder", e)
            null
        }
    }

    /**
     * The ids of every document in the folder [parentId], or null when the listing could not
     * be had whole: the query failed, a row had no id, or the provider said what it gave was
     * not all of it; see [wholeListing]. Asked for when something is to be known to be gone, which a
     * listing that only happens to lack it does not tell.
     */
    fun ids(parentId: String): Set<String>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        return try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                val extras = cursor.extras
                val loading = extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
                if (!wholeListing(loading, extras?.getString(DocumentsContract.EXTRA_ERROR))) return null
                val ids = HashSet<String>(cursor.count)
                while (cursor.moveToNext()) ids += cursor.getString(0) ?: return null
                ids
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list a folder", e)
            null
        }
    }

    /** The name of the folder itself, as the provider shows it, or null when it does not say. */
    fun name(): String? = displayName(uri(rootId))

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read a document's name", e)
        null
    }

    @Throws(IOException::class)
    fun createDirectory(parentId: String, name: String): String =
        create(parentId, DocumentsContract.Document.MIME_TYPE_DIR, name)

    /**
     * Creates the document [name] of [mime] in [parentId] and returns its id. A provider that
     * has a document of that name already gives the new one another name; such a document is
     * removed again, since a backup finds its files by the names it gave them.
     */
    @Throws(IOException::class)
    fun create(parentId: String, mime: String, name: String): String {
        val created = try {
            DocumentsContract.createDocument(resolver, uri(parentId), mime, name)
        } catch (e: Exception) {
            throw IOException("Could not create $name", e)
        } ?: throw IOException("Could not create $name")
        val id = DocumentsContract.getDocumentId(created)
        val given = displayName(created)
        if (given != name) {
            delete(id)
            throw IOException("The folder named the new document $given instead of $name")
        }
        return id
    }

    /** Whether the provider says it can give the document [id] another name. */
    fun canRename(id: String): Boolean = try {
        resolver.query(uri(id), arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            it.moveToFirst() && it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0
        } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the flags of a document", e)
        false
    }

    /**
     * Gives the document [id] the name [name] and returns its id under that name, which a
     * provider may change with the name. A provider that names it otherwise, as when a
     * document of that name is there already, or whose name cannot be read back, has not
     * done what was asked; the document may be known by another id all the same, which
     * [RenamedOtherwise] then tells, so that it can still be read or removed.
     */
    @Throws(IOException::class)
    fun rename(id: String, name: String): String {
        val renamed = try {
            DocumentsContract.renameDocument(resolver, uri(id), name)
        } catch (e: Exception) {
            throw IOException("Could not rename a document to $name", e)
        } ?: throw IOException("Could not rename a document to $name")
        val now = DocumentsContract.getDocumentId(renamed)
        val given = displayName(renamed)
        if (given != name) throw RenamedOtherwise(now, "The folder named the document $given instead of $name")
        return now
    }

    fun delete(id: String): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri(id))
    } catch (e: Exception) {
        Log.w(TAG, "Could not delete a document", e)
        false
    }

    @Throws(IOException::class)
    fun openRead(id: String): InputStream =
        resolver.openInputStream(uri(id)) ?: throw IOException("Nothing to read from")

    /** Opens [id] for writing from the start, dropping what it held. */
    @Throws(IOException::class)
    fun openWrite(id: String): OutputStream =
        resolver.openOutputStream(uri(id), "wt") ?: throw IOException("Nothing to write to")

    /** The text of [id], or null when it cannot be read or is longer than [maxBytes]. */
    fun readText(id: String, maxBytes: Int): String? = try {
        openRead(id).use { input ->
            // One byte past the limit tells a file too large from one just at it.
            val bytes = ByteArray(maxBytes + 1)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) break
                count += n
            }
            if (count > maxBytes) null else String(bytes, 0, count, Charsets.UTF_8)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read a document", e)
        null
    }

    @Throws(IOException::class)
    fun writeText(id: String, text: String) {
        openWrite(id).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private companion object {
        const val TAG = "Documents"
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }
}
