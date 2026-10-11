package org.jarsi.arkstore.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupLogTest {

    private fun event(at: Long, kind: BackupEvent.Kind = BackupEvent.Kind.FOLDER) = BackupEvent(
        kind = kind,
        at = at,
        place = "ARK-varmuuskopio",
        apps = 3,
        bytes = 80_000_000,
        saved = 1,
        skipped = 2,
        failed = 0,
        outcome = BackupEvent.Outcome.DONE
    )

    @Test
    fun theNewestComesFirstAndOnlySoManyAreKept() {
        var log = emptyList<BackupEvent>()
        for (i in 1..(BackupLog.MAX + 3)) log = BackupLog.added(log, event(i.toLong()))
        assertEquals(BackupLog.MAX, log.size)
        assertEquals((BackupLog.MAX + 3).toLong(), log.first().at)
        assertEquals(4L, log.last().at)
    }

    @Test
    fun theLogRoundTripsAndToleratesWhatItCannotRead() {
        val log = listOf(event(2, BackupEvent.Kind.ZIP), event(1, BackupEvent.Kind.RESTORE))
        assertEquals(log, BackupLog.fromText(BackupLog.toText(log)))
        assertEquals(emptyList<BackupEvent>(), BackupLog.fromText(null))
        assertEquals(emptyList<BackupEvent>(), BackupLog.fromText("not json"))
        // An entry of a kind or outcome not known is left out, the rest kept.
        val text = BackupLog.toText(log).replaceFirst("\"ZIP\"", "\"TAPE\"")
        assertEquals(listOf(log[1]), BackupLog.fromText(text))
    }

    @Test
    fun anEventTellsHowTheJobEnded() {
        val done = BackupLog.event(BackupEvent.Kind.FOLDER, 5, "F", 3, 100, BackupState.Done(false, 1, 2, listOf("X")))
        assertEquals(BackupEvent(BackupEvent.Kind.FOLDER, 5, "F", 3, 100, 1, 2, 1, BackupEvent.Outcome.DONE), done)
        val cancelled = BackupLog.event(BackupEvent.Kind.ZIP, 5, "Z", 2, 50, BackupState.Failed(false, BackupError.CANCELLED, zip = true))
        assertEquals(BackupEvent.Outcome.CANCELLED, cancelled.outcome)
        val failed = BackupLog.event(BackupEvent.Kind.RESTORE, 5, "R", 2, 50, BackupState.Failed(true, BackupError.FOLDER_UNAVAILABLE))
        assertEquals(BackupEvent.Outcome.FAILED, failed.outcome)
        assertEquals(0, failed.saved)
    }
}
