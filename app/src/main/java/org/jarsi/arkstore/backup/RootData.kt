package org.jarsi.arkstore.backup

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

    /** The last line [parts] prints, which says the listing ran to its end. */
    const val PARTS_END = "arkstore-parts-end"

    /** A line [parts] prints when a folder could not be listed: the listing is then not to be trusted. */
    const val PARTS_ERROR = "arkstore-parts-error"

    /** The folder of the data [part] of every app of user [userId]: the system's own, reached as root, not the store's. */
    @Suppress("SdCardPath")
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
     * "part package"; [PARTS_ERROR] for a folder that could not be listed, since what `find`
     * could not read is not the same as nothing; and [PARTS_END] last of all, so that a
     * listing that was never run to its end, as when root was refused, is told from one of
     * apps that have no data.
     */
    fun parts(userId: Int, packages: List<String>): String {
        require(packages.isNotEmpty()) { "No packages" }
        val lines = AppBackup.DATA_PARTS.flatMap { part ->
            packages.map { pkg ->
                val dir = "${userDir(part, userId)}/${checked(pkg)}"
                val excluded = EXCLUDED.joinToString(" ") { "! -name $it" }
                "if [ -d $dir ]; then f=\$(find $dir -mindepth 1 -maxdepth 1 $excluded 2>&1) || echo $PARTS_ERROR; " +
                    "[ -n \"\$f\" ] && echo \"$part $pkg\"; fi"
            }
        }
        return lines.joinToString("; ") + "; echo $PARTS_END"
    }

    /**
     * The parts [parts] printed, by package, in the order of [AppBackup.DATA_PARTS]; null when
     * the listing did not run to its end, or a folder could not be listed.
     */
    fun partsFrom(output: String): Map<String, List<String>>? {
        val found = HashMap<String, MutableList<String>>()
        var ended = false
        for (line in output.lineSequence()) {
            val words = line.trim().split(' ')
            if (words.size == 1 && words[0] == PARTS_ERROR) return null
            if (words.size == 1 && words[0] == PARTS_END) {
                ended = true
                continue
            }
            if (words.size != 2 || words[0] !in AppBackup.DATA_PARTS || !AppBackup.isPackageName(words[1])) continue
            val list = found.getOrPut(words[1]) { ArrayList(2) }
            if (words[0] !in list) list += words[0]
        }
        if (!ended) return null
        return found.mapValues { (_, parts) -> parts.sortedBy { AppBackup.DATA_PARTS.indexOf(it) } }
    }

    /** Writes the data [part] of [packageName] as a tar to the output, the caches and libraries left out. */
    fun archive(tar: String, part: String, userId: Int, packageName: String): String {
        val pkg = checked(packageName)
        val excludes = EXCLUDED.joinToString(" ") { "--exclude=$pkg/$it" }
        return "$tar -c -f - -C ${userDir(part, userId)} $excludes $pkg"
    }

    /**
     * The folder a restore of [part] is unpacked into first: beside the apps' folders, so a
     * move into place is a rename. [token] names the restore, see [RootData.token], so that no
     * two restores, nor one and what an earlier one left behind, share a folder.
     */
    fun staging(part: String, userId: Int, token: String): String = "${userDir(part, userId)}/.arkstore-restore-${checkedToken(token)}"

    /** Where the app's data as it was is kept while the restore [token] puts the new in place. */
    fun old(part: String, userId: Int, token: String): String = "${userDir(part, userId)}/.arkstore-old-${checkedToken(token)}"

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
     * Puts the unpacked data of [packageName] in the staging folder of the restore [token]
     * in the place of the app's data, owned by the app ([uid]) and labelled as the system
     * labels an app's data, keeping the app's link to its native libraries. The old data is
     * moved aside and kept, see [old]: a step that fails puts it back, and [finish] removes
     * it once every part of the app's data is in place.
     */
    fun swap(part: String, userId: Int, packageName: String, uid: Int, token: String): String {
        require(uid >= 0) { "Not a uid" }
        val pkg = checked(packageName)
        val target = "${userDir(part, userId)}/$pkg"
        val fresh = "${staging(part, userId, token)}/$pkg"
        val old = old(part, userId, token)
        return listOf(
            "chown -R -h $uid:$uid $fresh",
            "rm -rf $old",
            "{ [ ! -e $target ] || mv $target $old; }",
            "{ mv $fresh $target || { [ ! -e $old ] || mv $old $target; false; }; }",
            "{ [ ! -L $old/lib ] || [ -e $target/lib ] || mv $old/lib $target/lib; }",
            "{ restorecon -R -F -D $target || { ${putBack(target, old)}; false; }; }"
        ).joinToString(" && ")
    }

    /** Takes the data [swap] put in place out again and puts the old back, as far as it is there. */
    fun unswap(part: String, userId: Int, packageName: String, token: String): String {
        val target = "${userDir(part, userId)}/${checked(packageName)}"
        return putBack(target, old(part, userId, token))
    }

    /**
     * The old data at [old] back in the place of [target], with the link to the libraries.
     * Without the old there is nothing to put back, and [target] is left as it is: it is then
     * the app's own data, never moved, or the old put back already.
     */
    private fun putBack(target: String, old: String): String =
        "{ [ ! -e $old ] || { { [ ! -L $target/lib ] || [ -e $old/lib ] || mv $target/lib $old/lib; } && rm -rf $target && mv $old $target; }; }"

    /** Removes what the restore [token] of [part] left beside the apps' folders: the old data and the staging folder. */
    fun finish(part: String, userId: Int, token: String): String =
        "rm -rf ${old(part, userId, token)} ${staging(part, userId, token)}"

    fun forceStop(packageName: String, userId: Int): String {
        require(userId >= 0) { "Not a user" }
        return "am force-stop --user $userId ${checked(packageName)}"
    }

    fun remove(staging: String): String = "rm -rf $staging"

    private fun checked(packageName: String): String {
        require(AppBackup.isPackageName(packageName)) { "Not a package name" }
        return packageName
    }

    private fun checkedToken(token: String): String {
        require(TOKEN.matches(token)) { "Not a token" }
        return token
    }

    private val TOOL_PATH = Regex("[A-Za-z0-9_./-]+")
    private val TOKEN = Regex("[0-9]{1,20}-[0-9]{1,9}")
}

