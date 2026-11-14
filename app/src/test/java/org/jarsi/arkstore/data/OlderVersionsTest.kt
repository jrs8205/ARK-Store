package org.jarsi.arkstore.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OlderVersionsTest {

    private val abis = listOf("arm64-v8a", "armeabi-v7a")

    private fun asset(id: Long, name: String, size: Long = 1000) = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("size", size)
        .put("browser_download_url", "https://example.invalid/$id/$name")

    private fun release(
        tag: String,
        published: String,
        vararg assets: JSONObject,
        prerelease: Boolean = false,
        draft: Boolean = false
    ) = JSONObject()
        .put("tag_name", tag)
        .put("name", "Release $tag")
        .put("body", "Notes of $tag")
        .put("html_url", "https://example.invalid/releases/$tag")
        .put("published_at", published)
        .put("prerelease", prerelease)
        .put("draft", draft)
        .put("assets", JSONArray(assets.toList()))

    private fun current(tag: String, published: String = "", source: String = StoreApp.SOURCE_GITHUB) =
        testApp("alice/app", versionCode = 50).copy(tag = tag, publishedAt = published, source = source)

    private fun five() = JSONArray(
        listOf(
            release("v1.5", "2026-05-01T10:00:00Z", asset(5, "app-1.5.apk")),
            release("v1.4", "2026-04-01T10:00:00Z", asset(4, "app-1.4.apk")),
            release("v1.3", "2026-03-01T10:00:00Z", asset(3, "app-1.3.apk")),
            release("v1.2", "2026-02-01T10:00:00Z", asset(2, "app-1.2.apk")),
            release("v1.1", "2026-01-01T10:00:00Z", asset(1, "app-1.1.apk"))
        )
    )

    @Test
    fun theThreeReleasesBeforeTheOneOfferedAreGiven() {
        val older = OlderVersions.ofReleases(five(), current("v1.5"), abis, withPrereleases = false)
        assertEquals(listOf("v1.4", "v1.3", "v1.2"), older.map { it.tag })
        val first = older.first()
        assertEquals("1.4", first.displayVersion)
        assertEquals("app-1.4.apk", first.apkName)
        assertEquals("https://example.invalid/4/app-1.4.apk", first.apkUrl)
        assertEquals(1000L, first.apkSize)
        assertEquals(4L, first.assetId)
        assertEquals("Notes of v1.4", first.releaseNotes)
        assertEquals("2026-04-01T10:00:00Z", first.publishedAt)
    }

    @Test
    fun aReleaseNewerThanTheOneOfferedIsNotAnOlderOne() {
        // The version offered is not the first of the list: a prerelease lies above it.
        val older = OlderVersions.ofReleases(five(), current("v1.3"), abis, withPrereleases = false)
        assertEquals(listOf("v1.2", "v1.1"), older.map { it.tag })
    }

    @Test
    fun draftsAndReleasesWithoutAFileForTheDeviceAreLeftOut() {
        val releases = JSONArray(
            listOf(
                release("v2.0", "2026-06-01T10:00:00Z", asset(20, "app.apk")),
                release("v1.9", "2026-05-20T10:00:00Z", asset(19, "app.apk"), draft = true),
                release("v1.8", "2026-05-10T10:00:00Z", asset(18, "app-x86_64.apk")),
                release("v1.7", "2026-05-01T10:00:00Z", asset(17, "source.zip")),
                release("v1.6", "2026-04-20T10:00:00Z"),
                release("v1.5", "2026-04-10T10:00:00Z", asset(15, "app-x86_64.apk"), asset(16, "app-arm64-v8a.apk"))
            )
        )
        val older = OlderVersions.ofReleases(releases, current("v2.0"), abis, withPrereleases = false)
        assertEquals(listOf("v1.5"), older.map { it.tag })
        assertEquals("app-arm64-v8a.apk", older.single().apkName)
    }

    @Test
    fun prereleasesAreGivenOnlyWhenTheyAreWanted() {
        val releases = JSONArray(
            listOf(
                release("v2.0", "2026-06-01T10:00:00Z", asset(20, "app.apk")),
                release("v2.0-beta", "2026-05-20T10:00:00Z", asset(19, "app.apk"), prerelease = true),
                release("v1.9", "2026-05-10T10:00:00Z", asset(18, "app.apk"))
            )
        )
        val app = current("v2.0")
        assertEquals(listOf("v1.9"), OlderVersions.ofReleases(releases, app, abis, withPrereleases = false).map { it.tag })
        val withBetas = OlderVersions.ofReleases(releases, app, abis, withPrereleases = true)
        assertEquals(listOf("v2.0-beta", "v1.9"), withBetas.map { it.tag })
        assertTrue(withBetas.first().prerelease)
        assertFalse(withBetas.last().prerelease)
    }

    @Test
    fun whenTheReleaseOfferedIsNotInTheListTheOnesPublishedBeforeItAreGiven() {
        // The time of another zone is read as the moment it is, not as its letters.
        val releases = JSONArray(
            listOf(
                release("v1.5", "2026-05-01T12:30:00+02:00", asset(5, "app.apk")),
                release("v1.4", "2026-05-01T11:30:00+02:00", asset(4, "app.apk")),
                release("v1.3", "", asset(3, "app.apk"))
            )
        )
        val older = OlderVersions.ofReleases(releases, current("1.5-renamed", "2026-05-01T10:00:00Z"), abis, withPrereleases = false)
        assertEquals(listOf("v1.4"), older.map { it.tag })
        // Without a time of its own, nothing tells which came before.
        assertEquals(emptyList<OlderVersion>(), OlderVersions.ofReleases(releases, current("1.5-renamed"), abis, false))
    }

    @Test
    fun aListThatIsNotOneGivesNothing() {
        val odd = JSONArray(listOf(JSONObject().put("tag_name", "v1"), "text", JSONObject()))
        assertEquals(emptyList<OlderVersion>(), OlderVersions.ofReleases(odd, current("v2"), abis, false))
    }

    private fun gitLabRelease(tag: String, released: String, vararg links: JSONObject, upcoming: Boolean = false) = JSONObject()
        .put("tag_name", tag)
        .put("name", tag)
        .put("description", "Notes of $tag")
        .put("released_at", released)
        .put("upcoming_release", upcoming)
        .put("_links", JSONObject().put("self", "https://gitlab.com/group/app/-/releases/$tag"))
        .put("assets", JSONObject().put("links", JSONArray(links.toList())))

    private fun link(id: Long, name: String, url: String) = JSONObject().put("id", id).put("name", name).put("url", url)

    @Test
    fun gitLabReleasesGiveTheFilesGitLabKeepsItself() {
        val releases = JSONArray(
            listOf(
                gitLabRelease("v3", "2026-06-01T10:00:00.000Z", link(30, "app.apk", "https://gitlab.com/group/app/-/package_files/30/download")),
                gitLabRelease("v2", "2026-05-01T10:00:00.000Z", link(20, "app.apk", "https://example.invalid/app.apk")),
                gitLabRelease(
                    "v1", "2026-04-01T10:00:00.000Z",
                    link(10, "Android", "https://gitlab.com/group/app/uploads/0123456789abcdef0123456789abcdef/app-1.apk")
                ),
                gitLabRelease("v0", "2026-03-01T10:00:00.000Z", link(1, "app.apk", "https://gitlab.com/group/app/-/package_files/1/download"), upcoming = true)
            )
        )
        val older = OlderVersions.ofGitLab(releases, current("v3", source = StoreApp.SOURCE_GITLAB), abis)
        assertEquals(listOf("v1"), older.map { it.tag })
        val file = older.single()
        // A link named otherwise is called by the file its address ends in.
        assertEquals("app-1.apk", file.apkName)
        assertEquals("https://gitlab.com/group/app/uploads/0123456789abcdef0123456789abcdef/app-1.apk", file.apkUrl)
        assertEquals(0L, file.apkSize)
        assertEquals("https://gitlab.com/group/app/-/releases/v1", file.releaseUrl)
        assertEquals("Notes of v1", file.releaseNotes)
    }

    @Test
    fun aGitLabLinkIsAFileOnlyAtAnAddressGitLabKeepsFilesAt() {
        fun apk(url: String, name: String = "app.apk") = OlderVersions.gitLabApk(link(1, name, url))
        assertEquals(
            "app.apk" to "https://gitlab.com/api/v4/projects/12/packages/generic/app/1.0/app.apk",
            apk("https://gitlab.com/api/v4/projects/12/packages/generic/app/1.0/app.apk")
        )
        assertEquals("app.apk", apk("https://gitlab.com/group/sub/app/-/raw/main/app.apk")?.first)
        assertEquals("app.apk", apk("https://gitlab.com/group/app/-/jobs/12/artifacts/raw/out/app.apk")?.first)
        assertEquals("app.apk", apk("https://gitlab.com/-/project/12/uploads/0123456789abcdef0123456789abcdef/app.apk")?.first)
        // Another site, another scheme, an address that passes the request on, a path that climbs.
        assertNull(apk("https://example.invalid/group/app/-/raw/main/app.apk"))
        assertNull(apk("http://gitlab.com/group/app/-/raw/main/app.apk"))
        assertNull(apk("https://gitlab.com/group/app/-/releases/v1/downloads/app.apk"))
        assertNull(apk("https://gitlab.com/group/app/-/raw/main/../app.apk"))
        assertNull(apk("https://gitlab.com/group/app/-/raw/main/%2e%2e/app.apk"))
        assertNull(apk("https://gitlab.com/api/v4/projects/12/releases/v1/assets/links/3"))
        // Neither the name nor the address says it is an APK.
        assertNull(apk("https://gitlab.com/group/app/-/package_files/30/download", name = "Android"))
    }

    @Test
    fun theListOfReleasesIsAskedWhereTheAppComesFrom() {
        assertEquals(
            "https://api.github.com/repos/alice/app/releases?per_page=100",
            OlderVersions.address(current("v1"))
        )
        assertEquals(
            "https://codeberg.org/api/v1/repos/alice/my%20app/releases?limit=50",
            OlderVersions.address(current("v1", source = StoreApp.SOURCE_CODEBERG).copy(fullName = "codeberg:alice/my app"))
        )
        assertEquals(
            "https://gitlab.com/api/v4/projects/group%2Fsub%2Fapp/releases?per_page=20",
            OlderVersions.address(current("v1", source = StoreApp.SOURCE_GITLAB).copy(fullName = "gitlab:group/sub/app"))
        )
        assertNull(OlderVersions.address(current("v1", source = StoreApp.SOURCE_FDROID).copy(fullName = "fdroid:org.example")))
    }

    @Test
    fun anOlderVersionIsInstalledAsTheSameAppWithNothingAssumedOfItsFile() {
        val app = current("v1.5").copy(
            versionName = "1.5", sha256 = "ab".repeat(32), signer = "cd".repeat(32), signerAssumed = true,
            minSdk = 26, permissions = listOf(".INTERNET"), label = "App"
        )
        val older = OlderVersions.ofReleases(five(), app, abis, withPrereleases = false).first()
        val asApp = OlderVersions.asApp(app, older)
        assertEquals(app.fullName, asApp.fullName)
        assertEquals(app.packageName, asApp.packageName)
        assertEquals("App", asApp.label)
        assertEquals("v1.4", asApp.tag)
        assertEquals("1.4", asApp.displayVersion)
        assertEquals(older.apkUrl, asApp.apkUrl)
        assertEquals(older.assetId, asApp.assetId)
        assertEquals(0L, asApp.versionCode)
        assertNull(asApp.sha256)
        assertNull(asApp.signer)
        assertFalse(asApp.signerAssumed)
        assertNull(asApp.minSdk)
        assertNull(asApp.permissions)
    }
}
