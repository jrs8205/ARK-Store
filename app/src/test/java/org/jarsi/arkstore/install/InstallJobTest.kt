package org.jarsi.arkstore.install

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallJobTest {

    @Test
    fun aCancelBeforeTheBodyRunsStillSettlesTheInstall() = runBlocking {
        val settled = CompletableDeferred<Boolean>()
        var ran = false
        val install = InstallJob(launch(Dispatchers.IO, start = CoroutineStart.LAZY) { ran = true })
        install.onSettled { cancelled -> settled.complete(cancelled) }
        assertTrue(install.cancel())
        install.start()
        assertTrue(withTimeout(2000) { settled.await() })
        assertFalse(ran)
    }

    @Test
    fun aCancelAfterTheHandOverIsRefusedAndTheJobGoesOn() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val settled = CompletableDeferred<Boolean>()
        val install = InstallJob(launch(Dispatchers.IO, start = CoroutineStart.LAZY) { gate.await() })
        install.onSettled { cancelled -> settled.complete(cancelled) }
        install.start()
        install.handOver()
        assertFalse(install.cancel())
        assertTrue(install.job.isActive)
        gate.complete(Unit)
        assertFalse(withTimeout(2000) { settled.await() })
    }

    @Test
    fun aHandOverAfterACancelIsRefused() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val install = InstallJob(launch(Dispatchers.IO, start = CoroutineStart.LAZY) { gate.await() })
        install.start()
        assertTrue(install.cancel())
        val refused = try {
            install.handOver()
            false
        } catch (e: CancellationException) {
            true
        }
        assertTrue(refused)
        gate.complete(Unit)
        Unit
    }

    @Test
    fun aCancelAfterTheAttemptHasEndedIsRefused() = runBlocking {
        // The attempt failed on its own: nothing is left to cancel, and the settling is no
        // cancellation even when a cancel lands before the body returns.
        val gate = CompletableDeferred<Unit>()
        val settled = CompletableDeferred<Boolean>()
        val install = InstallJob(launch(Dispatchers.IO, start = CoroutineStart.LAZY) { gate.await() })
        install.onSettled { cancelled -> settled.complete(cancelled) }
        install.start()
        install.end()
        assertFalse(install.cancel())
        assertTrue(install.job.isActive)
        gate.complete(Unit)
        assertFalse(withTimeout(2000) { settled.await() })
    }

    @Test
    fun anAttemptCancelledIsNotEndedByAFailureNoticedLater() = runBlocking {
        // A download the cancel did not interrupt fails afterwards; the attempt is still the
        // cancelled one, and its settling says so.
        val gate = CompletableDeferred<Unit>()
        val settled = CompletableDeferred<Boolean>()
        val install = InstallJob(launch(Dispatchers.IO, start = CoroutineStart.LAZY) { gate.await() })
        install.onSettled { cancelled -> settled.complete(cancelled) }
        install.start()
        assertTrue(install.cancel())
        assertFalse(install.end())
        assertTrue(withTimeout(2000) { settled.await() })
    }

    @Test
    fun theSettlingIsToldOnceWithWhetherItWasCancelled() = runBlocking {
        val told = ArrayList<Boolean>()
        val install = InstallJob(launch(Dispatchers.IO, start = CoroutineStart.LAZY) { })
        install.onSettled { told += it }
        install.start()
        install.job.join()
        assertEquals(listOf(false), told)
    }
}
