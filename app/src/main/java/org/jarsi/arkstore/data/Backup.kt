package org.jarsi.arkstore.data

import org.jarsi.arkstore.work.AutoUpdate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What the user can take from one device to another in a file of their choosing: the
 * sources, the bookmarks and the settings. The GitHub token stays behind, and so does what
 * belongs to the device alone, such as the betas installed and the keys seen to differ.
 */
data class Backup(
    val sources: List<String>,
    val bookmarks: Set<String>,
    val settings: Settings,
    /** The updates not to offer; [UpdatePolicy.NONE] when the file does not say. */
    val updates: UpdatePolicy = UpdatePolicy.NONE
) {

    /** Each setting as the file tells it, or null when the file does not say. */
    data class Settings(
        val includeBeta: Boolean? = null,
        val includeAuto: Boolean? = null,
        /** By the catalogue's source name, see [StoreApp.SOURCE_IZZY] and the like. */
        val catalogues: Map<String, Boolean> = emptyMap(),
        val palette: String? = null,
        val black: Boolean? = null,
        val material: Boolean? = null,
        val hideTop: Boolean? = null,
        val sort: String? = null,
        val autoUpdate: AutoUpdate? = null
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("includeBeta", includeBeta ?: JSONObject.NULL)
            .put("includeAuto", includeAuto ?: JSONObject.NULL)
            .put("catalogues", JSONObject(catalogues))
            .put("palette", palette ?: JSONObject.NULL)
            .put("black", black ?: JSONObject.NULL)
            .put("material", material ?: JSONObject.NULL)
            .put("hideTop", hideTop ?: JSONObject.NULL)
            .put("sort", sort ?: JSONObject.NULL)
            .put(
                "autoUpdate",
                autoUpdate?.let {
                    JSONObject()
                        .put("enabled", it.enabled)
                        .put("unmeteredOnly", it.unmeteredOnly)
                        .put("chargingOnly", it.chargingOnly)
                } ?: JSONObject.NULL
            )

        companion object {
            fun fromJson(json: JSONObject?): Settings {
                if (json == null) return Settings()
                val catalogues = json.optJSONObject("catalogues")?.let { entry ->
                    entry.keys().asSequence()
                        .mapNotNull { key -> (entry.opt(key) as? Boolean)?.let { key to it } }
                        .toMap()
                }.orEmpty()
                val auto = json.optJSONObject("autoUpdate")?.let { entry ->
                    val enabled = entry.opt("enabled") as? Boolean
                    val unmetered = entry.opt("unmeteredOnly") as? Boolean
                    val charging = entry.opt("chargingOnly") as? Boolean
                    if (enabled != null && unmetered != null && charging != null) {
                        AutoUpdate(enabled, unmetered, charging)
                    } else {
                        null
                    }
                }
                return Settings(
                    includeBeta = json.opt("includeBeta") as? Boolean,
                    includeAuto = json.opt("includeAuto") as? Boolean,
                    catalogues = catalogues,
                    palette = json.opt("palette") as? String,
                    black = json.opt("black") as? Boolean,
                    material = json.opt("material") as? Boolean,
                    hideTop = json.opt("hideTop") as? Boolean,
                    sort = json.opt("sort") as? String,
                    autoUpdate = auto
                )
            }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("app", APP)
        .put("version", VERSION)
        .put("sources", JSONArray(sources))
        .put("bookmarks", JSONArray(bookmarks.toList()))
        .put("settings", settings.toJson())
        .put(
            "updates",
            JSONObject()
                .put("skipped", JSONObject(updates.skipped))
                .put("held", JSONArray(updates.held.toList()))
        )

    companion object {
        const val APP = "org.jarsi.arkstore"
        const val VERSION = 1
        const val FILE_NAME = "ark-store-settings.json"
        const val MIME_TYPE = "application/json"

        /** The most bytes a file is read for: a backup is a few kilobytes. */
        const val MAX_BYTES = 1024 * 1024

        /** The most sources and bookmarks taken from a file; a device has far fewer. */
        const val MAX_SOURCES = 1000
        const val MAX_BOOKMARKS = 10_000

        private const val MAX_KEY_LENGTH = 255

        /**
         * The backup [text] holds, or null when it is not a file the store exported, or one
         * of a newer store than this. Sources that are no sources are left out, as is a
         * source already there under another case.
         */
        fun fromJson(text: String): Backup? {
            val json = try {
                JSONObject(text)
            } catch (_: JSONException) {
                return null
            }
            if (json.optString("app") != APP || json.optInt("version", Int.MAX_VALUE) > VERSION) return null
            val sources = mergedSources(emptyList(), strings(json.optJSONArray("sources")).mapNotNull { SourceStore.parse(it) })
                .take(MAX_SOURCES)
            val bookmarks = strings(json.optJSONArray("bookmarks"))
                .filter { it.isNotBlank() && it.length <= MAX_KEY_LENGTH }
                .take(MAX_BOOKMARKS)
                .toSet()
            return Backup(
                sources,
                bookmarks,
                Settings.fromJson(json.optJSONObject("settings")),
                updatePolicy(json.optJSONObject("updates"))
            )
        }

        private fun updatePolicy(json: JSONObject?): UpdatePolicy {
            if (json == null) return UpdatePolicy.NONE
            val skipped = json.optJSONObject("skipped")?.let { entry ->
                entry.keys().asSequence()
                    .filter { it.isNotBlank() && it.length <= MAX_KEY_LENGTH }
                    .mapNotNull { key -> (entry.opt(key) as? Number)?.takeIf { it is Int || it is Long }?.let { key to it.toLong() } }
                    .take(MAX_BOOKMARKS)
                    .toMap()
            }.orEmpty()
            val held = strings(json.optJSONArray("held"))
                .filter { it.isNotBlank() && it.length <= MAX_KEY_LENGTH }
                .take(MAX_BOOKMARKS)
                .toSet()
            return UpdatePolicy(skipped, held)
        }

        /** [current] with those of [imported] not yet among them, under whatever case, after them. */
        fun mergedSources(current: List<String>, imported: List<String>): List<String> {
            val seen = current.mapTo(HashSet()) { it.lowercase() }
            return current + imported.filter { seen.add(it.lowercase()) }
        }

        private fun strings(array: JSONArray?): List<String> =
            array?.let { list -> List(list.length()) { list.opt(it) as? String }.filterNotNull() }.orEmpty()
    }
}
