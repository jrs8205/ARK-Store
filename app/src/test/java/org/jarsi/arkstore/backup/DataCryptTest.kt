package org.jarsi.arkstore.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.SecretKey
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DataCryptTest {

    private val password = "correct horse".toCharArray()
    private val chunk = 1024
    private val iterations = 1000

    private fun encrypt(plain: ByteArray, password: CharArray = this.password, salt: ByteArray = DataCrypt.newSalt()): ByteArray {
        val out = ByteArrayOutputStream()
        val key = DataCrypt.deriveKey(password, salt, iterations)
        DataCrypt.encrypt(out, key, salt, iterations, chunk).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun decrypt(cipher: ByteArray, password: CharArray = this.password): ByteArray {
        val keys = HashMap<String, SecretKey>()
        val keyFor: (ByteArray, Int) -> SecretKey = { salt, its ->
            keys.getOrPut(salt.joinToString(",") + ":" + its) { DataCrypt.deriveKey(password, salt, its) }
        }
        return DataCrypt.decrypt(ByteArrayInputStream(cipher), keyFor).use { it.readBytes() }
    }

    @Test
    fun whatIsEncryptedComesBackWhole() {
        for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, chunk * 2, chunk * 5 / 2)) {
            val plain = Random(size).nextBytes(size)
            val cipher = encrypt(plain)
            assertTrue("size $size", cipher.size > plain.size + DataCrypt.HEADER_BYTES)
            assertArrayEquals("size $size", plain, decrypt(cipher))
        }
    }

    @Test
    fun theSameDataIsNeverEncryptedTheSameWayTwice() {
        val plain = ByteArray(100) { 7 }
        val one = encrypt(plain)
        val two = encrypt(plain)
        assertEquals(one.size, two.size)
        assertTrue(!one.contentEquals(two))
    }

    @Test
    fun everyFileHasAKeyOfItsOwn() {
        // The files of one run share the password's key and salt; each is encrypted with a
        // key drawn from the file's own id, so that no nonce is ever used twice with one key.
        val salt = DataCrypt.newSalt()
        val master = DataCrypt.deriveKey(password, salt, iterations)
        val a = DataCrypt.readHeader(ByteArrayInputStream(encrypt(byteArrayOf(1), salt = salt)))
        val b = DataCrypt.readHeader(ByteArrayInputStream(encrypt(byteArrayOf(2), salt = salt)))
        assertEquals(DataCrypt.FILE_ID_BYTES, a.fileId.size)
        assertFalse(a.fileId.contentEquals(b.fileId))
        assertFalse(DataCrypt.fileKey(master, a.fileId).encoded.contentEquals(DataCrypt.fileKey(master, b.fileId).encoded))
        assertArrayEquals(DataCrypt.fileKey(master, a.fileId).encoded, DataCrypt.fileKey(master, a.fileId).encoded)
        assertFalse(DataCrypt.fileKey(master, a.fileId).encoded.contentEquals(master.encoded))
        assertThrows(IllegalArgumentException::class.java) { DataCrypt.fileKey(master, ByteArray(4)) }
        // Two files of one key and one plain text: the same place in each is sealed differently.
        val plain = ByteArray(chunk * 2) { 5 }
        val one = encrypt(plain, salt = salt)
        val two = encrypt(plain, salt = salt)
        val first = DataCrypt.HEADER_BYTES + 4
        assertFalse(one.copyOfRange(first, first + chunk).contentEquals(two.copyOfRange(first, first + chunk)))
    }

    @Test
    fun aWrongPasswordIsToldApartFromAnythingElse() {
        val cipher = encrypt(Random(1).nextBytes(chunk * 3))
        assertThrows(WrongPasswordException::class.java) { decrypt(cipher, "wrong".toCharArray()) }
        assertThrows(WrongPasswordException::class.java) { decrypt(cipher, CharArray(0)) }
    }

    @Test
    fun aFileCutShortOrAlteredIsRefused() {
        val plain = Random(2).nextBytes(chunk * 3)
        val cipher = encrypt(plain)
        // Cut within the last chunk, and cut just after a whole chunk: neither ends with the final chunk.
        assertThrows(IOException::class.java) { decrypt(cipher.copyOf(cipher.size - 1)) }
        val afterFirst = DataCrypt.HEADER_BYTES + 4 + chunk + DataCrypt.TAG_BYTES
        assertThrows(IOException::class.java) { decrypt(cipher.copyOf(afterFirst)) }
        // A byte flipped in the second chunk: not the password's fault.
        val altered = cipher.copyOf().also { it[afterFirst + 10] = (it[afterFirst + 10] + 1).toByte() }
        val error = assertThrows(IOException::class.java) { decrypt(altered) }
        assertTrue(error !is WrongPasswordException)
        // A chunk taken out of the middle, which would otherwise be a shorter whole file.
        val second = afterFirst + 4 + chunk + DataCrypt.TAG_BYTES
        val skipped = cipher.copyOfRange(0, afterFirst) + cipher.copyOfRange(second, cipher.size)
        assertThrows(IOException::class.java) { decrypt(skipped) }
        // Not a file of this kind at all.
        assertThrows(IOException::class.java) { decrypt(ByteArray(0)) }
        assertThrows(IOException::class.java) { decrypt("PK\u0003\u0004 nonsense".toByteArray()) }
        val wrongMagic = cipher.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrows(IOException::class.java) { decrypt(wrongMagic) }
    }

    @Test
    fun theStreamTellsWhenItHasReadTheLastChunk() {
        val plain = Random(3).nextBytes(chunk * 2)
        val cipher = encrypt(plain)
        val keyFor: (ByteArray, Int) -> SecretKey = { s, i -> DataCrypt.deriveKey(password, s, i) }
        val opened = DataCrypt.decrypt(ByteArrayInputStream(cipher), keyFor)
        assertFalse(opened.whole)
        val buffer = ByteArray(chunk * 2)
        var read = 0
        while (read < buffer.size) read += opened.read(buffer, read, buffer.size - read)
        // The data is all there, but the last chunk, which says so, has not been opened yet.
        assertArrayEquals(plain, buffer)
        assertFalse(opened.whole)
        assertEquals(-1, opened.read())
        assertTrue(opened.whole)
        // Without the last chunk the data is all there just the same, and the end is refused.
        val withoutEnd = cipher.copyOf(cipher.size - 4 - DataCrypt.TAG_BYTES)
        val cut = DataCrypt.decrypt(ByteArrayInputStream(withoutEnd), keyFor)
        read = 0
        while (read < buffer.size) read += cut.read(buffer, read, buffer.size - read)
        assertArrayEquals(plain, buffer)
        assertFalse(cut.whole)
        assertThrows(IOException::class.java) { cut.read() }
    }

    @Test
    fun theBoundTextIsReadBeforeTheDataAndVouchedForByTheFirstChunk() {
        val salt = DataCrypt.newSalt()
        val key = DataCrypt.deriveKey(password, salt, iterations)
        val out = ByteArrayOutputStream()
        val bound = DataBinding.text("org.example.app", listOf("b".repeat(64), "a".repeat(64)))
        DataCrypt.encrypt(out, key, salt, iterations, chunk, bound).use { it.write(ByteArray(chunk * 2) { 3 }) }
        val cipher = out.toByteArray()
        val opened = DataCrypt.decrypt(ByteArrayInputStream(cipher), { s, i -> DataCrypt.deriveKey(password, s, i) })
        assertEquals(bound, opened.bound)
        assertEquals(chunk * 2, opened.readBytes().size)
        // The text is in the header in the clear; one that was changed opens no chunk.
        val at = String(cipher, Charsets.ISO_8859_1).indexOf("org.example.app")
        val altered = cipher.copyOf().also { it[at] = 'x'.code.toByte() }
        assertThrows(IOException::class.java) {
            DataCrypt.decrypt(ByteArrayInputStream(altered), { s, i -> DataCrypt.deriveKey(password, s, i) }).use { it.readBytes() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            DataCrypt.encrypt(ByteArrayOutputStream(), key, salt, iterations, chunk, "x".repeat(5000))
        }
    }

    @Test
    fun theHeaderIsReadBackAsWritten() {
        val salt = DataCrypt.newSalt()
        val key = DataCrypt.deriveKey(password, salt, iterations)
        val out = ByteArrayOutputStream()
        DataCrypt.encrypt(out, key, salt, iterations, chunk).use { it.write(byteArrayOf(1, 2, 3)) }
        val header = DataCrypt.readHeader(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(salt, header.salt)
        assertEquals(iterations, header.iterations)
        assertEquals(chunk, header.chunkBytes)
        assertEquals(DataCrypt.FILE_ID_BYTES, header.fileId.size)
        assertEquals("", header.bound)
        assertEquals(DataCrypt.SALT_BYTES, salt.size)
    }

    @Test
    fun aHeaderThatAsksTooMuchIsRefused() {
        val salt = DataCrypt.newSalt()
        val key = DataCrypt.deriveKey(password, salt, iterations)
        assertThrows(IllegalArgumentException::class.java) {
            DataCrypt.encrypt(ByteArrayOutputStream(), key, salt, DataCrypt.MAX_ITERATIONS + 1, chunk)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DataCrypt.encrypt(ByteArrayOutputStream(), key, salt, iterations, DataCrypt.MAX_CHUNK_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DataCrypt.encrypt(ByteArrayOutputStream(), key, salt, 0, chunk)
        }
    }

    @Test
    fun theNamesOfTheDataFilesTellWhetherTheyAreEncrypted() {
        assertTrue(DataCrypt.isEncrypted("data.tar.gz.aes"))
        assertTrue(!DataCrypt.isEncrypted("data.tar.gz"))
        assertEquals("data.tar.gz.aes", DataCrypt.fileName(AppBackup.DATA_USER, encrypted = true))
        assertEquals("data.tar.gz", DataCrypt.fileName(AppBackup.DATA_USER, encrypted = false))
        assertEquals("data_de.tar.gz.aes", DataCrypt.fileName(AppBackup.DATA_USER_DE, encrypted = true))
    }
}
