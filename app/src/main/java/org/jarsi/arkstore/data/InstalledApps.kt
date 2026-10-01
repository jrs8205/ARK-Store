package org.jarsi.arkstore.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import java.security.MessageDigest

data class InstalledVersion(
    val versionCode: Long,
    val versionName: String?,
    /**
     * The installed app is known to be signed with a different key than the one offered here,
     * typically because it was installed from another store. Android will not update it.
     */
    val otherSigner: Boolean = false,
    /** This very version was installed by the store as a beta. */
    val beta: Boolean = false
)

enum class AppStatus {
    NOT_INSTALLED,
    UPDATE_AVAILABLE,
    UP_TO_DATE,

    /** A newer version is offered, but the installed app is signed with a different key. */
    OTHER_SIGNER
}

object InstalledApps {

    private const val PREFS_CONFLICTS = "signature_conflicts"
    private const val PREFS_BETAS = "installed_betas"

    /** The installed version of [app], seen against the release the store offers of it. */
    fun find(context: Context, app: StoreApp): InstalledVersion? {
        val packageName = app.packageName ?: return null
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            val versionCode = PackageInfoCompat.getLongVersionCode(info)
            InstalledVersion(
                versionCode,
                info.versionName,
                otherSigner = hasConflict(context, app, packageName),
                beta = betas(context).getLong(packageName, -1) == versionCode
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun status(app: StoreApp, installed: InstalledVersion?): AppStatus = when {
        installed == null -> AppStatus.NOT_INSTALLED
        app.versionCode <= installed.versionCode -> AppStatus.UP_TO_DATE
        installed.otherSigner -> AppStatus.OTHER_SIGNER
        else -> AppStatus.UPDATE_AVAILABLE
    }

    fun countUpdates(context: Context, apps: List<StoreApp>): List<StoreApp> = apps.filter {
        status(it, find(context, it)) == AppStatus.UPDATE_AVAILABLE
    }

    /**
     * The certificates the installed [packageName] is signed with, as digests, or null when it
     * is not installed or they cannot be read.
     */
    @Suppress("DEPRECATION")
    fun signers(context: Context, packageName: String): Set<String>? = try {
        context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            .signatures?.map { digest(it.toByteArray()) }?.toSet()?.takeIf { it.isNotEmpty() }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Remembers that the installed [packageName] is signed with another key than the APK of
     * [app], so that this release is no longer counted as an update. Otherwise an app installed
     * from another store would be announced, downloaded and refused again and again.
     *
     * [packageName] is passed separately because it is read from the downloaded file, which is
     * known even when the catalogue could not tell.
     */
    fun rememberConflict(context: Context, app: StoreApp, packageName: String) {
        val installed = signers(context, packageName) ?: return
        conflicts(context).edit { putString(conflictKey(app, packageName), key(installed)) }
    }

    /** Called when an APK of [app] turned out to match the installed app after all. */
    fun forgetConflict(context: Context, app: StoreApp, packageName: String) {
        val key = conflictKey(app, packageName)
        if (conflicts(context).contains(key)) conflicts(context).edit { remove(key) }
    }

    /**
     * Whether the conflict remembered for [app] still holds. It is tied to the key the app was
     * installed with at the time: once the app has been removed or installed again with
     * another key, it no longer applies. Nothing is changed here, since this is asked from
     * several threads; an entry that no longer applies is replaced or removed by the next
     * install attempt.
     */
    private fun hasConflict(context: Context, app: StoreApp, packageName: String): Boolean {
        val remembered = conflicts(context).getString(conflictKey(app, packageName), null)
            ?: return false
        return signers(context, packageName)?.let(::key) == remembered
    }

    /**
     * Remembers that the store is installing [versionCode] of [packageName] as a beta, so
     * that the installed version can later be told from one that came from somewhere else.
     */
    fun rememberBeta(context: Context, packageName: String, versionCode: Long) {
        betas(context).edit { putLong(packageName, versionCode) }
    }

    private fun betas(context: Context) =
        context.getSharedPreferences(PREFS_BETAS, Context.MODE_PRIVATE)

    /**
     * What a remembered conflict belongs to: one package as offered by one repository, in its
     * full release or in its prerelease. The same package may come from another repository, or
     * a beta may be signed differently from the full release; a conflict with one of them says
     * nothing about the others.
     */
    internal fun conflictKey(app: StoreApp, packageName: String): String =
        listOf(packageName, app.fullName.lowercase(), if (app.prerelease) "beta" else "stable")
            .joinToString("|")

    private fun key(signers: Set<String>): String = signers.sorted().joinToString(",")

    private fun conflicts(context: Context) =
        context.getSharedPreferences(PREFS_CONFLICTS, Context.MODE_PRIVATE)
}
