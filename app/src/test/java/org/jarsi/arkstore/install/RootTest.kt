package org.jarsi.arkstore.install

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTest {

    @Test
    fun theOutputOfIdTellsRootFromAnyoneElse() {
        assertTrue(Root.isRoot("uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0\n"))
        assertTrue(Root.isRoot("  uid=0(root) gid=0(root)"))
        assertFalse(Root.isRoot("uid=2000(shell) gid=2000(shell)"))
        assertFalse(Root.isRoot("uid=10234(u0_a234) gid=10234(u0_a234)"))
        assertFalse(Root.isRoot(""))
        assertFalse(Root.isRoot("Permission denied"))
        // Only the user id counts, not a group named 0 further along.
        assertFalse(Root.isRoot("uid=2000(shell) gid=0(root)"))
    }

    @Test
    fun theVersionOfSuNamesItsProvider() {
        assertEquals("Magisk 27.0", Root.providerName("27.0:MAGISKSU\n"))
        assertEquals("KernelSU 1.0.3", Root.providerName("1.0.3:KernelSU"))
        assertEquals("APatch 10927", Root.providerName("10927:APatch"))
        assertEquals("16 su", Root.providerName("16 su"))
        assertEquals("", Root.providerName(""))
        // A version that is too long to be one is cut, so that the settings cannot be flooded.
        assertEquals(40, Root.providerName("x".repeat(500)).length)
    }

    /** A process that has printed [output] and is done. */
    private class Printed(output: ByteArray) : Process() {
        private val input = ByteArrayInputStream(output)
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
    }

    @Test
    fun anOutputLongerThanIsKeptIsToldToBeCut() = runBlocking {
        val long = ByteArray(Root.MAX_TEXT + 10) { 'a'.code.toByte() }
        val ran = Root.converse(Printed(long), "x", null, null, null) as Root.Outcome.Ran
        assertTrue(ran.cut)
        assertEquals(Root.MAX_TEXT, ran.out.length)
        val short = Root.converse(Printed("hello\n".toByteArray()), "x", null, null, null) as Root.Outcome.Ran
        assertFalse(short.cut)
        assertEquals("hello\n", short.out)
        assertEquals(0, short.exit)
    }

    @Test
    fun aCancelWaitsForTheReaderOfTheOutputToReturn() = runBlocking {
        // A process that prints a byte and then runs until it is killed.
        val pipe = PipedOutputStream()
        val dead = CountDownLatch(1)
        var destroyed = false
        val process = object : Process() {
            private val input = PipedInputStream(pipe)
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = input
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int {
                dead.await()
                return 0
            }
            override fun exitValue(): Int = 0
            override fun destroy() {
                destroyed = true
                pipe.close()
                dead.countDown()
            }
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            Root.converse(process, "x", null, null) { input ->
                input.read()
                entered.countDown()
                // Writing into a slow stream: the cancel must not return before this has.
                try {
                    release.await(10, TimeUnit.SECONDS)
                } finally {
                    exited.countDown()
                }
            }
        }
        pipe.write(1)
        pipe.flush()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancel()
        delay(300)
        assertTrue(destroyed)
        assertFalse("The cancel returned while the reader was still at work", job.isCompleted)
        assertEquals(1L, exited.count)
        release.countDown()
        job.join()
        assertEquals(0L, exited.count)
    }
}
