package org.jarsi.arkstore.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallConfirmationQueueTest {
    @Test
    fun retainsConfirmationUntilItsSessionSettles() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/app", "confirmation")

        assertEquals(InstallConfirmation("owner/app", "confirmation"), queue.next.value)
        assertEquals(queue.next.value, queue.take())
        assertNull(queue.next.value)
        assertNull(queue.take())
    }

    @Test
    fun listsOnlyTheConfirmationsNobodyHasPresented() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.enqueue("owner/second", "second")
        queue.enqueue("owner/third", "third")
        queue.take()
        queue.remove("owner/third")

        assertEquals(listOf(InstallConfirmation("owner/second", "second")), queue.waiting())
    }

    @Test
    fun theNextPromptWaitsForTheSessionOfTheOpenOneToSettle() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.take()
        queue.enqueue("owner/second", "second")

        // The prompt closing says nothing: the system may still be installing the first.
        assertNull(queue.next.value)
        assertNull(queue.take())
        queue.remove("owner/first")
        assertEquals(InstallConfirmation("owner/second", "second"), queue.take())
    }

    @Test
    fun anAbandonedPromptIsReleasedOnlyWhileItIsStillTheActiveOne() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.enqueue("owner/second", "second")
        queue.take()
        queue.abandon("owner/second") { it == "second" }
        assertNull(queue.next.value)
        // The prompt of an earlier attempt of the same app is not the one open now.
        queue.abandon("owner/first") { it == "earlier" }
        assertNull(queue.next.value)

        queue.abandon("owner/first") { it == "first" }
        assertEquals(InstallConfirmation("owner/second", "second"), queue.take())
        // A late release of the first must not drop the second, which is active now.
        queue.abandon("owner/first") { it == "first" }
        assertNull(queue.next.value)
        queue.enqueue("owner/third", "third")
        assertNull(queue.take())
    }

    @Test
    fun anAbandonedPromptIsAskedAgainAfterTheOthers() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.enqueue("owner/second", "second")
        queue.take()
        queue.abandon("owner/first") { it == "first" }

        assertEquals("owner/second", queue.take()?.repo)
        queue.remove("owner/second")
        assertEquals(InstallConfirmation("owner/first", "first"), queue.take())
        // Its session settling in the meantime takes it out for good.
        queue.abandon("owner/first") { it == "first" }
        queue.remove("owner/first")
        assertNull(queue.take())
    }

    @Test
    fun duplicateCallbacksDoNotOpenASecondPrompt() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/app", "old")
        queue.enqueue("owner/app", "latest")
        assertEquals("latest", queue.take()?.value)

        queue.enqueue("owner/app", "duplicate")
        queue.remove("owner/app")
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
    fun eachSettledSessionLetsTheNextPromptThrough() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.enqueue("owner/second", "second")
        queue.take()

        queue.remove("owner/first")
        assertEquals("owner/second", queue.next.value?.repo)
        queue.take()
        queue.remove("owner/second")
        assertNull(queue.next.value)
    }
}
