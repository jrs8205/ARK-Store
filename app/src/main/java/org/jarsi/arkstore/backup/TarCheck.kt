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
            if (ended) return
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
        val name = paxPath ?: longName ?: string(NAME, 100).let { base ->
            val prefix = if (isUstar()) string(PREFIX, 155) else ""
            if (prefix.isEmpty()) base else "$prefix/$base"
        }
        val link = paxLink ?: longLink ?: string(LINK, 100)
        longName = null
        longLink = null
        paxPath = null
        paxLink = null
        when (type) {
            '0', '\u0000', '7', '5' -> checkName(name)
            '2' -> {
                checkName(name)
                checkLink(name, link, symbolic = true)
            }
            '1' -> {
                checkName(name)
                checkLink(name, link, symbolic = false)
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
                    when (key) {
                        "path" -> paxPath = value
                        "linkpath" -> paxLink = value
                    }
                }
            }
            // A global header applies to everything after; its paths are not taken, as tar does not take them either.
            'g' -> Unit
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
        if (path.isEmpty() || path.startsWith("/") || '\\' in path || '\u0000' in path) return null
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts
    }

    private fun checkName(name: String) {
        val parts = parts(name) ?: throw IOException("The archive names a path that is not allowed: $name")
        if (parts.first() != root) throw IOException("The archive names a path outside its folder: $name")
    }

    /**
     * Whether [link], the target of [name], stays under [root]: a hard link's target is a
     * path in the archive; a symbolic link's target is taken from the link's own folder, and
     * may climb with `..` only as far as [root].
     */
    private fun checkLink(name: String, link: String, symbolic: Boolean) {
        if (link.isEmpty() || link.startsWith("/") || '\\' in link || '\u0000' in link) {
            throw IOException("The archive links to a path that is not allowed: $name -> $link")
        }
        if (!symbolic) {
            checkName(link)
            return
        }
        val from = parts(name)!!.dropLast(1).toMutableList()
        for (part in link.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (from.size <= 1) throw IOException("The archive links outside its folder: $name -> $link")
                    from.removeAt(from.size - 1)
                }
                else -> from += part
            }
        }
        if (from.first() != root) throw IOException("The archive links outside its folder: $name -> $link")
    }

    private fun isUstar(): Boolean = header[MAGIC] == 'u'.code.toByte() && header[MAGIC + 1] == 's'.code.toByte() &&
        header[MAGIC + 2] == 't'.code.toByte() && header[MAGIC + 3] == 'a'.code.toByte() && header[MAGIC + 4] == 'r'.code.toByte()

    private fun string(at: Int, length: Int): String {
        var end = at
        while (end < at + length && header[end] != 0.toByte()) end++
        return String(header, at, end - at, Charsets.UTF_8)
    }

    /** The entry's size: octal as a rule, or base 256 for a size the octal field cannot hold. */
    private fun size(): Long {
        if (header[SIZE].toInt() and 0x80 != 0) {
            var value = 0L
            for (i in 1 until 12) value = (value shl 8) or (header[SIZE + i].toLong() and 0xff)
            if (value < 0) throw IOException("Not a size")
            return value
        }
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
