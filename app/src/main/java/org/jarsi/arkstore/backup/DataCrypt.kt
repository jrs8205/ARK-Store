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
import javax.crypto.Mac
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
 * The file begins with a header: [MAGIC], a version byte, the salt, the iterations, the
 * file's own id, the chunk size, and a text the file is bound to (the package and the
 * signers of the app whose data it is, see [DataBinding]). Then come the chunks, each its
 * ciphertext's length (with the tag) and the ciphertext. The file is encrypted with a key of
 * its own, drawn from the password's key and the file's id, so that the files of one run,
 * which share the password's key, share no key with which a nonce could be used twice. The
 * nonce of chunk n is the first bytes of the id followed by n; the chunk's number, whether it
 * is the last, and the bound text are the associated data, so that a bound text changed in
 * the header opens no chunk.
 */
object DataCrypt {
    private val MAGIC = "ARKSTORE-DATA".toByteArray(Charsets.US_ASCII)
    private val FILE_KEY_LABEL = "ARKSTORE-FILE-KEY".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    const val SALT_BYTES = 16
    const val TAG_BYTES = 16
    const val ITERATIONS = 300_000
    const val MAX_ITERATIONS = 5_000_000
    const val CHUNK_BYTES = 1024 * 1024
    const val MAX_CHUNK_BYTES = 16 * 1024 * 1024
    const val FILE_ID_BYTES = 16
    private const val KEY_BITS = 256
    private const val NONCE_PREFIX_BYTES = 4
    private const val NONCE_BYTES = 12
    private const val MAX_BOUND_BYTES = 4096

    /** The header's length with an empty bound text: the magic, the version, the salt, the iterations, the file's id, the chunk size and the text's length. */
    val HEADER_BYTES: Int = MAGIC.size + 1 + SALT_BYTES + 4 + FILE_ID_BYTES + 4 + 4

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

    /** The key of the file [fileId], drawn from [key], the password's: HMAC-SHA256 of the file's id under a label. */
    internal fun fileKey(key: SecretKey, fileId: ByteArray): SecretKey {
        require(fileId.size == FILE_ID_BYTES) { "Not a file id" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.encoded, "HmacSHA256"))
        mac.update(FILE_KEY_LABEL)
        mac.update(fileId)
        return SecretKeySpec(mac.doFinal(), "AES")
    }

    /**
     * What the header of an encrypted file says, which is what a key is derived from, and the
     * text the file is [bound] to, which is not to be trusted until a chunk has opened.
     */
    class Header(val salt: ByteArray, val iterations: Int, val fileId: ByteArray, val chunkBytes: Int, val bound: String)

    /**
     * A stream that encrypts what is written to it into [out] with a key of its own drawn
     * from [key], made of [salt] over [iterations], in chunks of [chunkBytes], [bound] to a
     * text that every chunk is then authenticated with. Closing it writes the last chunk and
     * closes [out].
     */
    fun encrypt(
        out: OutputStream,
        key: SecretKey,
        salt: ByteArray,
        iterations: Int = ITERATIONS,
        chunkBytes: Int = CHUNK_BYTES,
        bound: String = ""
    ): OutputStream {
        require(salt.size == SALT_BYTES) { "Not a salt" }
        require(iterations in 1..MAX_ITERATIONS) { "Not a number of iterations" }
        require(chunkBytes in 1..MAX_CHUNK_BYTES) { "Not a chunk size" }
        val boundBytes = bound.toByteArray(Charsets.UTF_8)
        require(boundBytes.size <= MAX_BOUND_BYTES) { "The bound text is too long" }
        val fileId = ByteArray(FILE_ID_BYTES).also { SecureRandom().nextBytes(it) }
        val data = DataOutputStream(out)
        data.write(MAGIC)
        data.writeByte(VERSION.toInt())
        data.write(salt)
        data.writeInt(iterations)
        data.write(fileId)
        data.writeInt(chunkBytes)
        data.writeInt(boundBytes.size)
        data.write(boundBytes)
        return Encrypting(data, fileKey(key, fileId), fileId, chunkBytes, boundBytes)
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
            val fileId = ByteArray(FILE_ID_BYTES).also { data.readFully(it) }
            val chunkBytes = data.readInt()
            if (chunkBytes !in 1..MAX_CHUNK_BYTES) throw IOException("Not a chunk size: $chunkBytes")
            val boundLength = data.readInt()
            if (boundLength !in 0..MAX_BOUND_BYTES) throw IOException("Not a length of a bound text: $boundLength")
            val bound = ByteArray(boundLength).also { data.readFully(it) }
            return Header(salt, iterations, fileId, chunkBytes, String(bound, Charsets.UTF_8))
        } catch (e: EOFException) {
            throw IOException("Not an encrypted data file: it ends within its header", e)
        }
    }

    /**
     * A stream that decrypts the encrypted file [input] with the key [keyFor] gives for the
     * file's salt and iterations; its [Decrypted.bound] is the text the file says it is bound
     * to, which the first chunk to open vouches for. Throws [WrongPasswordException] when the
     * first chunk does not open (the password is wrong, or the header was altered), and an
     * [IOException] when a later one does not, or the file is cut short.
     */
    @Throws(IOException::class)
    fun decrypt(input: InputStream, keyFor: (salt: ByteArray, iterations: Int) -> SecretKey): Decrypted {
        val header = readHeader(input)
        return Decrypting(DataInputStream(input), fileKey(keyFor(header.salt, header.iterations), header.fileId), header)
    }

    /** The plain data of an encrypted file, and the text the file is bound to. */
    abstract class Decrypted : InputStream() {
        abstract val bound: String

        /** Whether the last chunk has been opened: the file was read to its end and found whole. */
        abstract val whole: Boolean
    }

    private fun cipher(mode: Int, key: SecretKey, fileId: ByteArray, counter: Long, last: Boolean, bound: ByteArray): Cipher {
        val nonce = ByteBuffer.allocate(NONCE_BYTES).put(fileId, 0, NONCE_PREFIX_BYTES).putLong(counter).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(ByteBuffer.allocate(9 + bound.size).putLong(counter).put(if (last) 1 else 0).put(bound).array())
        return cipher
    }

    private class Encrypting(
        private val out: DataOutputStream,
        private val key: SecretKey,
        private val fileId: ByteArray,
        chunkBytes: Int,
        private val bound: ByteArray
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
                cipher(Cipher.ENCRYPT_MODE, key, fileId, counter, last, bound).doFinal(buffer, 0, count)
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
    ) : Decrypted() {
        override val bound: String get() = header.bound
        override val whole: Boolean get() = ended
        private val boundBytes = header.bound.toByteArray(Charsets.UTF_8)
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
            cipher(Cipher.DECRYPT_MODE, key, header.fileId, counter, last, boundBytes).doFinal(sealed, 0, size)
                .also { if (last) ended = true }
        } catch (_: AEADBadTagException) {
            null
        } catch (e: GeneralSecurityException) {
            throw IOException("Could not decrypt", e)
        }

        override fun close() = input.close()
    }
}
