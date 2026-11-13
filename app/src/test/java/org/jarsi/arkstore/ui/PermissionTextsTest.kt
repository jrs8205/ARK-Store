package org.jarsi.arkstore.ui

import java.util.Locale
import org.jarsi.arkstore.data.InstalledVersion
import org.jarsi.arkstore.data.testApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionTextsTest {

    private fun described(name: String, label: String? = null, sensitive: Boolean = false) =
        DescribedPermission(name, label, sensitive)

    @Test
    fun aPermissionIsShownByWhatTheDeviceCallsItBegunWithACapital() {
        val camera = described("android.permission.CAMERA", "take pictures and videos", true)
        assertEquals("Take pictures and videos", PermissionTexts.line(camera, Locale.ENGLISH))
        // As the language writes its capitals.
        assertEquals("İnternet", PermissionTexts.line(described("x.Y", "internet"), Locale.forLanguageTag("tr")))
        assertEquals("Ääni", PermissionTexts.line(described("x.Y", "ääni"), Locale.forLanguageTag("fi")))
    }

    @Test
    fun aPermissionTheDeviceHasNoNameForIsShownByItsOwn() {
        assertEquals("com.example.permission.OWN", PermissionTexts.line(described("com.example.permission.OWN"), Locale.ENGLISH))
    }

    @Test
    fun aLabelThatSaysNothingIsNone() {
        assertEquals("take pictures", PermissionTexts.labelOf("android.permission.CAMERA", " take pictures "))
        // A permission without a label of its own is given its name for one.
        assertNull(PermissionTexts.labelOf("android.permission.CAMERA", "android.permission.CAMERA"))
        assertNull(PermissionTexts.labelOf("android.permission.CAMERA", ""))
        assertNull(PermissionTexts.labelOf("android.permission.CAMERA", "   "))
        assertNull(PermissionTexts.labelOf("android.permission.CAMERA", null))
    }

    @Test
    fun thePermissionsTheUserIsAskedAboutComeFirstAndTheOrderIsKeptOtherwise() {
        val internet = described("android.permission.INTERNET", "have full network access")
        val camera = described("android.permission.CAMERA", "take pictures and videos", true)
        val wake = described("android.permission.WAKE_LOCK", "prevent phone from sleeping")
        val audio = described("android.permission.RECORD_AUDIO", "record audio", true)
        assertEquals(listOf(camera, audio, internet, wake), PermissionTexts.ordered(listOf(internet, camera, wake, audio)))
    }

    @Test
    fun whatAnUpdateAddsIsToldAgainstTheVersionInstalledNow() {
        val offered = testApp(versionCode = 3)
        val one = InstalledVersion(versionCode = 1, versionName = "1.0", updatedAt = 1_000)
        val two = InstalledVersion(versionCode = 2, versionName = "2.0", updatedAt = 2_000)
        // Another version installed meanwhile is another comparison, to be made anew.
        assertNotEquals(installedCompared(offered, one), installedCompared(offered, two))
        assertEquals(installedCompared(offered, one), installedCompared(offered, one.copy(label = "Renamed")))
        // The same version installed again is one too: its permissions are read again.
        assertNotEquals(installedCompared(offered, one), installedCompared(offered, one.copy(updatedAt = 1_500)))
        // Nothing to compare with: no app installed, another app under the name, or a version not older.
        assertNull(installedCompared(offered, null))
        assertNull(installedCompared(offered, one.copy(otherApp = true)))
        assertNull(installedCompared(offered, one.copy(versionCode = 3)))
        assertNull(installedCompared(offered, one.copy(versionCode = 4)))
    }

    @Test
    fun twoPermissionsTheDeviceCallsTheSameAreShownOnce() {
        val fine = described("android.permission.ACCESS_FINE_LOCATION", "access precise location", true)
        val media = described("android.permission.ACCESS_MEDIA_LOCATION", "access precise location", true)
        val own = described("com.example.A")
        val other = described("com.example.B")
        assertEquals(
            listOf("Access precise location", "com.example.A", "com.example.B"),
            PermissionTexts.lines(listOf(fine, media, own, other), Locale.ENGLISH)
        )
    }
}
