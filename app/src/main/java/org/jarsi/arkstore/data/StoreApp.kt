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
     * same after the name of the place, such as "codeberg:owner/repo", for one elsewhere, and
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
     * The name the app gives itself, read from the APK by the store index. Null when the
     * index has not told it; the repository's name then stands in.
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
    /** What a catalogue warns about in the app, such as tracking, in its own words. */
    val antiFeatures: List<String> = emptyList()
) {
    /** Whether the app comes from a repository's releases rather than from a catalogue. */
    val fromRepository: Boolean
        get() = source == SOURCE_GITHUB || source == SOURCE_CODEBERG

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
        .put("sha256", sha256 ?: JSONObject.NULL)
        .put("antiFeatures", JSONArray(antiFeatures))

    companion object {
        const val SOURCE_GITHUB = "github"
        const val SOURCE_CODEBERG = "codeberg"
        const val SOURCE_IZZY = "izzy"
        const val SOURCE_FDROID = "fdroid"

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
            signer = if (json.isNull("signer")) null else json.getString("signer"),
            sha256 = if (json.isNull("sha256")) null else json.getString("sha256"),
            antiFeatures = json.optJSONArray("antiFeatures")
                ?.let { array -> List(array.length()) { array.getString(it) } }
                .orEmpty()
        )
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
