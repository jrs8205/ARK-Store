package org.jarsi.arkstore.work

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchingTest {
    @Test
    fun tellsOnceWhenTheConditionsStopHoldingWhileTheWorkRuns() = runBlocking {
        var checks = 0
        var told = 0
        val result = watching(
            allowed = { ++checks < 3 },
            intervalMillis = 5,
            onDisallowed = { told++ }
        ) {
            delay(100)
            "done"
        }
        assertEquals("done", result)
        assertEquals(1, told)
    }

    @Test
    fun saysNothingWhileTheConditionsHoldOrOnceTheWorkIsDone() = runBlocking {
        var told = 0
        var allowed = true
        val result = watching(allowed = { allowed }, intervalMillis = 5, onDisallowed = { told++ }) {
            delay(30)
            7
        }
        allowed = false
        delay(30)
        assertEquals(7, result)
        assertEquals(0, told)
    }
}
