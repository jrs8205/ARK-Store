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

    /**
     * The name a file of [title] at [versionName] (or [versionCode] when it has none) is saved
     * under: letters, digits, dots, hyphens and underscores only, never hidden, never more
     * than [MAX_NAME] characters, so that any app's name makes a name the folder takes.
     */
    fun fileName(title: String, versionName: String?, versionCode: Long): String {
        val version = clean(versionName.orEmpty()).take(MAX_VERSION).trimEnd('-', '.')
            .ifEmpty { versionCode.toString() }
        val tail = "-$version$EXTENSION"
        val name = clean(title).take(MAX_NAME - tail.length).trimEnd('-', '.').ifEmpty { "app" }
        return name + tail
    }

    private fun clean(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-', '.')

    fun parse(json: String?): Map<String, SavedApk> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(json)
            root.keys().asSequence().associateWith { packageName ->
                val record = root.getJSONObject(packageName)
                SavedApk(
                    packageName = packageName,
                    versionCode = record.getLong("versionCode"),
                    versionName = if (record.isNull("versionName")) null else record.getString("versionName"),
                    uri = record.getString("uri"),
                    repo = record.getString("repo")
                )
            }
        } catch (e: JSONException) {
            emptyMap()
        }
    }

    fun toJson(saved: Map<String, SavedApk>): String {
        val root = JSONObject()
        for ((packageName, record) in saved) {
            root.put(
                packageName,
                JSONObject()
                    .put("versionCode", record.versionCode)
                    .put("versionName", record.versionName ?: JSONObject.NULL)
                    .put("uri", record.uri)
                    .put("repo", record.repo)
            )
        }
        return root.toString()
    }

    /**
     * The records of [saved] still waiting to be installed: the version is not installed yet,
     * by [installedVersion] (null when the app is not installed), and the file is still there,
     * by [exists]. A file the user removed is no longer offered.
     */
    fun pending(
        saved: Map<String, SavedApk>,
        installedVersion: (String) -> Long?,
        exists: (SavedApk) -> Boolean
    ): Map<String, SavedApk> =
        saved.filter { (packageName, record) ->
            installedVersion(packageName).let { it == null || it < record.versionCode } && exists(record)
        }

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

    /**
     * The files to delete once a file of [packageName] is saved at [newUri]: the one recorded
     * for it before, if any. Only what the store itself recorded is ever deleted.
     */
    fun olderToDelete(saved: Map<String, SavedApk>, packageName: String, newUri: String): List<String> =
        listOfNotNull(saved[packageName]?.uri?.takeIf { it != newUri })
}
