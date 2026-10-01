package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceStoreTest {
    @Test
    fun parsesAccountsRepositoriesAndLinks() {
        assertEquals("jrs8205", SourceStore.parse(" jrs8205 "))
        assertEquals("jrs8205/ARK-launcher", SourceStore.parse("jrs8205/ARK-launcher"))
        assertEquals("jrs8205", SourceStore.parse("https://github.com/jrs8205/"))
        assertEquals(
            "jrs8205/ARK-launcher",
            SourceStore.parse("https://github.com/jrs8205/ARK-launcher/releases/tag/v0.8.2")
        )
        assertEquals("owner/repo", SourceStore.parse("github.com/owner/repo.git"))
    }

    @Test
    fun renamedRepositoryTakesItsSourcesPlace() {
        val sources = listOf("jrs8205", "old/app", "other/app")
        assertEquals(
            listOf("jrs8205", "new/app", "other/app"),
            SourceStore.renamed(sources, "old/app", "new/app")
        )
    }

    @Test
    fun renamedRepositoryAlreadyAmongTheSourcesIsNotListedTwice() {
        val sources = listOf("old/app", "New/App")
        assertEquals(listOf("New/App"), SourceStore.renamed(sources, "old/app", "new/app"))
    }

    @Test
    fun rejectsAnythingElse() {
        assertNull(SourceStore.parse(""))
        assertNull(SourceStore.parse("two words"))
        assertNull(SourceStore.parse("-leading-dash"))
        assertNull(SourceStore.parse("https://example.com/"))
    }
}
