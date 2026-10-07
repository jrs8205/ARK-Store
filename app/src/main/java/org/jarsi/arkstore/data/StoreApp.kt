package org.jarsi.arkstore.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One app as one place offers it: a repository's latest release that carries an installable
 * APK, or a package of another catalogue.
 */
data class StoreApp(
    /**
     * Identifies the app throughout the store: "owner/repo" for a repository on GitHub, the
     * same after the name of the place, such as "codeberg:owner/repo", for one elsewhere (on
     * GitLab the path can have more parts, as in "gitlab:group/subgroup/project"), and
     * the catalogue and package, such as "fdroid:org.example", for an app of a catalogue.
     */
    val fullName: String,
    val description: String,
    val stars: Int,
    /** One of [Categories.ALL]. */
    val category: String,
    /** SPDX identifier of the repository's licence, e.g. "GPL-3.0". */
    val license: String,
    /**
     * Downloads of APK files summed over the repository's recent full releases or, for a
     * prerelease, over its recent prereleases.
     */
    val downloads: Long,
    val repoUrl: String,
    /** True when this is a prerelease, offered because beta versions are wanted. */
    val prerelease: Boolean = false,
    /**
     * True when the app was found by searching GitHub rather than published to the store by
     * its developer. In the catalogue shown to the user it is false for an app that also comes
     * from one of the sources on this device.
     */
    val auto: Boolean = false,
    val tag: String,
    val releaseName: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val publishedAt: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val assetId: Long,
    /** Null when the APK's manifest could not be read remotely. */
    val packageName: String?,
    val versionCode: Long,
    val versionName: String?,
    /**
     * The APK's default name for a repository release, or a translated metadata name for
     * a catalogue entry. Null when unknown; the repository's name then stands in.
     */
    val label: String? = null,
    /** Downloads of APK files summed over all of the repository's recent releases. */
    val allDownloads: Long = downloads,
    /** Where the app is offered from: one of the SOURCE constants. */
    val source: String = SOURCE_GITHUB,
    /** Who made the app, as a catalogue tells it. */
    val author: String? = null,
    /** SHA-256 of the certificate the APK is signed with, in hex, when the catalogue tells it. */
    val signer: String? = null,
    /** SHA-256 the APK file must have, in hex, when the catalogue tells it. */
    val sha256: String? = null,
    /**
     * The lowest Android API level the APK runs on, as its manifest says it, when the
     * catalogue tells it; null when it does not, as for a release read straight from GitHub.
     */
    val minSdk: Int? = null,
    /**
     * The codename of the preview of Android the APK's manifest names as the lowest it runs
     * on, instead of a number, when the index tells it. Only that preview runs the file.
     */
    val minSdkCodename: String? = null,
    /** What a catalogue warns about in the app, such as tracking, in its own words. */
    val antiFeatures: List<String> = emptyList(),
    /** The app's icon as the index publishes it, or null when it publishes none. */
    val icon: AppIcon? = null,
    /**
     * What the developer publishes of the app beyond its summary, by language ("en", "fi"),
     * as the index tells it; empty when it tells nothing.
     */
    val metadata: Map<String, AppMetadata> = emptyMap()
) {
    /** Whether the app comes from a repository's releases rather than from a catalogue. */
    val fromRepository: Boolean
        get() = source == SOURCE_GITHUB || source in ELSEWHERE

    /** "owner/repo" of a repository, wherever it is; only meaningful with [fromRepository]. */
    val repoPath: String
        get() = if (source == SOURCE_GITHUB) fullName else fullName.substringAfter(':')

    /** What the app is called in the store. */
    val title: String
        get() = label ?: repo

    val owner: String
        get() = repoPath.substringBefore('/')

    val repo: String
        get() = if (fromRepository) repoPath.substringAfter('/') else fullName.substringAfter(':')

    /** Who the app is by: its author as a catalogue tells it, else the repository's owner. */
    val developer: String
        get() = author?.takeIf { it.isNotBlank() } ?: if (fromRepository) owner else ""

    /** Version shown to the user: the manifest's name when known, else the release tag. */
    val displayVersion: String
        get() = versionName ?: tag.removePrefix("v")

    /** What the APK's manifest said of it, or null when the manifest could not be read. */
    val apkInfo: ApkInfo?
        get() = packageName?.let { ApkInfo(it, versionCode, versionName, minSdk, minSdkCodename) }

    fun toJson(): JSONObject = JSONObject()
        .put("fullName", fullName)
        .put("description", description)
        .put("stars", stars)
        .put("category", category)
        .put("license", license)
        .put("downloads", downloads)
        .put("repoUrl", repoUrl)
        .put("prerelease", prerelease)
        .put("auto", auto)
        .put("tag", tag)
        .put("releaseName", releaseName)
        .put("releaseNotes", releaseNotes)
        .put("releaseUrl", releaseUrl)
        .put("publishedAt", publishedAt)
        .put("apkName", apkName)
        .put("apkUrl", apkUrl)
        .put("apkSize", apkSize)
        .put("assetId", assetId)
        .put("packageName", packageName ?: JSONObject.NULL)
        .put("versionCode", versionCode)
        .put("versionName", versionName ?: JSONObject.NULL)
        .put("label", label ?: JSONObject.NULL)
        .put("allDownloads", allDownloads)
        .put("source", source)
        .put("author", author ?: JSONObject.NULL)
        .put("signer", signer ?: JSONObject.NULL)
        .put("signerReader", if (fromRepository) SIGNER_READER else JSONObject.NULL)
        .put("sha256", sha256 ?: JSONObject.NULL)
        .put("minSdk", minSdk ?: JSONObject.NULL)
        .put("minSdkCodename", minSdkCodename ?: JSONObject.NULL)
        .put("antiFeatures", JSONArray(antiFeatures))
        .put("icon", icon?.toJson() ?: JSONObject.NULL)
        .put("metadata", JSONObject().also { json -> metadata.forEach { (language, it) -> json.put(language, it.toJson()) } })

    companion object {
        const val SOURCE_GITHUB = "github"
        const val SOURCE_CODEBERG = "codeberg"
        const val SOURCE_GITLAB = "gitlab"

        /** The places besides GitHub whose repositories are read for their releases. */
        val ELSEWHERE = setOf(SOURCE_CODEBERG, SOURCE_GITLAB)
        const val SOURCE_IZZY = "izzy"
        const val SOURCE_FDROID = "fdroid"

        // Matches the index reader that rejects ambiguous and rotated signing keys.
        private const val SIGNER_READER = 2

        /** Older index entries may name an obsolete key; wait for their files to be read again. */
        internal fun indexedSigner(json: JSONObject): String? =
            if (json.optInt("signerReader") == SIGNER_READER && !json.isNull("signer")) {
                json.getString("signer").lowercase()
            } else {
                null
            }

        /**
         * The lowest Android API level [json], an index entry of a file or a stored app, says
         * the file runs on, or null when it says none.
         */
        fun minSdkOf(json: JSONObject): Int? =
            if (json.isNull("minSdk")) null else json.optInt("minSdk").takeIf { it > 0 }

        /**
         * The codename of the preview of Android [json], an index entry of a file or a stored
         * app, names as the lowest the file runs on, or null when it names none.
         */
        fun minSdkCodenameOf(json: JSONObject): String? =
            if (json.isNull("minSdkCodename")) null else json.getString("minSdkCodename").takeIf { it.isNotBlank() }

        fun fromJson(json: JSONObject) = StoreApp(
            fullName = json.getString("fullName"),
            description = json.optString("description"),
            stars = json.optInt("stars"),
            category = json.optString("category", Categories.OTHER),
            license = json.optString("license"),
            downloads = json.optLong("downloads"),
            repoUrl = json.getString("repoUrl"),
            prerelease = json.optBoolean("prerelease"),
            auto = json.optBoolean("auto"),
            tag = json.getString("tag"),
            releaseName = json.optString("releaseName"),
            releaseNotes = json.optString("releaseNotes"),
            releaseUrl = json.optString("releaseUrl"),
            publishedAt = json.optString("publishedAt"),
            apkName = json.getString("apkName"),
            apkUrl = json.getString("apkUrl"),
            apkSize = json.getLong("apkSize"),
            assetId = json.getLong("assetId"),
            packageName = if (json.isNull("packageName")) null else json.getString("packageName"),
            versionCode = json.optLong("versionCode"),
            versionName = if (json.isNull("versionName")) null else json.getString("versionName"),
            label = if (json.isNull("label")) null else json.getString("label"),
            allDownloads = json.optLong("allDownloads", json.optLong("downloads")),
            source = json.optString("source", SOURCE_GITHUB),
            author = if (json.isNull("author")) null else json.getString("author"),
            signer = if (json.optString("source", SOURCE_GITHUB).let { it == SOURCE_GITHUB || it in ELSEWHERE }) {
                indexedSigner(json)
            } else {
                if (json.isNull("signer")) null else json.getString("signer")
            },
            sha256 = if (json.isNull("sha256")) null else json.getString("sha256"),
            minSdk = minSdkOf(json),
            minSdkCodename = minSdkCodenameOf(json),
            antiFeatures = json.optJSONArray("antiFeatures")
                ?.let { array -> List(array.length()) { array.getString(it) } }
                .orEmpty(),
            icon = AppIcon.of(json.opt("icon")),
            metadata = AppMetadata.mapOf(json.optJSONObject("metadata"))
        )
    }
}

