package org.jarsi.arkstore.data

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Identity of an APK as declared in its manifest, with the lowest Android it runs on: the
 * API level [minSdk], 1 when the manifest names none, or the preview of Android it names by
 * its codename instead, [minSdkCodename]; both null when they are not known.
 */
data class ApkInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int? = null,
    val minSdkCodename: String? = null
)

/** Random access to a remote or local file. */
fun interface RangeSource {
    fun read(start: Long, length: Int): ByteArray
}

/**
 * Reads the package name, the version and the lowest Android of an APK without downloading it
 * in full: only the zip central directory and the compressed AndroidManifest.xml entry are
 * fetched.
 */
object ApkManifestReader {

    private const val MANIFEST = "AndroidManifest.xml"
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val EOCD_MIN_SIZE = 22
    private const val MAX_COMMENT = 65535
    private const val MAX_DIRECTORY = 8 * 1024 * 1024
    private const val MAX_MANIFEST = 4 * 1024 * 1024

    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val FLAG_UTF8 = 0x100
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c
    private const val ATTR_VERSION_CODE_MAJOR = 0x01010576
    private const val ATTR_MIN_SDK = 0x0101020c
    // Android's codenames are a word or two; anything longer is not one.
    private const val MAX_CODENAME = 64

    /**
     * The file comes from the network and may be malformed or hostile, so every failure to
     * make sense of it, including out-of-range offsets, surfaces as an [IOException].
     */
    @Throws(IOException::class)
    fun read(size: Long, source: RangeSource): ApkInfo = guarded {
        parseManifest(readManifestBytes(size, source))
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: DataFormatException) {
        throw IOException("Corrupt compressed data", e)
    } catch (e: RuntimeException) {
        // Index, buffer and argument errors from offsets that point outside the data.
        throw IOException("Malformed APK", e)
    }

    private fun readManifestBytes(size: Long, source: RangeSource): ByteArray {
        if (size < EOCD_MIN_SIZE) throw IOException("Not a zip file")
        val tailLength = minOf(size, (EOCD_MIN_SIZE + MAX_COMMENT).toLong()).toInt()
        val tailStart = size - tailLength
        val tail = le(source.read(tailStart, tailLength))

        var eocd = tailLength - EOCD_MIN_SIZE
        while (eocd >= 0 && tail.getInt(eocd) != EOCD_SIGNATURE) eocd--
        if (eocd < 0) throw IOException("Zip end record not found")

        val directorySize = tail.getInt(eocd + 12).toUnsigned()
        val directoryOffset = tail.getInt(eocd + 16).toUnsigned()
        // Zip64 archives store 0xFFFFFFFF here; APKs that large are not supported.
        if (directorySize > MAX_DIRECTORY || directoryOffset + directorySize > size) {
            throw IOException("Unsupported zip layout")
        }
        val directory = if (directoryOffset >= tailStart) {
            val from = (directoryOffset - tailStart).toInt()
            le(tail.array().copyOfRange(from, from + directorySize.toInt()))
        } else {
            le(source.read(directoryOffset, directorySize.toInt()))
        }

        var position = 0
        while (position + 46 <= directory.capacity() &&
            directory.getInt(position) == CENTRAL_SIGNATURE
        ) {
            val method = directory.getShort(position + 10).toInt() and 0xffff
            val compressedSize = directory.getInt(position + 20).toUnsigned()
            val uncompressedSize = directory.getInt(position + 24).toUnsigned()
            val nameLength = directory.getShort(position + 28).toInt() and 0xffff
            val extraLength = directory.getShort(position + 30).toInt() and 0xffff
            val commentLength = directory.getShort(position + 32).toInt() and 0xffff
            val localOffset = directory.getInt(position + 42).toUnsigned()
            val name = String(directory.array(), position + 46, nameLength, Charsets.UTF_8)

            if (name == MANIFEST) {
                if (compressedSize > MAX_MANIFEST || uncompressedSize > MAX_MANIFEST ||
                    localOffset + 30 > size
                ) {
                    throw IOException("Manifest too large")
                }
                return readEntry(
                    source, size, localOffset, method, compressedSize.toInt(),
                    uncompressedSize.toInt()
                )
            }
            position += 46 + nameLength + extraLength + commentLength
        }
        throw IOException("Manifest not found")
    }

