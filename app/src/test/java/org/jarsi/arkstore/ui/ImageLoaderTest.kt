package org.jarsi.arkstore.ui

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
}
