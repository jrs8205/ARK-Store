package org.jarsi.arkstore.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The first chunk did not open with the key: the password is not the one the file was made with. */
class WrongPasswordException : IOException("The password is not the one the data was encrypted with")

/**
 * Encrypts an app's data archive with a password, chunk by chunk, so that a file of any size
 * is written and read as a stream: AES-256-GCM over chunks of at most [MAX_CHUNK_BYTES], the
 * key derived from the password with PBKDF2-HMAC-SHA256. Each chunk is bound to its place by
 * its number, and the last one is marked as the last, so that a file cut short, a chunk
 * dropped or one moved is refused as a whole.
 *
 * The file begins with a header: [MAGIC], a version byte, the salt, the iterations, four
 * random bytes that begin every nonce of the file, and the chunk size. Then come the chunks,
 * each its ciphertext's length (with the tag) and the ciphertext. The nonce of chunk n is
 * the file's four bytes followed by n; the chunk's number and whether it is the last are
 * the associated data.
 */
object DataCrypt {
    private val MAGIC = "ARKSTORE-DATA".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    const val SALT_BYTES = 16
    const val TAG_BYTES = 16
    const val ITERATIONS = 300_000
    const val MAX_ITERATIONS = 5_000_000
    const val CHUNK_BYTES = 1024 * 1024
    const val MAX_CHUNK_BYTES = 16 * 1024 * 1024
    private const val KEY_BITS = 256
    private const val NONCE_PREFIX_BYTES = 4
    private const val NONCE_BYTES = 12

    /** The header's length: the magic, the version, the salt, the iterations, the nonce's start and the chunk size. */
    val HEADER_BYTES: Int = MAGIC.size + 1 + SALT_BYTES + 4 + NONCE_PREFIX_BYTES + 4

    private const val SUFFIX = ".aes"
    private const val ARCHIVE = ".tar.gz"

    /** The name of the data file of [part] ([AppBackup.DATA_USER] or [AppBackup.DATA_USER_DE]), encrypted or not. */
    fun fileName(part: String, encrypted: Boolean): String = part + ARCHIVE + if (encrypted) SUFFIX else ""

    fun isEncrypted(name: String): Boolean = name.endsWith(SUFFIX)

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /** The key [password] gives with [salt] over [iterations]; slow by design, so derived once per run. */
    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        require(iterations in 1..MAX_ITERATIONS) { "Not a number of iterations" }
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** What the header of an encrypted file says, which is what a key is derived from. */
    class Header(val salt: ByteArray, val iterations: Int, val noncePrefix: ByteArray, val chunkBytes: Int)

    /**
     * A stream that encrypts what is written to it into [out] with [key], made of [salt] over
     * [iterations], in chunks of [chunkBytes]. Closing it writes the last chunk and closes [out].
     */
    fun encrypt(
        out: OutputStream,
        key: SecretKey,
        salt: ByteArray,
        iterations: Int = ITERATIONS,
        chunkBytes: Int = CHUNK_BYTES
    ): OutputStream {
        require(salt.size == SALT_BYTES) { "Not a salt" }
        require(iterations in 1..MAX_ITERATIONS) { "Not a number of iterations" }
        require(chunkBytes in 1..MAX_CHUNK_BYTES) { "Not a chunk size" }
        val prefix = ByteArray(NONCE_PREFIX_BYTES).also { SecureRandom().nextBytes(it) }
        val data = DataOutputStream(out)
        data.write(MAGIC)
        data.writeByte(VERSION.toInt())
        data.write(salt)
        data.writeInt(iterations)
        data.write(prefix)
        data.writeInt(chunkBytes)
        return Encrypting(data, key, prefix, chunkBytes)
    }