/**
 * What the developer publishes of the app in one language, as the index tells it: the
 * address of a [description] longer than the summary, or null when there is none, and
 * the addresses of [screenshots] taken on a phone.
 */
data class AppMetadata(val description: String?, val screenshots: List<String>) {

    fun toJson(): JSONObject = JSONObject()
        .put("description", description ?: JSONObject.NULL)
        .put("screenshots", JSONArray(screenshots))

    companion object {
        /**
         * The metadata an index entry's "metadata" field describes, by language; a language
         * with nothing to show is left out.
         */
        fun mapOf(json: JSONObject?): Map<String, AppMetadata> {
            if (json == null) return emptyMap()
            val metadata = LinkedHashMap<String, AppMetadata>()
            for (language in json.keys()) {
                val entry = json.optJSONObject(language) ?: continue
                // Asked for as what it is: Android's optString reads a null as the word "null".
                val description = (entry.opt("description") as? String)?.takeIf { it.isNotBlank() }
                val screenshots = entry.optJSONArray("screenshots")
                    ?.let { array -> List(array.length()) { array.opt(it) as? String } }
                    .orEmpty()
                    .filterNotNull()
                    .filter { it.isNotBlank() }
                if (description != null || screenshots.isNotEmpty()) {
                    metadata[language] = AppMetadata(description, screenshots)
                }
            }
            return metadata
        }

        /**
         * The metadata to show of [metadata] on a device whose [languages] are those, in
         * order of preference: the description of the first language that has one, else of
         * English, else of whatever there is, and the screenshots likewise, as a developer
         * often publishes the screenshots in one language only; null when there is none.
         */
        fun pick(metadata: Map<String, AppMetadata>, languages: List<String>): AppMetadata? {
            if (metadata.isEmpty()) return null
            val inOrder = (languages + "en" + metadata.keys).distinct().mapNotNull { metadata[it] }
            return AppMetadata(
                inOrder.firstNotNullOfOrNull { it.description },
                inOrder.firstOrNull { it.screenshots.isNotEmpty() }?.screenshots.orEmpty()
            )
        }
    }
}