    private fun readEntry(
        source: RangeSource,
        size: Long,
        localOffset: Long,
        method: Int,
        compressedSize: Int,
        uncompressedSize: Int
    ): ByteArray {
        // The local header repeats the name and may carry a different extra field (zipalign
        // padding), so read some slack and fetch again only if that was not enough.
        var slack = 30 + MANIFEST.length + 1024
        while (true) {
            val wanted = minOf(size - localOffset, (slack + compressedSize).toLong()).toInt()
            val local = le(source.read(localOffset, wanted))
            if (local.getInt(0) != LOCAL_SIGNATURE) throw IOException("Bad local header")
            val nameLength = local.getShort(26).toInt() and 0xffff
            val extraLength = local.getShort(28).toInt() and 0xffff
            val dataStart = 30 + nameLength + extraLength
            if (dataStart + compressedSize > wanted) {
                if (localOffset + dataStart + compressedSize > size) throw IOException("Truncated")
                slack = dataStart
                continue
            }
            return when (method) {
                0 -> local.array().copyOfRange(dataStart, dataStart + compressedSize)
                8 -> {
                    val inflater = Inflater(true)
                    try {
                        inflater.setInput(local.array(), dataStart, compressedSize)
                        val out = ByteArray(uncompressedSize)
                        var done = 0
                        while (done < out.size && !inflater.finished()) {
                            val n = inflater.inflate(out, done, out.size - done)
                            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                                break
                            }
                            done += n
                        }
                        if (done != out.size) throw IOException("Manifest inflate failed")
                        out
                    } finally {
                        inflater.end()
                    }
                }
                else -> throw IOException("Unsupported compression $method")
            }
        }
    }

    /**
     * Parses the binary XML manifest: the root element's attributes for the package and the
     * version, and those of the uses-sdk element, wherever it stands among the root's
     * children, for the lowest Android. Only the strings looked for are decoded: a hostile
     * file can hold tens of thousands of elements named by strings of tens of thousands of
     * characters each, and decoding them all would cost gigabytes.
     */
    @Throws(IOException::class)
    internal fun parseManifest(bytes: ByteArray): ApkInfo = guarded { parseManifestUnchecked(bytes) }

    private fun parseManifestUnchecked(bytes: ByteArray): ApkInfo {
        val xml = le(bytes)
        var strings: StringPool? = null
        var resourceIds = IntArray(0)
        var identity: ApkInfo? = null
        // A manifest that names no lowest Android runs on every one, as Android takes it.
        var minSdk: Int? = 1
        var codename: String? = null

        var position = 8
        while (position + 8 <= bytes.size) {
            val type = xml.getShort(position).toInt() and 0xffff
            val headerSize = xml.getShort(position + 2).toInt() and 0xffff
            val chunkSize = xml.getInt(position + 4)
            if (chunkSize < 8 || headerSize < 8 || headerSize > chunkSize ||
                chunkSize > bytes.size - position
            ) {
                break
            }

            when (type) {
                CHUNK_STRING_POOL -> strings = StringPool(xml, position, headerSize, chunkSize)
                CHUNK_RESOURCE_MAP -> resourceIds = IntArray((chunkSize - headerSize) / 4) {
                    xml.getInt(position + headerSize + it * 4)
                }
                CHUNK_START_ELEMENT -> {
                    val body = position + headerSize
                    val pool = strings ?: throw IOException("String pool missing")
                    val name = xml.getInt(body + 4)
                    if (identity == null) {
                        if (!pool.matches(name, "manifest")) throw IOException("Unexpected root element")
                        identity = identityOf(pool, attributes(xml, resourceIds, body, position + chunkSize))
                    } else if (pool.matches(name, "uses-sdk")) {
                        // The children of the manifest element come in the order they were
                        // written, so the whole document is read: uses-sdk can follow
                        // application. Of several, the last one counts.
                        minSdk = 1
                        codename = null
                        for (attribute in attributes(xml, resourceIds, body, position + chunkSize)) {
                            if (attribute.resource != ATTR_MIN_SDK) continue
                            when (attribute.dataType) {
                                TYPE_INT_DEC, TYPE_INT_HEX -> minSdk = attribute.data
                                // A preview of Android, named by its codename.
                                TYPE_STRING -> {
                                    minSdk = null
                                    codename = pool[attribute.data, MAX_CODENAME]
                                }
                                else -> minSdk = null
                            }
                        }
                    }
                }
            }
            position += chunkSize
        }
        val info = identity ?: throw IOException("Manifest element not found")
        return info.copy(minSdk = minSdk, minSdkCodename = codename)
    }

    /** One attribute of a start element, its strings left in the pool until they are wanted. */
    private class Attribute(val resource: Int, val nameIndex: Int, val rawValue: Int, val dataType: Int, val data: Int)

    /** The attributes of the start element whose body begins at [body] and whose chunk ends at [end]. */
    private fun attributes(xml: ByteBuffer, resourceIds: IntArray, body: Int, end: Int): List<Attribute> {
        val attributeStart = xml.getShort(body + 8).toInt() and 0xffff
        val attributeSize = xml.getShort(body + 10).toInt() and 0xffff
        val attributeCount = xml.getShort(body + 12).toInt() and 0xffff
        if (attributeSize < 20 || body + attributeStart + attributeCount.toLong() * attributeSize > end) {
            throw IOException("Attributes out of range")
        }
        return List(attributeCount) { i ->
            val attribute = body + attributeStart + i * attributeSize
            val nameIndex = xml.getInt(attribute + 4)
            Attribute(
                resource = resourceIds.getOrElse(nameIndex) { 0 },
                nameIndex = nameIndex,
                rawValue = xml.getInt(attribute + 8),
                dataType = xml.get(attribute + 15).toInt() and 0xff,
                data = xml.getInt(attribute + 16)
            )
        }
    }

    /** The value of [attribute] as text, or null when it has none. */
    private fun text(pool: StringPool, attribute: Attribute): String? = when {
        attribute.dataType == TYPE_STRING -> pool[attribute.data]
        attribute.rawValue >= 0 -> pool[attribute.rawValue]
        else -> null
    }

    /** The package and version the root element's [attributes] declare. */
    private fun identityOf(pool: StringPool, attributes: List<Attribute>): ApkInfo {
        var packageName: String? = null
        var versionCode = 0L
        var versionCodeMajor = 0L
        var versionName: String? = null
        for (attribute in attributes) {
            when (attribute.resource) {
                ATTR_VERSION_CODE -> versionCode = attribute.data.toUnsigned()
                ATTR_VERSION_CODE_MAJOR -> versionCodeMajor = attribute.data.toUnsigned()
                ATTR_VERSION_NAME -> versionName = text(pool, attribute)
                else -> if (pool.matches(attribute.nameIndex, "package")) packageName = text(pool, attribute)
            }
        }
        return ApkInfo(
            packageName = packageName ?: throw IOException("Package name missing"),
            versionCode = (versionCodeMajor shl 32) or versionCode,
            versionName = versionName
        )
    }

    /**
     * The manifest's string table. Strings are decoded on demand, so a table that claims an
     * absurd number of entries costs nothing beyond the range check.
     */
    private class StringPool(
        private val xml: ByteBuffer,
        private val chunk: Int,
        private val headerSize: Int,
        chunkSize: Int
    ) {
        private val limit = chunk + chunkSize
        private val utf8 = xml.getInt(chunk + 16) and FLAG_UTF8 != 0
        private val stringsStart = xml.getInt(chunk + 20)
        private val count: Int

        init {
            if (headerSize < 28) throw IOException("String pool header too short")
            val declared = xml.getInt(chunk + 8)
            if (declared < 0 || declared > (chunkSize - headerSize) / 4 ||
                stringsStart < headerSize || stringsStart > chunkSize
            ) {
                throw IOException("String pool out of range")
            }
            count = declared
        }

        /**
         * The string at [index], or null when there is none there, or when it is longer than
         * [maxLength] bytes (UTF-8) or UTF-16 units.
         */
        operator fun get(index: Int, maxLength: Int = Int.MAX_VALUE): String? {
            val (at, length) = locate(index) ?: return null
            if (length > maxLength) return null
            return if (utf8) {
                if (length > limit - at) null else String(xml.array(), at, length, Charsets.UTF_8)
            } else {
                if (length > (limit - at) / 2) {
                    null
                } else {
                    String(xml.array(), at, length * 2, Charsets.UTF_16LE)
                }
            }
        }

        /**
         * Whether the string at [index] is [expected], an ASCII name: decided from the length
         * alone when the lengths differ, so that a long string costs nothing to rule out.
         */
        fun matches(index: Int, expected: String): Boolean {
            val (_, length) = locate(index) ?: return false
            return length == expected.length && get(index) == expected
        }

        /**
         * Where the string at [index] begins and how long it is, in bytes for UTF-8 and in
         * UTF-16 units otherwise; null when the index or the offset is out of range.
         */
        private fun locate(index: Int): Pair<Int, Int>? {
            if (index < 0 || index >= count) return null
            val offset = xml.getInt(chunk + headerSize + index * 4)
            if (offset < 0 || offset >= limit - chunk - stringsStart) return null
            var at = chunk + stringsStart + offset
            return if (utf8) {
                // Character count, then byte count; each takes two bytes when the high bit is set.
                at += if (xml.get(at).toInt() and 0x80 != 0) 2 else 1
                if (at + 2 > limit) return null
                var length = xml.get(at).toInt() and 0xff
                at += 1
                if (length and 0x80 != 0) {
                    length = ((length and 0x7f) shl 8) or (xml.get(at).toInt() and 0xff)
                    at += 1
                }
                at to length
            } else {
                if (at + 4 > limit) return null
                var length = xml.getShort(at).toInt() and 0xffff
                at += 2
                if (length and 0x8000 != 0) {
                    length = ((length and 0x7fff) shl 16) or (xml.getShort(at).toInt() and 0xffff)
                    at += 2
                }
                at to length
            }
        }
    }

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun Int.toUnsigned(): Long = toLong() and 0xffffffffL
}