/** Takes an app's own data as root and puts it back; see [RootDataCommands] for the commands. */
object RootData {
    private const val TAG = "RootData"
    private const val TOOL_TIMEOUT_MS = 60_000L

    /** Putting the old data back or clearing up is a few moves and removals; large data takes its time. */
    private const val CLEAR_TIMEOUT_MS = 10 * 60_000L
    private const val BUFFER_BYTES = 64 * 1024
    private const val PROGRESS_STEP_BYTES = 1024 * 1024L

    /** The user the store runs as; an app's data lives under the user's folders. */
    val userId: Int get() = Root.userId

    private val restores = AtomicInteger(0)

    @Volatile
    private var tarFound: String? = null

    /** The `tar` command of this device, or null when root cannot be had or there is no `tar`. */
    suspend fun tar(): String? {
        tarFound?.let { return it }
        val ran = Root.run(RootDataCommands.findTar(), TOOL_TIMEOUT_MS, global = true) as? Root.Outcome.Ran ?: return null
        if (ran.cut) return null
        return RootDataCommands.tarFrom(ran.out)?.also { tarFound = it }
    }

    /** The size of the data of each of [packages], in bytes, as far as it could be read. */
    suspend fun sizes(packages: List<String>): Map<String, Long> {
        if (packages.isEmpty()) return emptyMap()
        val ran = Root.run(RootDataCommands.sizes(userId, packages), TOOL_TIMEOUT_MS, global = true) as? Root.Outcome.Ran ?: return emptyMap()
        if (ran.exit != 0 || ran.cut) Log.w(TAG, "du ended with ${ran.exit}${if (ran.cut) ", cut short" else ""}: ${ran.err.take(200)}")
        return RootDataCommands.sizesFrom(ran.out, userId).also { Log.i(TAG, "Sizes of ${it.size} of ${packages.size} apps read") }
    }

    /**
     * The parts of the data of each of [packages] that hold anything worth taking; null when
     * the listing could not be had whole, which is not the same as apps that have no data.
     */
    suspend fun parts(packages: List<String>): Map<String, List<String>>? {
        if (packages.isEmpty()) return emptyMap()
        val ran = Root.run(RootDataCommands.parts(userId, packages), TOOL_TIMEOUT_MS, global = true) as? Root.Outcome.Ran
        if (ran == null || ran.exit != 0 || ran.cut) {
            Log.w(TAG, "The data could not be listed: ${if (ran == null) "no root" else "exit ${ran.exit}${if (ran.cut) ", cut short" else ""}: ${ran.err.take(200)}"}")
            return null
        }
        val parts = RootDataCommands.partsFrom(ran.out)
        if (parts == null) Log.w(TAG, "The listing of the data did not run to its end: ${ran.err.take(200)}")
        else Log.i(TAG, "Parts of ${parts.size} of ${packages.size} apps found")
        return parts
    }

