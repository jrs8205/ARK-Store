package org.jarsi.arkstore.install

import java.text.Normalizer
import org.json.JSONException
import org.json.JSONObject

/**
 * A checked file the store saved to the device's folder for the user to install, as the store
 * cannot install it itself: of [packageName] at [versionCode], found at [uri], downloaded for
 * the catalogue's [repo].
 */
data class SavedApk(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val uri: String,
    val repo: String
)

/** The rules of the files the store saves for the user to install; see [ApkFolder]. */
object SavedApks {
    private const val MAX_NAME = 100
    private const val MAX_VERSION = 40
    private const val EXTENSION = ".apk"

    /** Where the store's files are, as MediaStore gives a file's relative path. */
    private const val FOLDER_PATH = "Download/ARK-Store"

    /**
     * The name a file of [title] at [versionName] (or [versionCode] when it has none) is saved
     * under: letters, digits, dots, hyphens and underscores only, never hidden, never more
     * than [MAX_NAME] characters, so that any app's name makes a name the folder takes. A
     * title in a script of its own, which leaves nothing of that, is named by its [fallback],
     * the package, so that the files can still be told apart.
     */
    fun fileName(title: String, versionName: String?, versionCode: Long, fallback: String? = null): String {
        val version = clean(versionName.orEmpty()).take(MAX_VERSION).trimEnd('-', '.')
            .ifEmpty { versionCode.toString() }
        val tail = "-$version$EXTENSION"
        val room = MAX_NAME - tail.length
        val name = clean(title).take(room).trimEnd('-', '.')
            .ifEmpty { clean(fallback.orEmpty()).take(room).trimEnd('-', '.') }
            .ifEmpty { "app" }
        return name + tail
    }

    /**
     * Whether a file at [relativePath], as MediaStore tells it, is still in the store's folder:
     * one the user moved elsewhere is theirs, and is never deleted.
     */
    fun inFolder(relativePath: String?): Boolean {
        val path = relativePath?.trimEnd('/') ?: return false
        return path == FOLDER_PATH || path.startsWith("$FOLDER_PATH/")
    }

    private fun clean(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-', '.')

    /**
     * The records written by [toJson], by repository. Up to 1.11.0 they were written by package,
     * with no package inside: the key then is the package.
     */
    fun parse(json: String?): Map<String, SavedApk> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(json)
            root.keys().asSequence().map { key ->
                val record = root.getJSONObject(key)
                SavedApk(
                    packageName = if (record.has("packageName")) record.getString("packageName") else key,
                    versionCode = record.getLong("versionCode"),
                    versionName = if (record.isNull("versionName")) null else record.getString("versionName"),
                    uri = record.getString("uri"),
                    repo = record.getString("repo")
                )
            }.associateBy { it.repo }
        } catch (e: JSONException) {
            emptyMap()
        }
    }

    fun toJson(saved: Map<String, SavedApk>): String {
        val root = JSONObject()
        for (record in saved.values) {
            root.put(
                record.repo,
                JSONObject()
                    .put("packageName", record.packageName)
                    .put("versionCode", record.versionCode)
                    .put("versionName", record.versionName ?: JSONObject.NULL)
                    .put("uri", record.uri)
                    .put("repo", record.repo)
            )
        }
        return root.toString()
    }

    /**
     * The records of [saved] whose file is still there, by [exists]: a file the user removed is
     * no longer offered. One whose version is installed by now is kept all the same, so that
     * its file goes when a newer one comes; the rows no longer show it, see [shownFor].
     */
    fun kept(saved: Map<String, SavedApk>, exists: (SavedApk) -> Boolean): Map<String, SavedApk> =
        saved.filterValues(exists)

    /**
     * [saved], when a row shows it as waiting to be installed: it is newer than the
     * [installedVersion], and at least the [offeredVersion] (0 when the catalogue does not know
     * it yet). When the catalogue offers a newer version, the row offers that to download.
     */
    fun shownFor(saved: SavedApk?, installedVersion: Long?, offeredVersion: Long): SavedApk? =
        saved?.takeIf {
            (installedVersion == null || installedVersion < it.versionCode) &&
                (offeredVersion <= 0 || it.versionCode >= offeredVersion)
        }

    /** What the records become after a save: [records], and the files to [delete]. */
    data class Update(val records: Map<String, SavedApk>, val delete: List<String>)

    /**
     * What [saved], by repository, becomes once [record] is saved: it takes the place of its
     * repository's record, and of the records of the same package from other repositories of an
     * older version, whose files are deleted. A newer file of another repository stays, and so
     * does the one just saved, so that whichever of two downloads ends last, neither costs the
     * other its file. Only what the store itself saved is ever deleted.
     */
    fun withSaved(saved: Map<String, SavedApk>, record: SavedApk): Update {
        val replaced = saved.values.filter {
            it.repo == record.repo ||
                (it.packageName == record.packageName && it.versionCode < record.versionCode)
        }
        val records = saved - replaced.map { it.repo }.toSet() + (record.repo to record)
        return Update(records, replaced.map { it.uri }.filter { it != record.uri }.distinct())
    }
}
