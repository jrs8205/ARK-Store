package org.jarsi.arkstore.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.security.MessageDigest
import org.jarsi.arkstore.data.CatalogRules
import org.jarsi.arkstore.data.InstalledApps
import org.jarsi.arkstore.data.StoreApp

/** The checks a downloaded file passes before anything is done with it. */
internal object ApkCheck {
    private const val TAG = "ApkCheck"

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    fun readArchive(context: Context, apk: File): PackageInfo? = try {
        // GET_SIGNATURES still works on archives for every supported Android version, whereas
        // GET_SIGNING_CERTIFICATES returns nothing for them before Android 13.
        context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
    } catch (e: Exception) {
        Log.w(TAG, "Could not parse ${apk.name}", e)
        null
    }

    /**
     * The archive of [file], downloaded for [app], when it is what the catalogue says it is:
     * of the checksum the catalogue gives, if any, an APK that can be read, and of the package
     * the catalogue names, if it names one. Its files come from mirrors too, so one that is
     * anything else is not used. Null otherwise.
     */
    fun checkDownload(context: Context, app: StoreApp, file: File): PackageInfo? {
        if (app.sha256 != null && !app.sha256.equals(sha256(file), ignoreCase = true)) {
            Log.w(TAG, "Downloaded file of ${app.fullName} does not match its checksum")
            return null
        }
        val archive = readArchive(context, file) ?: return null
        if (app.packageName != null && archive.packageName != app.packageName) return null
        return archive
    }

    /** The digests of the certificates [archive] is signed with, as [InstalledApps.digest] gives them. */
    @Suppress("DEPRECATION")
    fun signers(archive: PackageInfo): List<String> =
        archive.signatures?.map { InstalledApps.digest(it.toByteArray()) }.orEmpty()

    /**
     * False only when the app is installed and provably signed with a different key: one the
     * installed app has never had, see [CatalogRules.mayUpdate].
     */
    fun signaturesMatch(context: Context, archive: PackageInfo): Boolean =
        CatalogRules.mayUpdate(signers(archive), InstalledApps.keysHeld(context.packageManager, archive.packageName))
}
