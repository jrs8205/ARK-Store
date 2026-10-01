package org.jarsi.arkstore.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallConfirmationQueueTest {
    @Test
    fun retainsConfirmationUntilTheActivityReturns() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/app", "confirmation")

        assertEquals(InstallConfirmation("owner/app", "confirmation"), queue.next.value)
        assertEquals(queue.next.value, queue.take())
        assertNull(queue.next.value)
        assertNull(queue.take())
    }

    @Test
    fun waitsForTheOpenActivityEvenAfterItsSessionCompletes() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.take()
        queue.enqueue("owner/second", "second")
        queue.remove("owner/first")

        assertNull(queue.next.value)
        assertNull(queue.take())
        queue.finishActive()
        assertEquals(InstallConfirmation("owner/second", "second"), queue.take())
    }

    @Test
    fun duplicateCallbacksDoNotOpenASecondPrompt() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/app", "old")
        queue.enqueue("owner/app", "latest")
        assertEquals("latest", queue.take()?.value)

        queue.enqueue("owner/app", "duplicate")
        queue.finishActive()
        assertNull(queue.next.value)
        assertNull(queue.take())
    }

    @Test
    fun removesCompletedSessionsWhileWaitingInTheBackground() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.enqueue("owner/second", "second")
        queue.remove("owner/first")

        assertEquals(InstallConfirmation("owner/second", "second"), queue.take())
    }

    @Test
    fun closingOrFailingOnePromptAllowsTheNextPrompt() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.enqueue("owner/second", "second")
        queue.take()

        queue.finishActive()
        assertEquals("owner/second", queue.next.value?.repo)
        queue.take()
        queue.finishActive()
        assertNull(queue.next.value)
    }
}
