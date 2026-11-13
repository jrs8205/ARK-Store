package org.jarsi.arkstore.backup

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a backup or a restore that did not get to its end is told of; see [failedBy]. */
class BackupEndTest {

    @Test
    fun aZipWrittenInVainAndRemovedIsToldOfAsRemoved() {
        assertEquals(
            BackupState.Failed(false, BackupError.FOLDER_UNAVAILABLE, zip = true),
            failedBy(restoring = false, zip = true, error = IOException("no room"))
        )
        assertEquals(
            BackupState.Failed(false, BackupError.CANCELLED, zip = true),
            failedBy(restoring = false, zip = true, error = CancellationException("stop"))
        )
    }

    @Test
    fun aZipThatCouldNotBeRemovedIsToldOfAsLeft() {
        val failure = IOException("no room")
        val left = leftBehind(failure)
        assertSame(failure, left.cause)
        assertEquals(
            BackupState.Failed(false, BackupError.FOLDER_UNAVAILABLE, zip = true, zipLeft = true),
            failedBy(restoring = false, zip = true, error = left)
        )
    }

    @Test
    fun aCancelStaysACancelWhenTheZipCouldNotBeRemoved() {
        val left = leftBehind(CancellationException("stop"))
        assertTrue(left is CancellationException)
        assertEquals(
            BackupState.Failed(false, BackupError.CANCELLED, zip = true, zipLeft = true),
            failedBy(restoring = false, zip = true, error = left)
        )
    }

    @Test
    fun aCancelCarriesWhetherTheFoldersIndexWasWritten() {
        assertEquals(
            BackupState.Failed(false, BackupError.CANCELLED, indexWritten = false),
            failedBy(restoring = false, zip = false, error = CancelledWith(false, CancellationException("stop")))
        )
    }

    @Test
    fun aRemovalThatFailsOrIsRefusedIsNotTakenForDone() {
        assertTrue(gone { true })
        assertFalse(gone { false })
        assertFalse(gone { throw SecurityException("not yours") })
        assertFalse(gone { throw IllegalArgumentException("no such document") })
    }

    @Test
    fun aZipBackupCancelledBeforeItBeganLeavesTheFileThePickerMade() {
        assertTrue(unbegun(restoring = false, zip = true).zipLeft)
        // A restore's zip is the user's own backup, and a folder has no file to speak of.
        assertFalse(unbegun(restoring = true, zip = true).zipLeft)
        assertFalse(unbegun(restoring = false, zip = false).zipLeft)
        assertEquals(BackupError.CANCELLED, unbegun(restoring = false, zip = true).reason)
    }

    @Test
    fun theHistoryTellsACancelledZipAsCancelledWhetherItWasRemovedOrNot() {
        val event = BackupLog.event(BackupEvent.Kind.ZIP, 5, "Z", 2, 50, unbegun(restoring = false, zip = true))
        assertEquals(BackupEvent.Outcome.CANCELLED, event.outcome)
    }
}
