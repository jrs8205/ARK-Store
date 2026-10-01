package org.jarsi.arkstore.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.jarsi.arkstore.BuildConfig

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** GitHub refused the request because the hourly quota of anonymous calls is used up. */
class RateLimitedException(val resetAtMillis: Long) : IOException("GitHub rate limit reached")

object Http {
    private const val TIMEOUT_MS = 20_000
    private const val USER_AGENT = "ARK-Store/${BuildConfig.VERSION_NAME}"

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

    /** Fetches a GitHub API document. */
    @Throws(IOException::class)
    fun getApi(url: String): String {
        val connection = open(url).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = connection.responseCode
            if (code == 200) return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if ((code == 403 || code == 429) &&
                connection.getHeaderField("x-ratelimit-remaining") == "0"
            ) {
                val reset = connection.getHeaderField("x-ratelimit-reset")?.toLongOrNull() ?: 0
                throw RateLimitedException(reset * 1000)
            }
            throw HttpStatusException(code)
        } finally {
            connection.disconnect()
        }
    }

    /** Reads [length] bytes starting at [start] with a range request. */
    @Throws(IOException::class)
    fun readRange(url: String, start: Long, length: Int): ByteArray {
        val connection = open(url).apply {
            setRequestProperty("Range", "bytes=$start-${start + length - 1}")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            if (connection.responseCode != 206) throw HttpStatusException(connection.responseCode)
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size != length) throw IOException("Short range response")
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads [url] into [target], reporting bytes read and the total (or -1 if unknown). */
    @Throws(IOException::class)
    fun download(url: String, target: File, onProgress: (read: Long, total: Long) -> Unit) {
        val connection = open(url).apply { setRequestProperty("Accept-Encoding", "identity") }
        try {
            if (connection.responseCode != 200) throw HttpStatusException(connection.responseCode)
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
