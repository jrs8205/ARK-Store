package org.jarsi.arkstore.ui

import org.jarsi.arkstore.ui.CachedFiles.Kept
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageLoaderTest {

    @Test
    fun screenshotsAreDecodedAtHalfOfAPhoneScreen() {
        assertEquals(2, ScreenshotLoader.sampleSize(1080, 2400))
        assertEquals(1, ScreenshotLoader.sampleSize(540, 1200))
        assertEquals(4, ScreenshotLoader.sampleSize(2160, 4800))
    }

    @Test
    fun iconsAreStillDecodedNoLargerThanARowNeeds() {
        assertEquals(2, IconLoader.sampleSize(384, 384))
        assertEquals(1, ScreenshotLoader.sampleSize(384, 384))
    }

    @Test
    fun theOldestCopiesGoWhenTheFolderOutgrowsItsBudget() {
        val kept = listOf(Kept("b", 40, modified = 2), Kept("a", 40, modified = 1), Kept("c", 40, modified = 3))
        // Trimmed to three quarters of the budget, so that the next fetch does not trim again.
        assertEquals(listOf("a", "b"), CachedFiles.surplus(kept, budget = 100))
        assertEquals(emptyList<String>(), CachedFiles.surplus(kept, budget = 120))
        assertEquals(listOf("a"), CachedFiles.surplus(kept.take(2), budget = 70))
    }
}
