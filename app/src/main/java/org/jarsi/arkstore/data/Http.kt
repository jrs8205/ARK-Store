package org.jarsi.arkstore.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.jarsi.arkstore.BuildConfig

/**
 * A response other than the one asked for. [retryAfter] tells that the server said when to
 * come back, which is how GitHub asks a client that is going too fast to slow down.
 */
class HttpStatusException(val code: Int, val retryAfter: Boolean = false) :
    IOException("HTTP $code")

/** GitHub refused the request because the hourly quota of anonymous calls is used up. */
class RateLimitedException(val resetAtMillis: Long) : IOException("GitHub rate limit reached")

object Http {
    private const val TIMEOUT_MS = 20_000
    private const val USER_AGENT = "ARK-Store/${BuildConfig.VERSION_NAME}"
    private const val MAX_REDIRECTS = 3
    private val REDIRECTS = setOf(301, 302, 307, 308)

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

    /**
     * Fetches a GitHub API document, with the [token] the user has given, if any. Redirects,
     * as for a renamed repository, are followed by hand, so that the token goes to the API
     * alone, see [GitHubToken.authorization].
     */
    @Throws(IOException::class)
    fun getApi(url: String, token: String? = GitHubToken.get()): String {
        var address = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = open(address).apply {
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                GitHubToken.authorization(address, token)?.let { setRequestProperty("Authorization", it) }
            }
            try {
                val code = connection.responseCode
                if (code == 200) return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                if (code in REDIRECTS) {
                    val location = connection.getHeaderField("Location") ?: throw HttpStatusException(code)
                    address = URL(URL(address), location).toString()
                    return@repeat
                }
                if ((code == 403 || code == 429) &&
                    connection.getHeaderField("x-ratelimit-remaining") == "0"
                ) {
                    val reset = connection.getHeaderField("x-ratelimit-reset")?.toLongOrNull() ?: 0
                    throw RateLimitedException(reset * 1000)
                }
                throw HttpStatusException(code, connection.getHeaderField("retry-after") != null)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    /**
     * Fetches a plain text document that is not served by the GitHub API, unless it still has
     * the given [etag]. Returns the text, or null when it has not changed, with its ETag.
     */
    @Throws(IOException::class)
    fun getTextIfChanged(url: String, etag: String?): Pair<String?, String?> {
        val connection = open(url)
        if (etag != null) connection.setRequestProperty("If-None-Match", etag)
        try {
            return when (connection.responseCode) {
                200 -> connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } to
                    connection.getHeaderField("ETag")
                304 -> null to etag
                else -> throw HttpStatusException(connection.responseCode)
            }
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

    /** Fetches a small document, such as an icon, refusing one of more than [limit] bytes. */
    @Throws(IOException::class)
    fun getBytes(url: String, limit: Int): ByteArray {
        val connection = open(url)
        try {
            if (connection.responseCode != 200) throw HttpStatusException(connection.responseCode)
            if (connection.contentLengthLong > limit) throw IOException("Too large: $url")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > limit) throw IOException("Too large: $url")
                    output.write(buffer, 0, n)
                }
            }
            return output.toByteArray()
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
