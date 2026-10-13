package org.jarsi.arkstore.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.jarsi.arkstore.install.Root
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The restore of an app's data as a whole: unpack and check every part, then put them all in place, or none. */
class RootDataTest {

    private val pkg = "org.example.app"
    private val commands = ArrayList<String>()

    @After
    fun after() {
        Root.transport = null
    }

    /** An archive of the app's folder alone, as `tar` writes one, with the end marker. */
    private fun archive(): ByteArray {
        val header = ByteArray(512)
        fun put(at: Int, text: String) = text.toByteArray().copyInto(header, at)
        put(0, "$pkg/")
        put(100, "0000700\u0000")
        put(108, "0000000\u0000")
        put(116, "0000000\u0000")
        put(124, "00000000000\u0000")
        put(136, "00000000000\u0000")
        header[156] = '5'.code.toByte()
        put(257, "ustar\u0000")
        put(263, "00")
        " ".repeat(8).toByteArray().copyInto(header, 148)
        put(148, String.format("%06o", header.sumOf { it.toInt() and 0xff }) + "\u0000 ")
        return header + ByteArray(1024)
    }

    /**
     * Answers as a device would: the staging holds the app's folder, no links, and every
     * other command goes through, unless [failing] says it fails.
     */
    private fun device(failing: (String) -> Boolean = { false }) {
        Root.transport = { command, stdin, _, _ ->
            commands += command
            stdin?.invoke(ByteArrayOutputStream())
            when {
                failing(command) -> Root.Outcome.Ran(1, "", "failed")
                command.startsWith("find ") && "-maxdepth" in command -> Root.Outcome.Ran(0, command.split(' ')[1] + "/$pkg\n", "")
                else -> Root.Outcome.Ran(0, "", "")
            }
        }
    }

    private fun swaps() = commands.filter { it.startsWith("chown -R -h 10123:10123 ") }
    private fun unswaps() = commands.filter { it.startsWith("{ [ ! -e /data/user") && "rm -rf /data/user" in it }
    private fun finishes() = commands.filter { it.startsWith("rm -rf /data/user") && ".arkstore-old-" in it }

    @Test
    fun thePartsAreNullWhenTheListingDidNotRunToItsEnd() = runBlocking {
        val end = RootDataCommands.PARTS_END
        Root.transport = { _, _, _, _ -> Root.Outcome.Ran(1, "", "Permission denied") }
        assertNull(RootData.parts(listOf(pkg)))
        Root.transport = { _, _, _, _ -> Root.Outcome.Ran(0, "data $pkg\n", "") }
        assertNull(RootData.parts(listOf(pkg)))
        Root.transport = { _, _, _, _ -> Root.Outcome.Ran(0, "data $pkg\n$end\n", "", cut = true) }
        assertNull(RootData.parts(listOf(pkg)))
        Root.transport = { _, _, _, _ -> Root.Outcome.NoRoot }
        assertNull(RootData.parts(listOf(pkg)))
        Root.transport = { _, _, _, _ -> Root.Outcome.Ran(0, "data $pkg\n$end\n", "") }
        assertEquals(mapOf(pkg to listOf("data")), RootData.parts(listOf(pkg)))
        Root.transport = { _, _, _, _ -> Root.Outcome.Ran(0, "$end\n", "") }
        assertEquals(emptyMap<String, List<String>>(), RootData.parts(listOf(pkg)))
    }

    @Test
    fun everyPartIsPutInPlaceAndClearedUpAfter() = runBlocking {
        device()
        val a = RootData.stage("tar", AppBackup.DATA_USER, pkg, ByteArrayInputStream(archive()))
        val b = RootData.stage("tar", AppBackup.DATA_USER_DE, pkg, ByteArrayInputStream(archive()))
        assertTrue(a.token != b.token)
        RootData.swap(listOf(a, b), pkg, 10123)
        assertEquals(listOf("am force-stop --user 0 $pkg"), commands.filter { it.startsWith("am ") })
        assertEquals(2, swaps().size)
        assertTrue(swaps()[0].contains("/data/user/0/"))
        assertTrue(swaps()[1].contains("/data/user_de/0/"))
        assertEquals(0, unswaps().size)
        assertEquals(2, finishes().size)
        val before = commands.size
        RootData.discard(listOf(a, b))
        assertEquals(before, commands.size)
    }