/**
 * How an app's icon is published, as the index tells it. [address] is the address of the
 * icon: an image, or a vector drawable described as JSON when it ends in ".json". An icon
 * drawn in layers, an adaptive icon, has no address of its own but a [foreground] and a
 * [background], each an address like the above, the background possibly a colour as
 * "#aarrggbb". A layer is 108 units across, of which the middle 72 show.
 */
data class AppIcon(val address: String?, val foreground: String?, val background: String?) {

    /** The icon the way an index entry writes it: the address, or the layers. */
    fun toJson(): Any = address
        ?: JSONObject().put("foreground", foreground).put("background", background ?: JSONObject.NULL)

    companion object {
        /** The icon an index entry's "icon" field describes, or null when it describes none. */
        fun of(value: Any?): AppIcon? = when (value) {
            is String -> value.takeIf { it.isNotBlank() }?.let { AppIcon(it, null, null) }
            is JSONObject -> value.optString("foreground").takeIf { it.isNotBlank() }?.let { foreground ->
                AppIcon(null, foreground, value.optString("background").takeIf { it.isNotBlank() })
            }
            else -> null
        }
    }
}

data class Catalog(
    val apps: List<StoreApp>,
    val checkedAt: Long,
    /** How many times the store's own APKs have been downloaded; null when unknown. */
    val storeDownloads: Long? = null,
    /**
     * For each repository with a prerelease that upgrades its full release, the version code
     * of that prerelease. It tells an installed beta apart from any other newer version.
     */
    val betaVersions: Map<String, Long> = emptyMap()
) {
    companion object {
        val EMPTY = Catalog(emptyList(), 0)
    }
}
