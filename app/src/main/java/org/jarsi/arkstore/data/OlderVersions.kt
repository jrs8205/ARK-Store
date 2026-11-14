package org.jarsi.arkstore.data

import java.net.URI
import java.net.URISyntaxException
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import org.json.JSONArray
import org.json.JSONObject

/**
 * An earlier release of an app, with the file of it that suits this device. What is inside
 * the file is not known before it is downloaded: the release's tag tells its version.
 */
data class OlderVersion(
    val tag: String,
    val releaseName: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val apkName: String,
    val apkUrl: String,
    /** 0 when the place does not tell it. */
    val apkSize: Long,
    val assetId: Long
) {
    /** The version as the user is to read it; see [StoreApp.displayVersion]. */
    val displayVersion: String
        get() = tag.removePrefix("v")
}

/**
 * The releases of a repository before the one the store offers, read from the place itself
 * when the user asks for them: the lists the store downloads carry the newest release only.
 */
object OlderVersions {
    /** How many earlier versions are offered. */
    const val MAX = 3

    private const val GITHUB_API = "https://api.github.com"
    private const val CODEBERG_API = "https://codeberg.org/api/v1"
    private const val GITLAB_HOST = "gitlab.com"
    private const val GITLAB_API = "https://$GITLAB_HOST/api/v4"

    /**
     * The addresses at which GitLab hands out a file it keeps itself; the index builder has
     * the same rule (GITLAB_FILES in tools/build_forge.py). A release link can point anywhere,
     * and a file on another site is that site's to offer.
     */
    private val GITLAB_FILES = Regex(
        "/api/v4/projects/[^/]+/packages/generic/[^/]+/[^/]+/.+" +
            "|/-/project/\\d+/uploads/[0-9a-f]{32}/[^/]+" +
            "|/(?!api/)(?:(?!-/)[^/]+/)+(?:" +
            "uploads/[0-9a-f]{32}/[^/]+" +
            "|-/raw/.+" +
            "|-/package_files/\\d+/download" +
            "|-/jobs/(?:\\d+/artifacts/raw|artifacts/[^/]+/raw)/.+" +
            ")"
    )

