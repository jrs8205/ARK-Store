package org.jarsi.arkstore.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun aPromptLeftUnansweredIsParkedOnlyWhileItIsStillTheActiveOne() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.enqueue("owner/second", "second")
        queue.take()
        queue.park("owner/second") { it == "second" }
        assertNull(queue.next.value)
        assertEquals(emptySet<String>(), queue.unanswered.value)
        // The prompt of an earlier attempt of the same app is not the one open now.
        queue.park("owner/first") { it == "earlier" }
        assertNull(queue.next.value)

        queue.park("owner/first") { it == "first" }
        assertEquals(setOf("owner/first"), queue.unanswered.value)
        assertEquals(InstallConfirmation("owner/second", "second"), queue.take())
        // A late parking of the first must not touch the second, which is active now.
        queue.park("owner/first") { it == "first" }
        assertNull(queue.next.value)
        queue.enqueue("owner/third", "third")
        assertNull(queue.take())
    }

    @Test
    fun aParkedPromptIsPresentedAgainOnlyWhenAskedFor() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.enqueue("owner/second", "second")
        queue.take()
        queue.park("owner/first") { it == "first" }

        // The others go on; the parked one does not come back by itself.
        assertEquals("owner/second", queue.take()?.repo)
        queue.remove("owner/second")
        assertNull(queue.next.value)
        assertEquals(listOf(InstallConfirmation("owner/first", "first")), queue.parked())
        assertTrue(queue.holds("owner/first"))

        queue.enqueue("owner/third", "third")
        assertTrue(queue.resume("owner/first"))
        // Asked for, it comes before those still waiting.
        assertEquals(InstallConfirmation("owner/first", "first"), queue.next.value)
        assertEquals(InstallConfirmation("owner/first", "first"), queue.take())
        assertEquals(emptySet<String>(), queue.unanswered.value)
        assertFalse(queue.resume("owner/first"))
    }

    @Test
    fun aParkedPromptGoesWhenItsSessionSettlesOrANewRequestComes() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.take()
        queue.park("owner/first") { it == "first" }
        queue.remove("owner/first")
        assertFalse(queue.holds("owner/first"))
        assertEquals(emptySet<String>(), queue.unanswered.value)

        queue.enqueue("owner/second", "second")
        queue.take()
        queue.park("owner/second") { it == "second" }
        // A new request for the same app, as from a new attempt, is the one that counts.
        queue.enqueue("owner/second", "again")
        assertEquals(emptyList<InstallConfirmation<String>>(), queue.parked())
        assertEquals(InstallConfirmation("owner/second", "again"), queue.take())
    }

    @Test
    fun aPromptPresentedElsewhereBecomesTheActiveOne() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.enqueue("owner/second", "second")

        // The notification opened the second: the screen must not open it too.
        assertTrue(queue.hold("owner/second"))
        assertNull(queue.next.value)
        assertNull(queue.take())
        assertFalse(queue.hold("owner/first"))
        assertTrue(queue.hold("owner/second"))

        queue.park("owner/second") { it == "second" }
        assertEquals(setOf("owner/second"), queue.unanswered.value)
        assertEquals(InstallConfirmation("owner/first", "first"), queue.take())
        queue.remove("owner/first")
        // A parked one can be presented elsewhere too.
        assertTrue(queue.hold("owner/second"))
        assertEquals(emptySet<String>(), queue.unanswered.value)
        assertFalse(queue.hold("owner/none"))
    }

    @Test
    fun tellsWhichConfirmationIsBeingPresented() {
        val queue = InstallConfirmationQueue<String>()
        assertNull(queue.active())
        queue.enqueue("owner/first", "first")
        assertNull(queue.active())
        queue.take()
        assertEquals(InstallConfirmation("owner/first", "first"), queue.active())
        queue.park("owner/first") { it == "first" }
        assertNull(queue.active())
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