    /** The header of the encrypted file [input] begins with; the stream is left after it. */
    @Throws(IOException::class)
    fun readHeader(input: InputStream): Header {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            data.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("Not an encrypted data file")
            val version = data.readByte()
            if (version != VERSION) throw IOException("An encrypted data file of a newer kind: version $version")
            val salt = ByteArray(SALT_BYTES).also { data.readFully(it) }
            val iterations = data.readInt()
            if (iterations !in 1..MAX_ITERATIONS) throw IOException("Not a number of iterations: $iterations")
            val prefix = ByteArray(NONCE_PREFIX_BYTES).also { data.readFully(it) }
            val chunkBytes = data.readInt()
            if (chunkBytes !in 1..MAX_CHUNK_BYTES) throw IOException("Not a chunk size: $chunkBytes")
            return Header(salt, iterations, prefix, chunkBytes)
        } catch (e: EOFException) {
            throw IOException("Not an encrypted data file: it ends within its header", e)
        }
    }

    /**
     * A stream that decrypts the encrypted file [input] with the key [keyFor] gives for the
     * file's salt and iterations. Throws [WrongPasswordException] when the first chunk does
     * not open, and an [IOException] when a later one does not, or the file is cut short.
     */
    @Throws(IOException::class)
    fun decrypt(input: InputStream, keyFor: (salt: ByteArray, iterations: Int) -> SecretKey): InputStream {
        val header = readHeader(input)
        return Decrypting(DataInputStream(input), keyFor(header.salt, header.iterations), header)
    }

    private fun cipher(mode: Int, key: SecretKey, prefix: ByteArray, counter: Long, last: Boolean): Cipher {
        val nonce = ByteBuffer.allocate(NONCE_BYTES).put(prefix).putLong(counter).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(ByteBuffer.allocate(9).putLong(counter).put(if (last) 1 else 0).array())
        return cipher
    }

    private class Encrypting(
        private val out: DataOutputStream,
        private val key: SecretKey,
        private val prefix: ByteArray,
        chunkBytes: Int
    ) : OutputStream() {
        private val buffer = ByteArray(chunkBytes)
        private var count = 0
        private var counter = 0L
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "Closed" }
            var from = off
            var left = len
            while (left > 0) {
                val n = minOf(left, buffer.size - count)
                System.arraycopy(b, from, buffer, count, n)
                count += n
                from += n
                left -= n
                if (count == buffer.size) flushChunk(last = false)
            }
        }

        private fun flushChunk(last: Boolean) {
            val sealed = try {
                cipher(Cipher.ENCRYPT_MODE, key, prefix, counter, last).doFinal(buffer, 0, count)
            } catch (e: GeneralSecurityException) {
                throw IOException("Could not encrypt", e)
            }
            out.writeInt(sealed.size)
            out.write(sealed)
            counter++
            count = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                // The last chunk, empty if need be, says the file is whole.
                flushChunk(last = true)
                out.flush()
            } finally {
                out.close()
            }
        }
    }

    private class Decrypting(
        private val input: DataInputStream,
        private val key: SecretKey,
        private val header: Header
    ) : InputStream() {
        private var chunk = ByteArray(0)
        private var position = 0
        private var counter = 0L
        private var ended = false
        private val sealed = ByteArray(header.chunkBytes + TAG_BYTES)

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position == chunk.size) {
                if (ended) return -1
                nextChunk()
            }
            val n = minOf(len, chunk.size - position)
            System.arraycopy(chunk, position, b, off, n)
            position += n
            return n
        }

        private fun nextChunk() {
            val size = try {
                input.readInt()
            } catch (e: EOFException) {
                throw IOException("The encrypted data is cut short: its last chunk is missing", e)
            }
            if (size < TAG_BYTES || size > sealed.size) throw IOException("Not a chunk of this file: $size bytes")
            try {
                input.readFully(sealed, 0, size)
            } catch (e: EOFException) {
                throw IOException("The encrypted data is cut short within a chunk", e)
            }
            // Which of the two it is, the last chunk or another, is not told in the clear: both
            // are tried, and the one that opens is the one it is.
            chunk = open(size, last = false) ?: open(size, last = true) ?: throw when (counter) {
                0L -> WrongPasswordException()
                else -> IOException("The encrypted data has been altered at chunk $counter")
            }
            position = 0
            counter++
        }

        private fun open(size: Int, last: Boolean): ByteArray? = try {
            cipher(Cipher.DECRYPT_MODE, key, header.noncePrefix, counter, last).doFinal(sealed, 0, size)
                .also { if (last) ended = true }
        } catch (_: AEADBadTagException) {
            null
        } catch (e: GeneralSecurityException) {
            throw IOException("Could not decrypt", e)
        }

        override fun close() = input.close()
    }
}
