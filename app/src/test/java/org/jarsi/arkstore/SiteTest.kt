package org.jarsi.arkstore

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The first button of the site's front page gives the APK itself, so that a phone downloads it
 * at once. A release names its file after its version, so the address changes with each one.
 */
class SiteTest {
    @Test
    fun theDownloadButtonGivesTheApkOfThisVersion() {
        val page = File("../docs/index.html").readText()
        val button = Regex("""<a class="button main" href="([^"]+)">Download for Android</a>""").find(page)
        val version = BuildConfig.VERSION_NAME
        assertEquals(
            "https://github.com/${BuildConfig.STORE_REPO}/releases/download/v$version/ARK-Store-$version.apk",
            button?.groupValues?.get(1)
        )
    }
}
