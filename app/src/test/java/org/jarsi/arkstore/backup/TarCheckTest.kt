package org.jarsi.arkstore.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TarCheckTest {

    private val root = "org.example.app"

    /** One entry of a tar made here: a ustar header and its content, padded to blocks. */
    private class Item(
        val name: String,
        val type: Char = '0',
        val content: ByteArray = ByteArray(0),
        val link: String = "",
        val prefix: String = "",
        val size: Long = content.size.toLong()
    )

    private fun tar(vararg items: Item, end: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        for (item in items) {
            val header = ByteArray(512)
            fun put(at: Int, text: String) = text.toByteArray().copyInto(header, at)
            put(0, item.name)
            put(100, "0000644\u0000")
            put(108, "0001750\u0000")
            put(116, "0001750\u0000")
            put(124, String.format("%011o", item.size) + "\u0000")
            put(136, "00000000000\u0000")
            header[156] = item.type.code.toByte()
            put(157, item.link)
            put(257, "ustar\u0000")
            put(263, "00")
            put(345, item.prefix)
            " ".repeat(8).toByteArray().copyInto(header, 148)
            val sum = header.sumOf { it.toInt() and 0xff }
            put(148, String.format("%06o", sum) + "\u0000 ")
            out.write(header)
            out.write(item.content)
            val pad = (512 - item.content.size % 512) % 512
            out.write(ByteArray(pad))
        }
        if (end) out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun pass(bytes: ByteArray): ByteArray = TarCheck(ByteArrayInputStream(bytes), root).use { it.readBytes() }

    private fun refused(bytes: ByteArray): IOException =
        assertThrows(IOException::class.java) { TarCheck(ByteArrayInputStream(bytes), root).use { it.readBytes() } }

    @Test
    fun anArchiveOfTheFolderPassesThroughUnchanged() {
        val bytes = tar(
            Item("$root/", '5'),
            Item("$root/files/", '5'),
            Item("$root/files/notes.txt", content = "hello".toByteArray()),
            Item("$root/shared_prefs/a.xml", content = ByteArray(1000) { 1 }),
            Item("$root/files/link", '2', link = "notes.txt"),
            Item("$root/files/up", '2', link = "../shared_prefs/a.xml"),
            Item("$root/files/same", '1', link = "$root/files/notes.txt"),
            Item("files/x.txt", prefix = root),
            Item("$root/files/dir", '5'),
            Item("$root/files/dir", '2', link = "notes.txt")
        )
        assertArrayEquals(bytes, pass(bytes))
        // Read a byte at a time too.
        val slow = TarCheck(ByteArrayInputStream(bytes), root)
        val out = ByteArrayOutputStream()
        while (true) {
            val b = slow.read()
            if (b < 0) break
            out.write(b)
        }
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun namesOutsideTheFolderAreRefused() {
        for (name in listOf("/etc/passwd", "../x", "$root/../other/x", "other.app/x", "x", "$root/a/../../b")) {
            refused(tar(Item(name, content = "x".toByteArray())))
        }
        refused(tar(Item("x", prefix = "../$root")))
    }

    @Test
    fun linksOutOfTheFolderAreRefused() {
        refused(tar(Item("$root/files/link", '2', link = "/data/system/x")))
        refused(tar(Item("$root/files/link", '2', link = "../../other.app/x")))
        refused(tar(Item("$root/link", '2', link = "..")))
        refused(tar(Item("$root/files/link", '2', link = "")))
        refused(tar(Item("$root/files/link", '1', link = "other.app/x")))
        refused(tar(Item("$root/files/link", '1', link = "/x")))
        // A hard link to a file the archive has not made, or to a link, could be to anything.
        refused(tar(Item("$root/files/link", '1', link = "$root/files/other.txt")))
        refused(
            tar(
                Item("$root/files/a", '2', link = "b"),
                Item("$root/files/link", '1', link = "$root/files/a")
            )
        )
        // A file made into a link afterwards is a link.
        refused(
            tar(
                Item("$root/files/a", content = "x".toByteArray()),
                Item("$root/files/a", '2', link = "b"),
                Item("$root/files/link", '1', link = "$root/files/a")
            )
        )
        // The folder itself as a link, which the move into place would follow.
        refused(tar(Item(root, '2', link = ".")))
        refused(tar(Item("$root/", '2', link = "x")))
        // Up to the folder itself is as far as a link may climb.
        pass(tar(Item("$root/files/deep/link", '2', link = "../../databases/db")))
    }

    @Test
    fun devicesPipesAndTheLikeAreRefused() {
        for (type in listOf('3', '4', '6')) refused(tar(Item("$root/dev", type)))
    }

    @Test
    fun longNamesOfTheGnuAndPaxKindsAreReadAsNames() {
        val long = "$root/" + "d/".repeat(60) + "file.txt"
        pass(tar(Item("././@LongLink", 'L', content = (long + "\u0000").toByteArray()), Item("$root/d", content = "x".toByteArray())))
        val evil = "../" + "d/".repeat(60) + "file.txt"
        refused(tar(Item("././@LongLink", 'L', content = (evil + "\u0000").toByteArray()), Item("$root/d", content = "x".toByteArray())))
        fun pax(vararg records: Pair<String, String>): ByteArray {
            val text = records.joinToString("") { (k, v) ->
                val body = " $k=$v\n"
                var length = body.length + 1
                while (length.toString().length + body.length != length) length++
                "$length$body"
            }
            return text.toByteArray()
        }
        pass(tar(Item("./PaxHeaders/x", 'x', content = pax("path" to long)), Item("$root/d", content = "x".toByteArray())))
        refused(tar(Item("./PaxHeaders/x", 'x', content = pax("path" to "/etc/x")), Item("$root/d", content = "x".toByteArray())))
        refused(tar(Item("./PaxHeaders/x", 'x', content = pax("linkpath" to "/etc/x")), Item("$root/d", '2', link = "ok")))
        // A pax name is used once: the entry after is named by its own header again.
        refused(
            tar(
                Item("./PaxHeaders/x", 'x', content = pax("path" to "$root/ok")),
                Item("$root/d", content = "x".toByteArray()),
                Item("../evil", content = "x".toByteArray())
            )
        )
    }

    @Test
    fun nothingPassesThroughALinkTheArchiveMade() {
        // A link to the folder itself: a path through it, climbed twice, would leave the folder on the device.
        refused(
            tar(
                Item("$root/up", '2', link = "."),
                Item("$root/x", '2', link = "up/up/../..")
            )
        )
        refused(
            tar(
                Item("$root/files", '2', link = "."),
                Item("$root/files/evil", content = "x".toByteArray())
            )
        )
        refused(
            tar(
                Item("$root/a", '2', link = "b"),
                Item("$root/a/deep/file", content = "x".toByteArray())
            )
        )
        // A folder made into a link afterwards is a link from then on.
        refused(
            tar(
                Item("$root/d/", '5'),
                Item("$root/d/ok", content = "x".toByteArray()),
                Item("$root/d", '2', link = "."),
                Item("$root/d/late", content = "x".toByteArray())
            )
        )
        // A folder, a file or another link where a link was made: tar may keep the link and use it.
        refused(
            tar(
                Item("$root/d", '2', link = "."),
                Item("$root/d/", '5'),
                Item("$root/d/late", content = "x".toByteArray())
            )
        )
        refused(
            tar(
                Item("$root/f", '2', link = "x"),
                Item("$root/f", content = "x".toByteArray())
            )
        )
        refused(
            tar(
                Item("$root/f", '2', link = "x"),
                Item("$root/f", '2', link = "y")
            )
        )
        // A name or a target with a line break in it would make a line of its own in a listing.
        refused(tar(Item("$root/a\nb", content = "x".toByteArray())))
        refused(tar(Item("$root/l", '2', link = "a\nb")))
    }

    @Test
    fun whatDifferentTarsWouldReadDifferentlyIsRefused() {
        // A link with a size: some tars skip the bytes, some read them as the next header.
        refused(tar(Item("$root/link", '2', link = "x", size = 512)))
        refused(tar(Item("$root/link", '1', link = "$root/x", size = 512)))
        // A prefix in a header that is not POSIX ustar: in a GNU header those bytes are other things.
        val notUstar = tar(Item("x", prefix = root))
        notUstar[257] = 0
        refused(fixChecksum(notUstar))
        val gnu = tar(Item("x", prefix = root))
        gnu[262] = ' '.code.toByte()
        gnu[263] = ' '.code.toByte()
        gnu[264] = 0
        refused(fixChecksum(gnu))
        val gnuNoPrefix = tar(Item("$root/x", content = ByteArray(10)))
        gnuNoPrefix[262] = ' '.code.toByte()
        gnuNoPrefix[263] = ' '.code.toByte()
        gnuNoPrefix[264] = 0
        pass(fixChecksum(gnuNoPrefix))
        // A size in base 256.
        val base256 = tar(Item("$root/x", content = ByteArray(10)))
        base256[124] = 0x80.toByte()
        for (i in 125 until 136) base256[i] = 0
        base256[135] = 10
        refused(fixChecksum(base256))
        // A size in a pax header, and a path in a global one.
        fun pax(vararg records: Pair<String, String>): ByteArray = records.joinToString("") { (k, v) ->
            val body = " $k=$v\n"
            var length = body.length + 1
            while (length.toString().length + body.length != length) length++
            "$length$body"
        }.toByteArray()
        refused(tar(Item("./PaxHeaders/x", 'x', content = pax("size" to "10")), Item("$root/x", content = ByteArray(10))))
        refused(tar(Item("./PaxHeaders/x", 'x', content = pax("GNU.sparse.name" to "../x")), Item("$root/x", content = ByteArray(10))))
        refused(tar(Item("./PaxHeaders/g", 'g', content = pax("path" to "$root/x")), Item("$root/x", content = ByteArray(10))))
        pass(tar(Item("./PaxHeaders/g", 'g', content = pax("comment" to "hi")), Item("$root/x", content = ByteArray(10))))
        // Data after the end of the archive, which tar would not read.
        val ended = tar(Item("$root/x", content = ByteArray(10)))
        refused(ended + tar(Item("../evil", content = ByteArray(10))))
        pass(ended + ByteArray(5120))
        // The folder itself as a file.
        refused(tar(Item(root, content = "x".toByteArray())))
    }

    private fun fixChecksum(bytes: ByteArray): ByteArray {
        val header = bytes.copyOf(512)
        " ".repeat(8).toByteArray().copyInto(header, 148)
        val sum = header.sumOf { it.toInt() and 0xff }
        (String.format("%06o", sum) + "\u0000 ").toByteArray().copyInto(header, 148)
        return header + bytes.copyOfRange(512, bytes.size)
    }

    @Test
    fun anArchiveCutShortOrNotATarIsRefused() {
        val whole = tar(Item("$root/files/notes.txt", content = ByteArray(700) { 2 }))
        refused(whole.copyOf(whole.size - 1024 - 100))
        refused(whole.copyOf(512 + 100))
        refused(whole.copyOf(100))
        refused("not a tar at all, not even close to a header".toByteArray() + ByteArray(600))
        val badSum = whole.copyOf().also { it[0] = 'x'.code.toByte() }
        refused(badSum)
        // An archive that ends without the two empty blocks, but after a whole entry, is let through: tar takes it.
        assertTrue(pass(tar(Item("$root/a", content = "x".toByteArray()), end = false)).isNotEmpty())
    }
}
