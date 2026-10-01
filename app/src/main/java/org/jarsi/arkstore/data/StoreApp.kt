package org.jarsi.arkstore.data

import org.json.JSONObject

/** One repository's latest release that carries an installable APK. */
data class StoreApp(
    /** "owner/repo"; identifies the app throughout the store. */
    val fullName: String,
    val description: String,
    val stars: Int,
    /** One of [Categories.ALL]. */
    val category: String,
    /** SPDX identifier of the repository's licence, e.g. "GPL-3.0". */
    val license: String,
    /** Downloads of APK files summed over the repository's recent releases. */
    val downloads: Long,
    val repoUrl: String,
    /** True when this is a prerelease, offered because beta versions are wanted. */
    val prerelease: Boolean = false,
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
    val versionName: String?
) {
    val owner: String
        get() = fullName.substringBefore('/')

    val repo: String
        get() = fullName.substringAfter('/')

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

    companion object {
        fun fromJson(json: JSONObject) = StoreApp(
            fullName = json.getString("fullName"),
            description = json.optString("description"),
            stars = json.optInt("stars"),
            category = json.optString("category", Categories.OTHER),
            license = json.optString("license"),
            downloads = json.optLong("downloads"),
            repoUrl = json.getString("repoUrl"),
            prerelease = json.optBoolean("prerelease"),
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
            versionName = if (json.isNull("versionName")) null else json.getString("versionName")
        )
    }
}

data class Catalog(
    val apps: List<StoreApp>,
    val checkedAt: Long,
    /** How many times the store's own APKs have been downloaded; null when unknown. */
    val storeDownloads: Long? = null
) {
    companion object {
        val EMPTY = Catalog(emptyList(), 0)
    }
}
