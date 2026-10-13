package org.jarsi.arkstore.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreRulesTest {

    @Test
    fun theDataGoesOnlyIntoTheAppItIsBoundTo() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val bound = DataBinding.text("org.example.app", listOf(a))
        assertEquals("org.example.app" to listOf(a), DataBinding.parse(bound))
        assertNull(DataBinding.parse("nonsense"))
        assertNull(DataBinding.parse("arkstore-data-binding/1\norg.example.app\nnot-a-digest"))
        assertTrue(RestoreRules.dataAllowed("org.example.app", bound, emptyList(), setOf(a)))
        assertTrue(RestoreRules.dataAllowed("org.example.app", bound, emptyList(), setOf(a, b)))
        assertFalse(RestoreRules.dataAllowed("org.example.app", bound, emptyList(), setOf(b)))
        assertFalse(RestoreRules.dataAllowed("org.other.app", bound, emptyList(), setOf(a)))
        assertFalse(RestoreRules.dataAllowed("org.example.app", "garbage", emptyList(), setOf(a)))
        assertFalse(RestoreRules.dataAllowed("org.example.app", bound, emptyList(), null))
        assertFalse(RestoreRules.dataAllowed("org.example.app", bound, emptyList(), emptySet()))
        // An archive with no signers bound to it names no app it belongs to.
        assertFalse(RestoreRules.dataAllowed("org.example.app", DataBinding.text("org.example.app", emptyList()), emptyList(), setOf(a)))
        // In the clear, the entry's own word is all there is.
        assertTrue(RestoreRules.dataAllowed("org.example.app", null, listOf(a), setOf(a)))
        assertFalse(RestoreRules.dataAllowed("org.example.app", null, listOf(b), setOf(a)))
        // An entry that names no signer cannot be held to anything: nothing is put back.
        assertFalse(RestoreRules.dataAllowed("org.example.app", null, emptyList(), setOf(a)))
        assertFalse(RestoreRules.dataAllowed("org.example.app", null, listOf(a), null))
    }


    private val key = "a".repeat(64)
    private val otherKey = "b".repeat(64)

    private val entry = BackupEntry(
        packageName = "org.example.app",
        label = "Example",
        versionCode = 12,
        versionName = "1.2",
        installer = null,
        signers = listOf(key),
        updatedAt = 1,
        minSdk = 26,
        files = listOf(BackupFile("base.apk", 100), BackupFile("split_x.apk", 10))
    )

    private val present = mapOf("base.apk" to 100L, "split_x.apk" to 10L, "app.json" to 5L)

    private fun status(
        installed: Long? = null,
        keysHeld: Set<String>? = setOf(key),
        present: Map<String, Long>? = this.present,
        sdk: Int = 34,
        entry: BackupEntry = this.entry
    ) = RestoreRules.status(entry, installed, keysHeld, present, sdk)

    @Test
    fun filesMissingIsNotSelectable() {
        assertEquals(RestoreStatus.FILES_MISSING, status(present = null))
        assertEquals(RestoreStatus.FILES_MISSING, status(present = mapOf("base.apk" to 100L)))
        // A file of another size than the entry says is not the file.
        assertEquals(RestoreStatus.FILES_MISSING, status(present = present + ("split_x.apk" to 11L)))
        assertFalse(RestoreStatus.FILES_MISSING.selectable)
        assertFalse(RestoreStatus.FILES_MISSING.preselected)
    }

    @Test
    fun unknownSizeCountsAsPresentButAWrongSizeDoesNot() {
        // A provider may list a file without telling its size; the file is there all the same.
        assertEquals(RestoreStatus.NOT_INSTALLED, status(present = mapOf("base.apk" to -1L, "split_x.apk" to 10L)))
        assertEquals(RestoreStatus.NOT_INSTALLED, status(present = mapOf("base.apk" to -1L, "split_x.apk" to -1L)))
        assertEquals(RestoreStatus.FILES_MISSING, status(present = mapOf("base.apk" to 99L, "split_x.apk" to 10L)))
    }

    @Test
    fun selectionFollowsTheRuleUnlessTheUserSaidOtherwise() {
        val rows = listOf("a" to true, "b" to false, "c" to true, "d" to false)
        val selectable = setOf("a", "b", "c")
        // The rule alone: what it chooses, among what can be chosen.
        assertEquals(setOf("a", "c"), Selection.chosen(rows, selectable, Selection()))
        // The user's own choices, either way, stand over the rule.
        val said = Selection(chosen = setOf("b", "d"), dropped = setOf("c"))
        assertEquals(setOf("a", "b"), Selection.chosen(rows, selectable, said))
        // A choice is remembered by what the user did last.
        assertEquals(Selection(chosen = setOf("c"), dropped = setOf("b")), Selection().toggled("c", true).toggled("b", false))
        assertEquals(Selection(chosen = emptySet(), dropped = setOf("c")), Selection().toggled("c", true).toggled("c", false))
        assertEquals(Selection(chosen = setOf("a", "c"), dropped = emptySet()), said.all(listOf("a", "c"), true))
        assertEquals(Selection(chosen = emptySet(), dropped = setOf("a", "c")), said.all(listOf("a", "c"), false))
    }

    @Test
    fun needsNewerAndroid() {
        assertEquals(RestoreStatus.NEEDS_NEWER_ANDROID, status(sdk = 25))
        assertEquals(RestoreStatus.NOT_INSTALLED, status(sdk = 26))
        assertEquals(RestoreStatus.NOT_INSTALLED, status(sdk = 25, entry = entry.copy(minSdk = null)))
        assertFalse(RestoreStatus.NEEDS_NEWER_ANDROID.selectable)
    }

    @Test
    fun notInstalledIsPreselected() {
        assertEquals(RestoreStatus.NOT_INSTALLED, status(installed = null, keysHeld = null))
        assertTrue(RestoreStatus.NOT_INSTALLED.selectable)
        assertTrue(RestoreStatus.NOT_INSTALLED.preselected)
    }

    @Test
    fun olderWithSameKeyIsPreselected() {
        assertEquals(RestoreStatus.OLDER_INSTALLED, status(installed = 11, keysHeld = setOf(key, otherKey)))
        assertTrue(RestoreStatus.OLDER_INSTALLED.selectable)
        assertTrue(RestoreStatus.OLDER_INSTALLED.preselected)
    }

    @Test
    fun olderWithUnknownKeysIsPreselected() {
        assertEquals(RestoreStatus.OLDER_INSTALLED, status(installed = 11, keysHeld = null))
        assertEquals(RestoreStatus.OLDER_INSTALLED, status(installed = 11, keysHeld = setOf(otherKey), entry = entry.copy(signers = emptyList())))
    }

    @Test
    fun otherSignerIsNotSelectable() {
        assertEquals(RestoreStatus.OTHER_SIGNER, status(installed = 11, keysHeld = setOf(otherKey)))
        assertEquals(RestoreStatus.OTHER_SIGNER, status(installed = 12, keysHeld = setOf(otherKey)))
        assertEquals(RestoreStatus.OTHER_SIGNER, status(installed = 13, keysHeld = setOf(otherKey)))
        assertFalse(RestoreStatus.OTHER_SIGNER.selectable)
    }

    @Test
    fun sameAndNewerInstalled() {
        assertEquals(RestoreStatus.SAME_INSTALLED, status(installed = 12))
        assertTrue(RestoreStatus.SAME_INSTALLED.selectable)
        assertFalse(RestoreStatus.SAME_INSTALLED.preselected)
        assertEquals(RestoreStatus.NEWER_INSTALLED, status(installed = 13))
        assertFalse(RestoreStatus.NEWER_INSTALLED.selectable)
        assertFalse(RestoreStatus.NEWER_INSTALLED.preselected)
    }

    @Test
    fun preselectionFollowsInstaller() {
        assertFalse(Installers.preselected(Installers.PLAY))
        assertTrue(Installers.preselected(null))
        assertTrue(Installers.preselected("org.fdroid.fdroid"))
        assertTrue(Installers.preselected("org.jarsi.arkstore"))
        assertTrue(Installers.preselected("com.aurora.store"))
    }

    @Test
    fun byHandCoversTheSystemInstallerAndAdb() {
        assertTrue(Installers.byHand(null))
        assertTrue(Installers.byHand("com.google.android.packageinstaller"))
        assertTrue(Installers.byHand("com.android.packageinstaller"))
        assertTrue(Installers.byHand("com.android.shell"))
        assertFalse(Installers.byHand("org.fdroid.fdroid"))
        assertFalse(Installers.byHand(Installers.PLAY))
    }

    @Test
    fun knownNames() {
        assertEquals("Google Play", Installers.knownName(Installers.PLAY))
        assertEquals("F-Droid", Installers.knownName("org.fdroid.fdroid"))
        assertEquals("F-Droid", Installers.knownName("org.fdroid.basic"))
        assertEquals("ARK-Store", Installers.knownName("org.jarsi.arkstore"))
        assertEquals("Aurora Store", Installers.knownName("com.aurora.store"))
        assertEquals("Obtainium", Installers.knownName("dev.imranr.obtainium"))
        assertEquals("Droid-ify", Installers.knownName("com.looker.droidify"))
        assertEquals("Neo Store", Installers.knownName("com.machiav3lli.fdroid"))
        assertEquals("Galaxy Store", Installers.knownName("com.sec.android.app.samsungapps"))
        // The system's own installer is the user installing by hand, which is no name.
        assertNull(Installers.knownName("com.google.android.packageinstaller"))
        assertNull(Installers.knownName("com.android.packageinstaller"))
        assertNull(Installers.knownName(null))
        assertNull(Installers.knownName("com.unknown.store"))
    }
}
