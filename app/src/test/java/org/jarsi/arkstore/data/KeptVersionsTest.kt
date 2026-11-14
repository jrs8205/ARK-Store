package org.jarsi.arkstore.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.jarsi.arkstore.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptVersionsTest {

    private class PrefsContext : ContextWrapper(null) {
        val prefs = HashMap<String, FakePreferences>()
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            prefs.getOrPut(name!!) { FakePreferences() }
    }

    private val context: Context = PrefsContext()

    @Test
    fun aVersionGoneBackToIsKeptWhileItIsTheOneInstalled() {
        assertEquals(emptyMap<String, Long>(), KeptVersions.read(context))
        KeptVersions.keep(context, "org.alice", 12)
        val kept = KeptVersions.read(context)
        assertTrue(KeptVersions.keeps(kept, "org.alice", 12))
        // Another version installed since, by whatever means: the choice is spent.
        assertFalse(KeptVersions.keeps(kept, "org.alice", 13))
        assertFalse(KeptVersions.keeps(kept, "org.bob", 12))
        assertFalse(KeptVersions.keeps(kept, null, 12))
    }

    @Test
    fun goingBackAgainReplacesTheVersion() {
        KeptVersions.keep(context, "org.alice", 12)
        KeptVersions.keep(context, "org.bob", 3)
        KeptVersions.keep(context, "org.alice", 9)
        assertEquals(mapOf("org.alice" to 9L, "org.bob" to 3L), KeptVersions.read(context))
    }
}
