package org.jarsi.arkstore.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
        val before = queue.presentation()
        queue.take()
        val first = queue.presentation()
        assertNotEquals(before, first)
        queue.park("owner/second", first)
        assertNull(queue.next.value)
        assertEquals(emptySet<String>(), queue.unanswered.value)
        // The prompt of an earlier presentation of the same app is not the one open now.
        queue.park("owner/first", before)
        assertNull(queue.next.value)

        queue.park("owner/first", first)
        assertEquals(setOf("owner/first"), queue.unanswered.value)
        assertEquals(InstallConfirmation("owner/second", "second"), queue.take())
        // A late parking of the first must not touch the second, which is active now.
        queue.park("owner/first", first)
        assertNull(queue.next.value)
        queue.enqueue("owner/third", "third")
        assertNull(queue.take())
    }

    @Test
    fun aLateParkingFromAnEarlierPresentationLeavesTheReopenedPromptAlone() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.take()
        val earlier = queue.presentation()
        queue.park("owner/first", earlier)
        assertTrue(queue.resume("owner/first"))
        assertEquals(InstallConfirmation("owner/first", "first"), queue.take())
        assertNotEquals(earlier, queue.presentation())

        // A second timer of the earlier presentation goes off while the prompt is open again.
        queue.park("owner/first", earlier)
        assertEquals(InstallConfirmation("owner/first", "first"), queue.active())
        assertEquals(emptySet<String>(), queue.unanswered.value)
        queue.park("owner/first", queue.presentation())
        assertNull(queue.active())
        assertEquals(setOf("owner/first"), queue.unanswered.value)
    }

    @Test
    fun aParkedPromptIsPresentedAgainOnlyWhenAskedFor() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.enqueue("owner/second", "second")
        queue.take()
        queue.park("owner/first", queue.presentation())

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
        queue.park("owner/first", queue.presentation())
        queue.remove("owner/first")
        assertFalse(queue.holds("owner/first"))
        assertEquals(emptySet<String>(), queue.unanswered.value)

        queue.enqueue("owner/second", "second")
        queue.take()
        queue.park("owner/second", queue.presentation())
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
        val held = queue.hold("owner/second")
        assertNotNull(held)
        assertEquals(held, queue.presentation())
        assertNull(queue.next.value)
        assertNull(queue.take())
        // Opened from the notification once more while still open: the same presentation.
        assertEquals(held, queue.hold("owner/second"))

        queue.park("owner/second", held!!)
        assertEquals(setOf("owner/second"), queue.unanswered.value)
        assertEquals(InstallConfirmation("owner/first", "first"), queue.take())
        queue.remove("owner/first")
        // A parked one can be presented elsewhere too, as a presentation of its own.
        val again = queue.hold("owner/second")
        assertNotNull(again)
        assertNotEquals(held, again)
        assertEquals(emptySet<String>(), queue.unanswered.value)
        assertNull(queue.hold("owner/none"))
    }

    @Test
    fun aPromptOpenedElsewhereOverTheActiveOneIsParkedRatherThanOfferedAgain() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.enqueue("owner/second", "second")
        queue.enqueue("owner/third", "third")
        queue.take()

        // The notification opened the second while the first is active: the screen must not
        // open it again once the first settles, but the user may still ask for it.
        assertNull(queue.hold("owner/second"))
        assertEquals(InstallConfirmation("owner/first", "first"), queue.active())
        assertEquals(setOf("owner/second"), queue.unanswered.value)
        queue.remove("owner/first")
        assertEquals(InstallConfirmation("owner/third", "third"), queue.next.value)
        assertTrue(queue.resume("owner/second"))
        assertEquals(InstallConfirmation("owner/second", "second"), queue.take())
    }

    @Test
    fun tellsWhichConfirmationIsBeingPresented() {
        val queue = InstallConfirmationQueue<String>()
        assertNull(queue.active())
        queue.enqueue("owner/first", "first")
        assertNull(queue.active())
        queue.take()
        assertEquals(InstallConfirmation("owner/first", "first"), queue.active())
        queue.park("owner/first", queue.presentation())
        assertNull(queue.active())
    }

    @Test
    fun tellsWhenTheConfirmationOfAnAppWasPresented() {
        val queue = InstallConfirmationQueue<String>()
        queue.enqueue("owner/first", "first")
        queue.enqueue("owner/second", "second")
        queue.enqueue("owner/third", "third")
        // Waiting is not presented; nor is one that is not here.
        assertNull(queue.presentedAt("owner/first"))
        assertNull(queue.presentedAt("owner/none"))

        val before = System.currentTimeMillis()
        queue.take()
        val presented = queue.presentedAt("owner/first")
        assertNotNull(presented)
        assertTrue(presented!! >= before)
        assertNull(queue.presentedAt("owner/second"))
        // Held over another, it is presented too, and parked.
        assertNull(queue.hold("owner/third"))
        assertNotNull(queue.presentedAt("owner/third"))
        // Parked, it stays presented, from the time it was.
        queue.park("owner/first", queue.presentation())
        assertEquals(presented, queue.presentedAt("owner/first"))
        // Asked for again, it waits to be presented anew; settled, it is gone.
        assertTrue(queue.resume("owner/first"))
        assertNull(queue.presentedAt("owner/first"))
        queue.remove("owner/third")
        assertNull(queue.presentedAt("owner/third"))
    }

    @Test
    fun aPromptOpenedElsewhereForAnAppNotHereCountsAsPresentedAllTheSame() {
        // After a restart the queue is empty, yet the notification of the earlier process
        // opens its prompt: the user sees it, and may read it at length before refusing.
        val queue = InstallConfirmationQueue<String>()
        assertNull(queue.hold("owner/gone"))
        assertNotNull(queue.presentedAt("owner/gone"))
        assertFalse(queue.holds("owner/gone"))
        queue.remove("owner/gone")
        assertNull(queue.presentedAt("owner/gone"))
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
