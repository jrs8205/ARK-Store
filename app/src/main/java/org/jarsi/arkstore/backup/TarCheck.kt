package org.jarsi.arkstore.backup

import java.io.IOException
import java.io.InputStream

/**
 * Passes a tar stream through while reading its headers, and refuses one that would reach
 * anywhere but under the folder [root] it is meant for: the archive is extracted as root,
 * so an archive that was tampered with must not be able to write elsewhere. Refused are a
 * name that is absolute, that has a `..` part or that lies outside [root]; a symbolic link
 * whose target is absolute or leads out of [root]; a hard link to anything outside [root];
 * and devices, pipes and sockets. Long names and paths of the GNU and pax kinds are read as
 * `tar` reads them. A stream that ends within an entry is refused as cut short.
 *
 * Reading throws an [IOException] at the first offending entry; what came before it may
 * have been extracted by then, so the caller discards the whole of the extraction.
 */
class TarCheck(private val input: InputStream, private val root: String) : InputStream() {
    private val header = ByteArray(BLOCK)
    private var headerCount = 0

    /** Bytes of the current entry's content and padding still to pass through. */
    private var remaining = 0L

    /** The content of an entry that names the next one (GNU long names, pax headers). */
    private var capture: ByteArrayBuilder? = null
    private var captureType = 0

    private var longName: String? = null
    private var longLink: String? = null
    private var paxPath: String? = null
    private var paxLink: String? = null
    private var zeroBlocks = 0
    private var ended = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    /** The paths of the symbolic links the archive has made so far: nothing may pass through one. */
    private val symlinks = HashSet<String>()