    @Test
    fun aPartThatFailsToGoInPlacePutsTheOnesBeforeItBack() = runBlocking {
        device(failing = { it.startsWith("chown") && "/data/user_de/" in it })
        val a = RootData.stage("tar", AppBackup.DATA_USER, pkg, ByteArrayInputStream(archive()))
        val b = RootData.stage("tar", AppBackup.DATA_USER_DE, pkg, ByteArrayInputStream(archive()))
        assertThrows(IOException::class.java) { runBlocking { RootData.swap(listOf(a, b), pkg, 10123) } }
        assertEquals(2, swaps().size)
        // The part that failed is put back too: its command may have moved the data before it failed.
        val back = unswaps()
        assertEquals(2, back.size)
        assertTrue(back[0].contains("/data/user_de/0/$pkg") && back[0].contains(".arkstore-old-${b.token}"))
        assertTrue(back[1].contains("/data/user/0/$pkg") && back[1].contains(".arkstore-old-${a.token}"))
        assertTrue(commands.indexOf(back[0]) > commands.indexOf(swaps()[1]))
        // The old data is never removed on this way out; the staging folders are, once discarded.
        assertEquals(0, finishes().size)
        RootData.discard(listOf(a, b))
        val removed = commands.filter { it.startsWith("rm -rf /data/user") && ".arkstore-restore-" in it && "mkdir" !in it }
        assertEquals(2, removed.size)
    }

    @Test
    fun aSwapCutShortIsPutBackAsWell() = runBlocking {
        // Root lost in the middle of the second part: the first is put back, and so is the second.
        device()
        val a = RootData.stage("tar", AppBackup.DATA_USER, pkg, ByteArrayInputStream(archive()))
        val b = RootData.stage("tar", AppBackup.DATA_USER_DE, pkg, ByteArrayInputStream(archive()))
        val before = Root.transport!!
        Root.transport = { command, stdin, stdout, global ->
            if (command.startsWith("chown") && "/data/user_de/" in command) {
                commands += command
                Root.Outcome.NoRoot
            } else {
                before(command, stdin, stdout, global)
            }
        }
        assertThrows(IOException::class.java) { runBlocking { RootData.swap(listOf(a, b), pkg, 10123) } }
        assertEquals(2, unswaps().size)
        assertEquals(0, finishes().size)
    }

    @Test
    fun anArchiveThatIsRefusedLeavesNothingBehindAndTouchesNoData() = runBlocking {
        device()
        val outside = archive().copyOf().also { "other.app/".toByteArray().copyInto(it, 0) }
        assertThrows(IOException::class.java) {
            runBlocking { RootData.stage("tar", AppBackup.DATA_USER, pkg, ByteArrayInputStream(outside)) }
        }
        assertTrue(commands.last().startsWith("rm -rf /data/user/0/.arkstore-restore-"))
        assertEquals(0, swaps().size)
        // Likewise one whose unpacked folder holds more than the app's folder.
        commands.clear()
        Root.transport = { command, stdin, _, _ ->
            commands += command
            stdin?.invoke(ByteArrayOutputStream())
            if (command.startsWith("find ") && "-maxdepth" in command) {
                Root.Outcome.Ran(0, command.split(' ')[1] + "/$pkg\n" + command.split(' ')[1] + "/other\n", "")
            } else {
                Root.Outcome.Ran(0, "", "")
            }
        }
        assertThrows(IOException::class.java) {
            runBlocking { RootData.stage("tar", AppBackup.DATA_USER, pkg, ByteArrayInputStream(archive())) }
        }
        assertTrue(commands.last().startsWith("rm -rf /data/user/0/.arkstore-restore-"))
        assertEquals(0, swaps().size)
    }
}