    private data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val url: String,
        val publishedAt: String,
        val prerelease: Boolean,
        val files: List<Asset>
    )

    private data class Asset(val id: Long, val name: String, val url: String, val size: Long)

    /**
     * Where the releases of [app]'s repository are listed, newest first, or null when [app]
     * is of a catalogue, which has no releases to list.
     */
    fun address(app: StoreApp): String? = when (app.source) {
        StoreApp.SOURCE_GITHUB ->
            "$GITHUB_API/repos/${app.repoPath.split('/').joinToString("/", transform = ::quote)}/releases?per_page=100"
        StoreApp.SOURCE_CODEBERG ->
            "$CODEBERG_API/repos/${app.repoPath.split('/').joinToString("/", transform = ::quote)}/releases?limit=50"
        // GitLab takes a project by its whole path, the slashes written out as part of the name.
        StoreApp.SOURCE_GITLAB -> "$GITLAB_API/projects/${quote(app.repoPath)}/releases?per_page=20"
        else -> null
    }

    /**
     * The versions before [current] among [releases], a list as GitHub and Codeberg give it:
     * at most [MAX], newest first, each with the file [ApkPicker] takes for [deviceAbis].
     * Drafts are left out, and prereleases too unless they are wanted ([withPrereleases]).
     */
    fun ofReleases(
        releases: JSONArray,
        current: StoreApp,
        deviceAbis: List<String>,
        withPrereleases: Boolean
    ): List<OlderVersion> = earlier(
        objects(releases).filter { !it.optBoolean("draft") }.mapNotNull { release ->
            val tag = release.text("tag_name").ifEmpty { return@mapNotNull null }
            Release(
                tag = tag,
                name = release.text("name"),
                notes = release.text("body"),
                url = release.text("html_url"),
                publishedAt = release.text("published_at"),
                prerelease = release.optBoolean("prerelease"),
                files = objects(release.optJSONArray("assets")).mapNotNull { asset ->
                    val name = asset.text("name")
                    val url = asset.text("browser_download_url")
                    if (!name.endsWith(".apk", ignoreCase = true) || url.isEmpty()) return@mapNotNull null
                    Asset(asset.optLong("id"), name, url, asset.optLong("size"))
                }
            )
        },
        current, deviceAbis, withPrereleases
    )

    /**
     * [ofReleases] of a list as GitLab gives it. GitLab has no prereleases; a release dated
     * in the future is no release yet. Its files are links, of which only those GitLab keeps
     * itself count ([gitLabApk]), and it does not tell their sizes.
     */
    fun ofGitLab(releases: JSONArray, current: StoreApp, deviceAbis: List<String>): List<OlderVersion> = earlier(
        objects(releases).filter { !it.optBoolean("upcoming_release") }.mapNotNull { release ->
            val tag = release.text("tag_name").ifEmpty { return@mapNotNull null }
            Release(
                tag = tag,
                name = release.text("name"),
                notes = release.text("description"),
                url = release.optJSONObject("_links")?.text("self").orEmpty(),
                publishedAt = release.text("released_at"),
                prerelease = false,
                files = objects(release.optJSONObject("assets")?.optJSONArray("links")).mapNotNull { link ->
                    gitLabApk(link)?.let { (name, url) -> Asset(link.optLong("id"), name, url, 0) }
                }
            )
        },
        current, deviceAbis, withPrereleases = false
    )

    /**
     * The name and the address of a release [link] of GitLab that is an APK kept on GitLab
     * itself, or null. What counts is where the link itself points, at one of the places
     * GitLab keeps files in ([GITLAB_FILES]). A link named otherwise is called by the file
     * its address ends in.
     */
    internal fun gitLabApk(link: JSONObject): Pair<String, String>? {
        val url = link.text("url")
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.scheme != "https" || uri.rawAuthority != GITLAB_HOST) return null
        // Read part by part: a slash written as %2F is part of a name, not a boundary between
        // parts. No name may be empty or stand for the directory itself or the one above.
        val parts = uri.rawPath.orEmpty().split('/').map { unquote(it) ?: return null }
        if (parts.drop(1).any { part -> part.split('/').any { it == "" || it == "." || it == ".." } }) return null
        // Written one and the same way, whatever way the link wrote it.
        if (!GITLAB_FILES.matches(parts.joinToString("/", transform = ::quote))) return null
        val name = link.text("name").takeIf { it.endsWith(".apk", ignoreCase = true) } ?: parts.last()
        return if (name.endsWith(".apk", ignoreCase = true)) name to url else null
    }

    /**
     * [current] as it is at the [older] version, for the install: the same app of the same
     * package, with the release and the file of that version. Nothing the lists tell of the
     * newest file is taken to hold of this one: not its checksum, its key, its version code,
     * the Android it needs or its permissions.
     */
    fun asApp(current: StoreApp, older: OlderVersion): StoreApp = current.copy(
        prerelease = older.prerelease,
        tag = older.tag,
        releaseName = older.releaseName,
        releaseNotes = older.releaseNotes,
        releaseUrl = older.releaseUrl,
        publishedAt = older.publishedAt,
        apkName = older.apkName,
        apkUrl = older.apkUrl,
        apkSize = older.apkSize,
        assetId = older.assetId,
        versionCode = 0,
        versionName = null,
        signer = null,
        signerAssumed = false,
        sha256 = null,
        minSdk = null,
        minSdkCodename = null,
        permissions = null
    )

    /**
     * The releases after [current]'s own in [releases], which are newest first; when its own
     * is not among them, as after a tag was renamed, the ones published before it.
     */
    private fun earlier(
        releases: List<Release>,
        current: StoreApp,
        deviceAbis: List<String>,
        withPrereleases: Boolean
    ): List<OlderVersion> {
        val at = releases.indexOfFirst { it.tag == current.tag }
        val before = if (at >= 0) {
            releases.drop(at + 1)
        } else {
            val offered = moment(current.publishedAt) ?: return emptyList()
            releases.filter { release -> moment(release.publishedAt)?.let { it < offered } == true }
        }
        return before.asSequence()
            .filter { withPrereleases || !it.prerelease }
            .mapNotNull { release ->
                val file = ApkPicker.pick(release.files.map { it.name }, deviceAbis)?.let { release.files[it] }
                    ?: return@mapNotNull null
                OlderVersion(
                    tag = release.tag,
                    releaseName = release.name,
                    releaseNotes = release.notes,
                    releaseUrl = release.url,
                    publishedAt = release.publishedAt,
                    prerelease = release.prerelease,
                    apkName = file.name,
                    apkUrl = file.url,
                    apkSize = file.size,
                    assetId = file.id
                )
            }
            .take(MAX)
            .toList()
    }

    /** The moment [text] names, whatever zone it is written in, or null when it names none. */
    private fun moment(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    /** The text at [key], or "" when there is none: optString reads a null as the word "null". */
    private fun JSONObject.text(key: String): String = (opt(key) as? String).orEmpty()

    /** [part] with everything but letters, digits and "-._~" written as %XX. */
    private fun quote(part: String): String = buildString {
        for (byte in part.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt().toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") {
                append(char)
            } else {
                append('%').append("%02X".format(byte.toInt() and 0xff))
            }
        }
    }

    /** [part] with its %XX read back, or null when one of them is not a number. */
    private fun unquote(part: String): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var at = 0
        while (at < part.length) {
            val char = part[at]
            if (char == '%') {
                val value = part.substring(at + 1, minOf(at + 3, part.length)).takeIf { it.length == 2 }?.toIntOrNull(16)
                    ?: return null
                bytes.write(value)
                at += 3
            } else {
                // Up to the next %XX at once, so that a letter written in two halves stays whole.
                val end = part.indexOf('%', at).let { if (it < 0) part.length else it }
                bytes.write(part.substring(at, end).toByteArray(Charsets.UTF_8))
                at = end
            }
        }
        return bytes.toString("UTF-8")
    }
}
