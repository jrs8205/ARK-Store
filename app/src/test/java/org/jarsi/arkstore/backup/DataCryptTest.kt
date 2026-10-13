package org.jarsi.arkstore.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.SecretKey
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DataCryptTest {

    private val password = "correct horse".toCharArray()
    private val chunk = 1024
    private val iterations = 1000

    private fun encrypt(plain: ByteArray, password: CharArray = this.password): ByteArray {
        val out = ByteArrayOutputStream()
        val salt = DataCrypt.newSalt()
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
    fun theHeaderIsReadBackAsWritten() {
        val salt = DataCrypt.newSalt()
        val key = DataCrypt.deriveKey(password, salt, iterations)
        val out = ByteArrayOutputStream()
        DataCrypt.encrypt(out, key, salt, iterations, chunk).use { it.write(byteArrayOf(1, 2, 3)) }
        val header = DataCrypt.readHeader(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(salt, header.salt)
        assertEquals(iterations, header.iterations)
        assertEquals(chunk, header.chunkBytes)
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