    /** The paths of the files the archive has made so far, which a hard link may point to. */
    private val regularFiles = HashSet<String>()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = input.read(b, off, len)
        if (n < 0) {
            if (!ended && (headerCount != 0 || remaining != 0L)) throw IOException("The archive is cut short")
            return -1
        }
        inspect(b, off, n)
        return n
    }

    private fun inspect(b: ByteArray, off: Int, len: Int) {
        var at = off
        val end = off + len
        while (at < end) {
            if (ended) {
                // Nothing may follow the end of the archive but padding: tar would not read
                // it, and what tar does not read is not checked.
                for (i in at until end) if (b[i] != 0.toByte()) throw IOException("The archive goes on after its end")
                return
            }
            if (remaining > 0) {
                val n = minOf(remaining, (end - at).toLong()).toInt()
                capture?.append(b, at, n)
                at += n
                remaining -= n
                if (remaining == 0L) capture?.let { finishCapture(it) }
                continue
            }
            val n = minOf(BLOCK - headerCount, end - at)
            System.arraycopy(b, at, header, headerCount, n)
            headerCount += n
            at += n
            if (headerCount == BLOCK) {
                headerCount = 0
                parseHeader()
            }
        }
    }

    private fun parseHeader() {
        if (header.all { it == 0.toByte() }) {
            if (++zeroBlocks == 2) ended = true
            return
        }
        zeroBlocks = 0
        if (!checksumHolds()) throw IOException("Not a tar header")
        val type = header[TYPE].toInt().toChar()
        val size = size()
        val padded = (size + BLOCK - 1) / BLOCK * BLOCK
        remaining = padded
        when (type) {
            'L', 'K', 'x', 'g' -> {
                if (size > MAX_META) throw IOException("A header of the archive is too long: $size bytes")
                val builder = ByteArrayBuilder(size.toInt())
                captureType = type.code
                if (padded == 0L) finishCapture(builder) else capture = builder
                return
            }
        }
        val prefix = string(PREFIX, 155)
        // A prefix is a POSIX ustar header's; in a GNU header the same bytes are other things,
        // and a name that is read two ways is not checked.
        if (prefix.isNotEmpty() && !isPosixUstar()) throw IOException("The archive has a prefix in a header that is not ustar")
        val name = paxPath ?: longName ?: string(NAME, 100).let { base ->
            if (prefix.isEmpty()) base else "$prefix/$base"
        }
        val link = paxLink ?: longLink ?: string(LINK, 100)
        longName = null
        longLink = null
        paxPath = null
        paxLink = null
        val parts = checkName(name)
        val path = parts.joinToString("/")
        if (parts.size == 1 && type != '5') throw IOException("The archive has the app's folder as something other than a folder")
        // Once a link, always a link: a tar that finds one where it would make a folder uses
        // the folder the link leads to, and one that finds one where it would make a file may
        // write through it.
        if (path in symlinks) throw IOException("The archive makes something where it made a link: $name")
        when (type) {
            '0', '\u0000', '7' -> regularFiles += path
            '5' -> regularFiles -= path
            '2' -> {
                // A link holds no data; a tar that read it as holding some would read the
                // headers after it elsewhere than here.
                if (size != 0L) throw IOException("The archive has a link with data: $name")
                checkLink(parts, link, symbolic = true)
                regularFiles -= path
                symlinks += path
            }
            '1' -> {
                if (size != 0L) throw IOException("The archive has a link with data: $name")
                checkLink(parts, link, symbolic = false)
                regularFiles += path
            }
            else -> throw IOException("The archive holds something that is not a file: type '$type' at $name")
        }
    }

    private fun finishCapture(captured: ByteArrayBuilder) {
        capture = null
        val text = captured.text()
        when (captureType.toChar()) {
            'L' -> longName = text.trimEnd('\u0000')
            'K' -> longLink = text.trimEnd('\u0000')
            'x' -> {
                for ((key, value) in paxRecords(text)) {
                    when {
                        key == "path" -> paxPath = value
                        key == "linkpath" -> paxLink = value
                        // A size given here is read by some tars and not by others, and a
                        // sparse file names itself and lays itself out in ways of its own.
                        key == "size" || key.startsWith("GNU.sparse") ->
                            throw IOException("The archive has a pax record that is not read the same way by every tar: $key")
                    }
                }
            }
            // A global header applies to everything after; one that names paths or sizes is
            // read differently by different tars.
            'g' -> for ((key, _) in paxRecords(text)) {
                if (key == "path" || key == "linkpath" || key == "size" || key.startsWith("GNU.sparse")) {
                    throw IOException("The archive names a path in a global header")
                }
            }
        }
    }

    /** The records of a pax header: each "length key=value\n". */
    private fun paxRecords(text: String): List<Pair<String, String>> {
        val records = ArrayList<Pair<String, String>>()
        var at = 0
        while (at < text.length) {
            val space = text.indexOf(' ', at)
            if (space < 0) throw IOException("Not a pax header")
            val length = text.substring(at, space).toIntOrNull() ?: throw IOException("Not a pax header")
            if (length <= 0 || at + length > text.length) throw IOException("Not a pax header")
            val record = text.substring(space + 1, at + length).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals < 0) throw IOException("Not a pax header")
            records += record.substring(0, equals) to record.substring(equals + 1)
            at += length
        }
        return records
    }

    /** The parts of [path] as it stands in the archive, with no empty parts and no `.`; null when it is not allowed. */
    private fun parts(path: String): List<String>? {
        // A control character in a name could make a line of its own in what the device
        // lists of the unpacked folder, which is checked line by line.
        if (path.isEmpty() || path.startsWith("/") || '\\' in path || path.any { it < ' ' }) return null
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts
    }

    /** The parts of [name], under [root] and passing through no link the archive has made. */
    private fun checkName(name: String): List<String> {
        val parts = parts(name) ?: throw IOException("The archive names a path that is not allowed: $name")
        if (parts.first() != root) throw IOException("The archive names a path outside its folder: $name")
        throughNoLink(parts.dropLast(1), name)
        return parts
    }

    /** Refuses a path whose folders, [folders], include a link the archive has made: where it leads is not known here. */
    private fun throughNoLink(folders: List<String>, name: String) {
        for (i in 1..folders.size) {
            if (folders.subList(0, i).joinToString("/") in symlinks) {
                throw IOException("The archive reaches through a link it made: $name")
            }
        }
    }

    /**
     * Whether [link], the target of the entry at [parts], stays under [root]: a hard link's
     * target is a file the archive has made; a symbolic link's target is taken from the
     * link's own folder, may climb with `..` only as far as [root], and may pass through no
     * link the archive has made, as such a link could lead anywhere.
     */
    private fun checkLink(parts: List<String>, link: String, symbolic: Boolean) {
        val name = parts.joinToString("/")
        if (link.isEmpty() || link.startsWith("/") || '\\' in link || link.any { it < ' ' }) {
            throw IOException("The archive links to a path that is not allowed: $name -> $link")
        }
        if (!symbolic) {
            val target = parts(link) ?: throw IOException("The archive links to a path that is not allowed: $name -> $link")
            if (target.joinToString("/") !in regularFiles) {
                throw IOException("The archive links to a file it has not made: $name -> $link")
            }
            return
        }
        val from = parts.dropLast(1).toMutableList()
        for (part in link.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (from.size <= 1) throw IOException("The archive links outside its folder: $name -> $link")
                    from.removeAt(from.size - 1)
                }
                else -> from += part
            }
            if (from.joinToString("/") in symlinks) throw IOException("The archive links through a link it made: $name -> $link")
        }
        if (from.first() != root) throw IOException("The archive links outside its folder: $name -> $link")
    }

    /** Whether the header is POSIX ustar: "ustar" and a NUL, not GNU's "ustar" and a space. */
    private fun isPosixUstar(): Boolean = header[MAGIC] == 'u'.code.toByte() && header[MAGIC + 1] == 's'.code.toByte() &&
        header[MAGIC + 2] == 't'.code.toByte() && header[MAGIC + 3] == 'a'.code.toByte() && header[MAGIC + 4] == 'r'.code.toByte() &&
        header[MAGIC + 5] == 0.toByte()

    private fun string(at: Int, length: Int): String {
        var end = at
        while (end < at + length && header[end] != 0.toByte()) end++
        return String(header, at, end - at, Charsets.UTF_8)
    }

    /**
     * The entry's size, in octal. A size in base 256, which some tars read and others do not,
     * is refused: it stands for more than any file of an app's data is.
     */
    private fun size(): Long {
        if (header[SIZE].toInt() and 0x80 != 0) throw IOException("The archive has a size that is not octal")
        return octal(SIZE, 12)
    }

    private fun octal(at: Int, length: Int): Long {
        var value = 0L
        var seen = false
        for (i in at until at + length) {
            val c = header[i].toInt().toChar()
            when {
                c in '0'..'7' -> {
                    value = value * 8 + (c - '0')
                    seen = true
                }
                c == ' ' || c == '\u0000' -> if (seen) break
                else -> throw IOException("Not an octal number in a tar header")
            }
        }
        return value
    }

    private fun checksumHolds(): Boolean {
        val said = try {
            octal(CHECKSUM, 8)
        } catch (_: IOException) {
            return false
        }
        var unsigned = 0L
        var signed = 0L
        for (i in 0 until BLOCK) {
            val b = if (i in CHECKSUM until CHECKSUM + 8) ' '.code.toByte() else header[i]
            unsigned += b.toInt() and 0xff
            signed += b.toInt()
        }
        return said == unsigned || said == signed
    }

    override fun close() = input.close()

    /** Gathers the content of an entry up to its size; the padding after it is let go. */
    private class ByteArrayBuilder(size: Int) {
        private val bytes = ByteArray(size)
        private var count = 0

        fun append(b: ByteArray, off: Int, len: Int) {
            val n = minOf(len, bytes.size - count)
            if (n > 0) {
                System.arraycopy(b, off, bytes, count, n)
                count += n
            }
        }

        fun text(): String = String(bytes, Charsets.UTF_8)
    }

    private companion object {
        const val BLOCK = 512
        const val NAME = 0
        const val SIZE = 124
        const val CHECKSUM = 148
        const val TYPE = 156
        const val LINK = 157
        const val MAGIC = 257
        const val PREFIX = 345

        /** The most a long name or a pax header may be; a path is nothing like it. */
        const val MAX_META = 1024 * 1024L
    }
}
