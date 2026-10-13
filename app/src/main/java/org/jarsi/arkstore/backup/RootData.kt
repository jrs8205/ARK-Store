package org.jarsi.arkstore.backup

import android.os.Process
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jarsi.arkstore.install.Root

/**
 * The commands by which an app's own data is taken and put back as root: text alone, made
 * of constants, numbers and package names that have passed [AppBackup.isPackageName], so
 * that nothing read from a backup can reach a command line. An app's data lives in two
 * places, see [AppBackup.DATA_PARTS]: `/data/user/N/<package>` (credential-encrypted) and
 * `/data/user_de/N/<package>` (device-encrypted), where N is the user the store runs as.
 * The caches and the native libraries are left out: the app makes them again.
 */
internal object RootDataCommands {
    /** Where root apps keep a busybox of their own, for a device whose toybox has no `tar`. */
    private val BUSYBOXES = listOf("/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox")

    private val EXCLUDED = listOf("cache", "code_cache", "lib")

    /** The folder of the data [part] of every app of user [userId]. */
    fun userDir(part: String, userId: Int): String {
        require(userId >= 0) { "Not a user" }
        return when (part) {
            AppBackup.DATA_USER -> "/data/user/$userId"
            AppBackup.DATA_USER_DE -> "/data/user_de/$userId"
            else -> throw IllegalArgumentException("Not a part of the data")
        }
    }

    /** Prints the command that is `tar` here: toybox's, or a root app's busybox; nothing when there is none. */
    fun findTar(): String =
        "command -v tar || for b in ${BUSYBOXES.joinToString(" ")}; do [ -x \$b ] && echo \"\$b tar\" && break; done"

    /** The `tar` command [found] printed, or null when it printed nothing usable. */
    fun tarFrom(found: String): String? {
        val line = found.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val ok = line == "tar" || (line.startsWith("/") && line.split(' ').let { parts ->
            parts.size in 1..2 && parts.all { TOOL_PATH.matches(it) } && (parts.size == 1 || parts[1] == "tar")
        })
        return if (ok) line else null
    }

    /** The size of every part of the data of [packages], in kilobytes, one line each: "123\t/data/user/0/org.example". */
    fun sizes(userId: Int, packages: List<String>): String {
        require(packages.isNotEmpty()) { "No packages" }
        val dirs = AppBackup.DATA_PARTS.flatMap { part -> packages.map { "${userDir(part, userId)}/${checked(it)}" } }
        return "du -s -k ${dirs.joinToString(" ")} 2>/dev/null"
    }

    /** The bytes [sizes] printed, by package: both parts added up. A line that is not a size is passed over. */
    fun sizesFrom(output: String, userId: Int): Map<String, Long> {
        val sizes = HashMap<String, Long>()
        val roots = AppBackup.DATA_PARTS.map { userDir(it, userId) + "/" }
        for (line in output.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            val kb = line.substring(0, tab).trim().toLongOrNull() ?: continue
            val path = line.substring(tab + 1).trim()
            val root = roots.firstOrNull { path.startsWith(it) } ?: continue
            val name = path.removePrefix(root)
            if (!AppBackup.isPackageName(name)) continue
            sizes[name] = (sizes[name] ?: 0L) + kb * 1024
        }
        return sizes
    }

    /**
     * Prints, for each of [packages], the parts that hold anything worth taking, one line each:
     * "part package". A folder holding nothing but the caches and the libraries is not a part.
     */
    fun parts(userId: Int, packages: List<String>): String {
        require(packages.isNotEmpty()) { "No packages" }
        val lines = AppBackup.DATA_PARTS.flatMap { part ->
            packages.map { pkg ->
                val dir = "${userDir(part, userId)}/${checked(pkg)}"
                val excluded = EXCLUDED.joinToString(" ") { "! -name $it" }
                "[ -d $dir ] && [ -n \"\$(find $dir -mindepth 1 -maxdepth 1 $excluded 2>/dev/null | head -n 1)\" ] && echo \"$part $pkg\""
            }
        }
        return lines.joinToString("; ") + "; true"
    }

    /** The parts [parts] printed, by package, in the order of [AppBackup.DATA_PARTS]. */
    fun partsFrom(output: String): Map<String, List<String>> {
        val found = HashMap<String, MutableList<String>>()
        for (line in output.lineSequence()) {
            val words = line.trim().split(' ')
            if (words.size != 2 || words[0] !in AppBackup.DATA_PARTS || !AppBackup.isPackageName(words[1])) continue
            val list = found.getOrPut(words[1]) { ArrayList(2) }
            if (words[0] !in list) list += words[0]
        }
        return found.mapValues { (_, parts) -> parts.sortedBy { AppBackup.DATA_PARTS.indexOf(it) } }
    }

