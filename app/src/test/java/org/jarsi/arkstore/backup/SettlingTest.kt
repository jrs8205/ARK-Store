package org.jarsi.arkstore.backup

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A backup or a restore says how it ended whatever became of its job; see [launchSettling]. */
class SettlingTest {

    /** Runs nothing until it is told to, so that a job can be cancelled before its first turn. */
    private class Held : CoroutineDispatcher() {
        private val waiting = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            waiting += block
        }

        fun runAll() {
            while (true) (waiting.removeFirstOrNull() ?: return).run()
        }
    }

    private val dispatcher = Held()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, _ -> })
    private val settled = ArrayList<String>()

    @Test
    fun workThatRunsToItsEndSettlesWithWhatItReturned() {
        val job = launchSettling(scope, otherwise = "cancelled", settle = { settled += it }) { "done" }
        dispatcher.runAll()
        assertTrue(job.isCompleted)
        assertEquals(listOf("done"), settled)
    }

    @Test
    fun workCancelledBeforeItsFirstTurnIsSettledAllTheSame() {
        // Started and cancelled at once: none of the body runs, and whatever the body was to
        // say at its end is never said. The state would stay at "running" for good, and
        // anything that waits for the work to be over would wait for ever.
        var entered = false
        val job = launchSettling(scope, otherwise = "cancelled", settle = { settled += it }) {
            entered = true
            "done"
        }
        job.cancel()
        dispatcher.runAll()
        assertTrue(job.isCompleted)
        assertEquals(false, entered)
        assertEquals(listOf("cancelled"), settled)
    }

    @Test
    fun workThatEndsWithoutAnAnswerIsSettledAllTheSame() {
        val job = launchSettling<String>(scope, otherwise = "cancelled", settle = { settled += it }) {
            throw IllegalStateException("gave no answer")
        }
        dispatcher.runAll()
        assertTrue(job.isCompleted)
        assertEquals(listOf("cancelled"), settled)
    }
}
