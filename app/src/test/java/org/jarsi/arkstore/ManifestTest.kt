package org.jarsi.arkstore

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store asks for no permission that lets it install, update or see every app: Play Protect
 * blocks any new app that does. Apps with a launcher icon are seen through a query instead.
 */
class ManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun whatBelongsToThisDeviceAloneIsLeftOutOfBackupsAndTransfers() {
        // The token, and what says how things stand on this device: whether root was given,
        // and where the files saved for the user are. On another device neither holds.
        for (rules in listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml")) {
            val text = File(rules).readText()
            for (file in listOf("github.xml", "root.xml", "saved_apks.xml")) {
                val excluded = Regex("""<exclude domain="sharedpref" path="${Regex.escape(file)}" />""").findAll(text).count()
                assertTrue("$file in $rules", excluded == if (rules.endsWith("data_extraction_rules.xml")) 2 else 1)
            }
        }
    }

    @Test
    fun installPermissionsAreGoneAndLauncherQueryIsThere() {
        for (permission in listOf(
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION",
            "android.permission.QUERY_ALL_PACKAGES"
        )) {
            assertFalse(permission, permission in manifest)
        }
        assertTrue(
            Regex("""(?s)<queries>.*android\.intent\.action\.MAIN.*android\.intent\.category\.LAUNCHER.*</queries>""")
                .containsMatchIn(manifest)
        )
    }
}