    /** Writes the data [part] of [packageName] as a tar to the output, the caches and libraries left out. */
    fun archive(tar: String, part: String, userId: Int, packageName: String): String {
        val pkg = checked(packageName)
        val excludes = EXCLUDED.joinToString(" ") { "--exclude=$pkg/$it" }
        return "$tar -c -f - -C ${userDir(part, userId)} $excludes $pkg"
    }

    /** The folder a restore of [part] is unpacked into first: beside the apps' folders, so a move into place is a rename. */
    fun staging(part: String, userId: Int, number: Int): String = "${userDir(part, userId)}/.arkstore-restore-$number"

    /** Makes [staging] afresh and unpacks the tar on the input into it. */
    fun extract(tar: String, staging: String): String =
        "rm -rf $staging && mkdir $staging && chmod 700 $staging && $tar -x -p -f - -C $staging"

    /** Prints what [staging] holds at its top, one line each. */
    fun listStaging(staging: String): String = "find $staging -mindepth 1 -maxdepth 1"

    /**
     * Prints where every link under [staging] leads, as the device itself resolves it, one
     * line each; a line of "?" for one the device could not resolve.
     */
    fun listLinks(staging: String): String =
        "find $staging -type l -exec sh -c 'readlink -f \"\$1\" || echo ?' - {} \\;"

    /**
     * Whether every link [listLinks] printed leads into the folder of [packageName] under
     * [staging], where a link checked against the archive alone could still have been led
     * astray by another link. One that could not be resolved does not.
     */
    fun linksStayIn(output: String, staging: String, packageName: String): Boolean {
        val folder = "$staging/${checked(packageName)}"
        return output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .all { it == folder || it.startsWith("$folder/") }
    }

    /** Whether what [listStaging] printed is nothing but the folder of [packageName]. */
    fun stagingHoldsOnly(output: String, staging: String, packageName: String): Boolean {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return lines == listOf("$staging/${checked(packageName)}")
    }

    /**
     * Puts the unpacked data of [packageName] in [staging] in the place of the app's data,
     * owned by the app ([uid]) and labelled as the system labels an app's data, keeping the
     * app's link to its native libraries. The old data is kept until the new is in place.
     */
    fun swap(part: String, userId: Int, packageName: String, staging: String, uid: Int, number: Int): String {
        require(uid >= 0) { "Not a uid" }
        val pkg = checked(packageName)
        val target = "${userDir(part, userId)}/$pkg"
        val fresh = "$staging/$pkg"
        val old = "${userDir(part, userId)}/.arkstore-old-$number"
        return listOf(
            "rm -rf $old",
            "{ [ ! -e $target ] || mv $target $old; }",
            "{ mv $fresh $target || { mv $old $target; false; }; }",
            "{ [ ! -L $old/lib ] || [ -e $target/lib ] || mv $old/lib $target/lib; }",
            "chown -R -h $uid:$uid $target",
            "restorecon -R -F -D $target",
            "rm -rf $old $staging"
        ).joinToString(" && ")
    }

    fun forceStop(packageName: String): String = "am force-stop ${checked(packageName)}"

    fun remove(staging: String): String = "rm -rf $staging"

    private fun checked(packageName: String): String {
        require(AppBackup.isPackageName(packageName)) { "Not a package name" }
        return packageName
    }

    private val TOOL_PATH = Regex("[A-Za-z0-9_./-]+")
}

/** Takes an app's own data as root and puts it back; see [RootDataCommands] for the commands. */
object RootData {
    private const val TAG = "RootData"
    private const val TOOL_TIMEOUT_MS = 60_000L
    private const val BUFFER_BYTES = 64 * 1024
    private const val PROGRESS_STEP_BYTES = 1024 * 1024L

    /** The user the store runs as; an app's data lives under the user's folders. */
    val userId: Int get() = Process.myUid() / PER_USER_RANGE

    private const val PER_USER_RANGE = 100_000

    private val restores = AtomicInteger(0)

    @Volatile
    private var tarFound: String? = null

    /** The `tar` command of this device, or null when root cannot be had or there is no `tar`. */
    suspend fun tar(): String? {
        tarFound?.let { return it }
        val ran = Root.run(RootDataCommands.findTar(), TOOL_TIMEOUT_MS) as? Root.Outcome.Ran ?: return null
        return RootDataCommands.tarFrom(ran.out)?.also { tarFound = it }
    }

    /** The size of the data of each of [packages], in bytes, as far as it could be read. */
    suspend fun sizes(packages: List<String>): Map<String, Long> {
        if (packages.isEmpty()) return emptyMap()
        val ran = Root.run(RootDataCommands.sizes(userId, packages), TOOL_TIMEOUT_MS) as? Root.Outcome.Ran ?: return emptyMap()
        return RootDataCommands.sizesFrom(ran.out, userId)
    }

