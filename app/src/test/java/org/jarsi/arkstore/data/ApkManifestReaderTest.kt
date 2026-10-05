package org.jarsi.arkstore.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ApkManifestReaderTest {

    private fun source(bytes: ByteArray) = RangeSource { start, length ->
        bytes.copyOfRange(start.toInt(), start.toInt() + length)
    }

    private fun read(bytes: ByteArray): ApkInfo = ApkManifestReader.read(bytes.size.toLong(), source(bytes))

    private fun zipWithManifest(manifest: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun chunk(kind: Int, header: ByteArray, body: ByteArray): ByteArray =
        le(8).putShort(kind.toShort()).putShort((8 + header.size).toShort())
            .putInt(8 + header.size + body.size).array() + header + body

    /** A UTF-16 string pool of [strings]. */
    private fun stringPool(strings: List<String>): ByteArray {
        val offsets = le(4 * strings.size)
        val data = ByteArrayOutputStream()
        for (text in strings) {
            offsets.putInt(data.size())
            data.write(le(2).putShort(text.length.toShort()).array())
            data.write(text.toByteArray(Charsets.UTF_16LE))
            data.write(byteArrayOf(0, 0))
        }
        val header = le(20).putInt(strings.size).putInt(0).putInt(0).putInt(28 + 4 * strings.size).putInt(0)
        return chunk(0x0001, header.array(), offsets.array() + data.toByteArray())
    }

    /** A start element named by string [name] with attributes (name index, type, data). */
    private fun element(name: Int, attributes: List<Triple<Int, Int, Int>>): ByteArray {
        val body = le(20 + 20 * attributes.size)
            .putInt(-1).putInt(name).putShort(20).putShort(20).putShort(attributes.size.toShort())
            .putShort(0).putShort(0).putShort(0)
        for ((attribute, type, data) in attributes) {
            body.putInt(-1).putInt(attribute).putInt(if (type == 0x03) data else -1)
                .putShort(8).put(0).put(type.toByte()).putInt(data)
        }
        return chunk(0x0102, le(8).putInt(1).putInt(-1).array(), body.array())
    }

    /**
     * A binary manifest of org.example, version 3, with an application element and, when
     * [minSdk] or [codename] is given, a uses-sdk element naming the lowest Android before
     * the application, or after it with [sdkLast].
     */
    private fun manifest(minSdk: Int? = null, codename: String? = null, sdkLast: Boolean = false): ByteArray {
        val strings = listOf(
            "versionCode", "package", "manifest", "org.example", "minSdkVersion", "uses-sdk",
            "application", codename.orEmpty()
        )
        val resourceMap = chunk(
            0x0180, ByteArray(0),
            le(32).putInt(0x0101021B).putInt(0).putInt(0).putInt(0).putInt(0x0101020C).putInt(0).putInt(0).putInt(0).array()
        )
        val root = element(2, listOf(Triple(0, 0x10, 3), Triple(1, 0x03, 3)))
        val usesSdk = when {
            codename != null -> element(5, listOf(Triple(4, 0x03, 7)))
            minSdk != null -> element(5, listOf(Triple(4, 0x10, minSdk)))
            else -> ByteArray(0)
        }
        val application = element(6, emptyList())
        val elements = if (sdkLast) application + usesSdk else usesSdk + application
        return chunk(0x0003, ByteArray(0), stringPool(strings) + resourceMap + root + elements)
    }

    @Test
    fun readsTheLowestAndroidFromUsesSdk() {
        val info = read(zipWithManifest(manifest(minSdk = 26)))
        assertEquals("org.example", info.packageName)
        assertEquals(3L, info.versionCode)
        assertEquals(26, info.minSdk)
        assertNull(info.minSdkCodename)
    }

    @Test
    fun lowestAndroidIsReadAfterTheApplicationToo() {
        assertEquals(35, read(zipWithManifest(manifest(minSdk = 35, sdkLast = true))).minSdk)
    }

    @Test
    fun manifestWithoutUsesSdkRunsOnEveryAndroid() {
        assertEquals(1, read(zipWithManifest(manifest())).minSdk)
    }

    @Test
    fun codenameOfAPreviewAndroidIsKept() {
        val info = read(zipWithManifest(manifest(codename = "Baklava")))
        assertNull(info.minSdk)
        assertEquals("Baklava", info.minSdkCodename)
    }

    @Test
    fun elementsAfterTheRootAreNotDecodedForNothing() {
        // A hostile manifest: tens of thousands of elements and attributes after the root,
        // every one named by a string of tens of thousands of characters. Decoding each
        // would cost gigabytes; only what the reader looks for is decoded.
        val big = "x".repeat(30_000)
        val strings = listOf("versionCode", "package", "manifest", "org.example", big)
        val resourceMap = chunk(
            0x0180, ByteArray(0), le(20).putInt(0x0101021B).putInt(0).putInt(0).putInt(0).putInt(0).array()
        )
        val root = element(2, listOf(Triple(0, 0x10, 3), Triple(1, 0x03, 3)))
        val filler = element(4, listOf(Triple(4, 0x03, 4)))
        val body = ByteArrayOutputStream()
        body.write(stringPool(strings))
        body.write(resourceMap)
        body.write(root)
        repeat(40_000) { body.write(filler) }
        val started = System.nanoTime()
        val info = ApkManifestReader.parseManifest(chunk(0x0003, ByteArray(0), body.toByteArray()))
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals("org.example", info.packageName)
        assertEquals(1, info.minSdk)
        assertTrue("took $millis ms", millis < 1000)
    }

    /** A manifest whose string pool claims [stringCount] strings but holds none. */
    private fun manifestWithStringCount(stringCount: Int): ByteArray {
        val pool = le(28).putShort(0x0001).putShort(28).putInt(28)
            .putInt(stringCount).putInt(0).putInt(0).putInt(28).putInt(0)
        val element = le(36).putShort(0x0102).putShort(16).putInt(36)
            .putInt(1).putInt(-1) // line, comment
            .putInt(-1).putInt(0) // namespace, name
            .putShort(20).putShort(20).putShort(0).putShort(0).putShort(0).putShort(0)
        val header = le(8).putShort(0x0003).putShort(8).putInt(8 + 28 + 36)
        return header.array() + pool.array() + element.array()
    }

    @Test
    fun rejectsFilesThatAreNotZips() {
        assertThrows(IOException::class.java) { read(ByteArray(0)) }
        assertThrows(IOException::class.java) { read(ByteArray(10)) }
        assertThrows(IOException::class.java) { read(ByteArray(4096)) }
    }

    @Test
    fun rejectsZipWithoutManifest() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(16))
            zip.closeEntry()
        }
        assertThrows(IOException::class.java) { read(out.toByteArray()) }
    }

    @Test
    fun rejectsManifestThatIsNotBinaryXml() {
        assertThrows(IOException::class.java) { read(zipWithManifest(ByteArray(0))) }
        assertThrows(IOException::class.java) { read(zipWithManifest("<manifest/>".toByteArray())) }
    }

    @Test
    fun hugeStringCountDoesNotAllocate() {
        for (count in listOf(Int.MAX_VALUE, 1 shl 28, -1, 1)) {
            assertThrows(IOException::class.java) {
                ApkManifestReader.parseManifest(manifestWithStringCount(count))
            }
        }
    }

    @Test
    fun directoryPointingOutsideTheFileIsRejected() {
        val zip = zipWithManifest(ByteArray(64))
        val buffer = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = zip.size - 22
        for (value in listOf(-1, Int.MAX_VALUE, zip.size)) {
            buffer.putInt(eocd + 12, value)
            assertThrows(IOException::class.java) { read(zip) }
            buffer.putInt(eocd + 12, 0).putInt(eocd + 16, value)
            assertThrows(IOException::class.java) { read(zip) }
        }
    }

    @Test
    fun corruptedInputOnlyEverFailsWithIoException() {
        val random = Random(20261001)
        val base = zipWithManifest(manifestWithStringCount(0))
        repeat(20_000) {
            val bytes = base.copyOf()
            repeat(1 + random.nextInt(6)) {
                bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
            }
            try {
                read(bytes)
            } catch (_: IOException) {
                // The only acceptable failure.
            }
        }
    }

    /**
     * Reads real APK files through the range-based reader, then again with random damage. The
     * files are given as a colon-separated list in the ARKSTORE_TEST_APKS environment variable;
     * without it the test is skipped.
     */
    @Test
    fun readsRealApks() {
        val paths = System.getenv("ARKSTORE_TEST_APKS")
        assumeTrue(paths != null)
        val random = Random(49)
        paths!!.split(":").forEach { path ->
            val file = File(path)
            var requested = 0L
            RandomAccessFile(file, "r").use { raf ->
                val intact = RangeSource { start, length ->
                    requested += length
                    ByteArray(length).also { raf.seek(start); raf.readFully(it) }
                }
                val info = ApkManifestReader.read(file.length(), intact)
                println("APKINFO ${file.name}: $info (read $requested of ${file.length()} bytes)")
                assertTrue(info.packageName.contains('.'))
                assertTrue(info.versionCode > 0)
                // The point of the reader is to avoid downloading the whole file.
                assertTrue(requested < file.length())

                repeat(300) {
                    val damaged = RangeSource { start, length ->
                        intact.read(start, length).also { bytes ->
                            repeat(1 + random.nextInt(8)) {
                                bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
                            }
                        }
                    }
                    try {
                        ApkManifestReader.read(file.length(), damaged)
                    } catch (_: IOException) {
                        // The only acceptable failure.
                    }
                }
            }
        }
    }
}
