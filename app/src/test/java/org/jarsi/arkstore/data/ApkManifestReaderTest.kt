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