    /** The parts of the data of each of [packages] that hold anything worth taking. */
    suspend fun parts(packages: List<String>): Map<String, List<String>> {
        if (packages.isEmpty()) return emptyMap()
        val ran = Root.run(RootDataCommands.parts(userId, packages), TOOL_TIMEOUT_MS) as? Root.Outcome.Ran ?: return emptyMap()
        return RootDataCommands.partsFrom(ran.out)
    }

    suspend fun forceStop(packageName: String) {
        Root.run(RootDataCommands.forceStop(packageName), TOOL_TIMEOUT_MS)
    }

    /**
     * Writes the data [part] of [packageName] as a tar into [sink], telling [onBytes] the
     * bytes so far, and returns the bytes written. The app is stopped first, so that what is
     * taken is whole.
     */
    @Throws(IOException::class)
    suspend fun archive(tar: String, part: String, packageName: String, sink: OutputStream, onBytes: (Long) -> Unit): Long {
        forceStop(packageName)
        var total = 0L
        val ran = Root.run(RootDataCommands.archive(tar, part, userId, packageName), null, stdout = { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            var told = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                sink.write(buffer, 0, n)
                total += n
                if (total - told >= PROGRESS_STEP_BYTES) {
                    told = total
                    onBytes(total)
                }
            }
        })
        when (ran) {
            Root.Outcome.NoRoot -> throw IOException("Root could not be had for the data of $packageName")
            is Root.Outcome.Ran -> if (ran.exit != 0) {
                throw IOException("tar failed for $packageName: ${ran.err.trim().lines().firstOrNull().orEmpty().take(200)}")
            }
        }
        onBytes(total)
        return total
    }

    /**
     * Puts the tar on [source] in the place of the data [part] of [packageName], owned by
     * [uid]: unpacked beside the app's folder first and checked, then moved into place. A
     * tar that reaches outside the app's folder is refused, see [TarCheck]. Whatever goes
     * wrong, nothing of the unpacking is left behind.
     */
    @Throws(IOException::class)
    suspend fun restore(tar: String, part: String, packageName: String, uid: Int, source: InputStream) {
        val number = restores.incrementAndGet()
        val staging = RootDataCommands.staging(part, userId, number)
        try {
            var checked: Throwable? = null
            val extracted = Root.run(RootDataCommands.extract(tar, staging), null, stdin = { out ->
                try {
                    TarCheck(source, packageName).copyTo(out, BUFFER_BYTES)
                } catch (e: IOException) {
                    checked = e
                    throw e
                }
            })
            checked?.let { throw IOException("The data of $packageName was not put back: ${it.message}", it) }
            when (extracted) {
                Root.Outcome.NoRoot -> throw IOException("Root could not be had for the data of $packageName")
                is Root.Outcome.Ran -> if (extracted.exit != 0) {
                    throw IOException("tar failed for $packageName: ${extracted.err.trim().lines().firstOrNull().orEmpty().take(200)}")
                }
            }
            val listed = Root.run(RootDataCommands.listStaging(staging), TOOL_TIMEOUT_MS) as? Root.Outcome.Ran
                ?: throw IOException("Root could not be had for the data of $packageName")
            if (listed.exit != 0 || !RootDataCommands.stagingHoldsOnly(listed.out, staging, packageName)) {
                throw IOException("The archive of $packageName does not hold the app's folder alone")
            }
            val links = Root.run(RootDataCommands.listLinks(staging), TOOL_TIMEOUT_MS) as? Root.Outcome.Ran
                ?: throw IOException("Root could not be had for the data of $packageName")
            if (links.exit != 0 || !RootDataCommands.linksStayIn(links.out, staging, packageName)) {
                throw IOException("The archive of $packageName has a link that leads out of the app's folder")
            }
            forceStop(packageName)
            val swapped = Root.run(RootDataCommands.swap(part, userId, packageName, staging, uid, number), TOOL_TIMEOUT_MS)
            when (swapped) {
                Root.Outcome.NoRoot -> throw IOException("Root could not be had for the data of $packageName")
                is Root.Outcome.Ran -> if (swapped.exit != 0) {
                    throw IOException("The data of $packageName could not be put in place: ${swapped.err.trim().lines().firstOrNull().orEmpty().take(200)}")
                }
            }
        } finally {
            withContext(NonCancellable) {
                val removed = Root.run(RootDataCommands.remove(staging), TOOL_TIMEOUT_MS)
                if (removed !is Root.Outcome.Ran || removed.exit != 0) Log.w(TAG, "Could not remove $staging")
            }
        }
    }
}