    suspend fun forceStop(packageName: String) {
        Root.run(RootDataCommands.forceStop(packageName, userId), TOOL_TIMEOUT_MS, global = true)
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
        val ran = Root.run(RootDataCommands.archive(tar, part, userId, packageName), null, global = true, stdout = { input ->
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

    /** A part of an app's data unpacked beside the app's folder and checked, waiting to be put in place by [swap]. */
    class Staged internal constructor(val part: String, val packageName: String, val token: String) {
        /** Whether [swap] has put it in place and cleared up after it. */
        @Volatile internal var finished = false
    }

    /** A name for a restore that no other restore has had, now or in an earlier run of the store: the time and a count. */
    private fun token(): String = "${System.currentTimeMillis()}-${restores.incrementAndGet()}"

    /**
     * Unpacks the tar on [source], the data [part] of [packageName], beside the app's folder
     * and checks it: a tar that reaches outside the app's folder is refused, see [TarCheck],
     * and the unpacked folder must hold the app's folder alone, with no link leading out of
     * it. Nothing of the app's own data is touched. Whatever goes wrong, nothing of the
     * unpacking is left behind.
     */
    @Throws(IOException::class)
    suspend fun stage(tar: String, part: String, packageName: String, source: InputStream): Staged {
        val token = token()
        val staging = RootDataCommands.staging(part, userId, token)
        try {
            var checked: Throwable? = null
            val extracted = Root.run(RootDataCommands.extract(tar, staging), null, global = true, stdin = { out ->
                try {
                    TarCheck(source, packageName).copyTo(out, BUFFER_BYTES)
                } catch (e: IOException) {
                    checked = e
                    throw e
                }
            })
            checked?.let {
                // A wrong password is told apart from the rest, for the user to try again.
                if (it is WrongPasswordException) throw it
                throw IOException("The data of $packageName was not put back: ${it.message}", it)
            }
            when (extracted) {
                Root.Outcome.NoRoot -> throw IOException("Root could not be had for the data of $packageName")
                is Root.Outcome.Ran -> if (extracted.exit != 0) {
                    throw IOException("tar failed for $packageName: ${extracted.err.trim().lines().firstOrNull().orEmpty().take(200)}")
                }
            }
            val listed = Root.run(RootDataCommands.listStaging(staging), TOOL_TIMEOUT_MS, global = true) as? Root.Outcome.Ran
                ?: throw IOException("Root could not be had for the data of $packageName")
            if (listed.exit != 0 || listed.cut || !RootDataCommands.stagingHoldsOnly(listed.out, staging, packageName)) {
                throw IOException("The archive of $packageName does not hold the app's folder alone")
            }
            val links = Root.run(RootDataCommands.listLinks(staging), TOOL_TIMEOUT_MS, global = true) as? Root.Outcome.Ran
                ?: throw IOException("Root could not be had for the data of $packageName")
            if (links.exit != 0 || links.cut || !RootDataCommands.linksStayIn(links.out, staging, packageName)) {
                throw IOException("The archive of $packageName has a link that leads out of the app's folder")
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { remove(staging) }
            throw e
        }
        return Staged(part, packageName, token)
    }

    /**
     * Puts [staged], every part of the data of [packageName] that was staged, in the place of
     * the app's data, owned by [uid], the app stopped first. The old data is kept aside until
     * every part is in place; a part that fails puts the parts before it back as they were,
     * so that the app is left with the data it had, never with parts of two.
     */
    @Throws(IOException::class)
    suspend fun swap(staged: List<Staged>, packageName: String, uid: Int) {
        if (staged.isEmpty()) return
        forceStop(packageName)
        val swapped = ArrayList<Staged>(staged.size)
        try {
            for (part in staged) {
                // Counted as begun before its command runs: a command cut short by a cancel
                // or a failure may have moved the data already, and is put back all the same.
                swapped += part
                // No limit on the time: the ownership and the labels of large data take a
                // while, and a cancel kills the command as it is.
                val ran = Root.run(RootDataCommands.swap(part.part, userId, packageName, uid, part.token), null, global = true)
                when (ran) {
                    Root.Outcome.NoRoot -> throw IOException("Root could not be had for the data of $packageName")
                    is Root.Outcome.Ran -> if (ran.exit != 0) {
                        throw IOException("The data of $packageName could not be put in place: ${ran.err.trim().lines().firstOrNull().orEmpty().take(200)}")
                    }
                }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                for (part in swapped.asReversed()) {
                    val back = Root.run(RootDataCommands.unswap(part.part, userId, packageName, part.token), CLEAR_TIMEOUT_MS, global = true)
                    if (back !is Root.Outcome.Ran || back.exit != 0) {
                        // The old data stays where it was put aside, for whoever comes to look.
                        Log.e(TAG, "The old ${part.part} of $packageName could not be put back; it is kept at ${RootDataCommands.old(part.part, userId, part.token)}")
                        part.finished = true
                    }
                }
            }
            throw e
        }
        withContext(NonCancellable) {
            for (part in staged) {
                val cleared = Root.run(RootDataCommands.finish(part.part, userId, part.token), CLEAR_TIMEOUT_MS, global = true)
                if (cleared !is Root.Outcome.Ran || cleared.exit != 0) Log.w(TAG, "Could not clear up after the restore of ${part.part} of $packageName")
                part.finished = true
            }
        }
    }

    /** Removes the staging folders of [staged] that [swap] has not cleared up; the app's data is not touched. */
    suspend fun discard(staged: List<Staged>) {
        withContext(NonCancellable) {
            for (part in staged) {
                if (!part.finished) remove(RootDataCommands.staging(part.part, userId, part.token))
            }
        }
    }

    private suspend fun remove(staging: String) {
        val removed = Root.run(RootDataCommands.remove(staging), CLEAR_TIMEOUT_MS, global = true)
        if (removed !is Root.Outcome.Ran || removed.exit != 0) Log.w(TAG, "Could not remove $staging")
    }
}
