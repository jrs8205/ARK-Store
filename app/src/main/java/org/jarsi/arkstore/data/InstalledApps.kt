package org.jarsi.arkstore.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
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

    /**
     * Every installed package, read in one go. Asking the system about each app of a
     * catalogue of thousands, one by one, takes seconds.
     */
    fun snapshot(context: Context): Map<String, PackageInfo> = try {
        context.packageManager.getInstalledPackages(0).associateBy { it.packageName }
    } catch (_: RuntimeException) {
        // The list can be too large to hand over at once; then nothing shows as installed
        // until the next look.
        emptyMap()
    }

    /**
     * The installed version of [app], seen against the release the store offers of it.
     * [installed] is a [snapshot] to look the package up in rather than asking the system.
     */
    fun find(
        context: Context,
        app: StoreApp,
        installed: Map<String, PackageInfo>? = null
    ): InstalledVersion? {
        val packageName = app.packageName ?: return null
        val info = if (installed != null) {
            installed[packageName] ?: return null
        } else {
            try {
                context.packageManager.getPackageInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return null
            }
        }
        val versionCode = PackageInfoCompat.getLongVersionCode(info)
        return InstalledVersion(
            versionCode,
            info.versionName,
            otherSigner = hasConflict(context, app, packageName) || signedOtherwise(context, app, packageName),
            beta = betas(context).getLong(packageName, -1) == versionCode
        )
    }

    /**
     * Whether the installed [packageName] is known to be signed with another key than the
     * file offered as [app]. A catalogue tells how its files are signed, so this is known
     * without downloading anything.
     */
    private fun signedOtherwise(context: Context, app: StoreApp, packageName: String): Boolean {
        val offered = app.signer ?: return false
        val installed = signers(context, packageName) ?: return false
        return offered !in installed
    }

    /**
     * [apps] as the list shows them: the same package offered by several places is listed
     * once, see [CatalogRules.merged]. [installed] is a [snapshot].
     */
    internal fun merged(
        context: Context,
        apps: List<StoreApp>,
        installed: Map<String, PackageInfo> = snapshot(context)
    ): List<CatalogRules.Merged> = CatalogRules.merged(apps) { packageName ->
        if (packageName in installed) signers(context, packageName) else null
    }

    fun status(app: StoreApp, installed: InstalledVersion?): AppStatus = when {
        installed == null -> AppStatus.NOT_INSTALLED
        app.versionCode <= installed.versionCode -> AppStatus.UP_TO_DATE
        installed.otherSigner -> AppStatus.OTHER_SIGNER
        else -> AppStatus.UPDATE_AVAILABLE
    }

    fun countUpdates(context: Context, apps: List<StoreApp>): List<StoreApp> {
        val installed = snapshot(context)
        return merged(context, apps, installed).map { it.app }.filter {
            status(it, find(context, it, installed)) == AppStatus.UPDATE_AVAILABLE
        }
    }

    /**
     * The certificates the installed [packageName] is signed with, as digests, or null when it
     * is not installed or they cannot be read.
     */
    fun signers(context: Context, packageName: String): Set<String>? =
        signers(context.packageManager, packageName)

    /**
     * An app whose signing key has been replaced along the way is told with every key it has
     * had, the present one among them. The older way of asking gives only the first of them,
     * and a file signed with the present key would then pass for one signed otherwise.
     */
    fun signers(packages: PackageManager, packageName: String): Set<String>? = try {
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = packages
                .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
            if (signing?.hasMultipleSigners() == true) {
                signing.apkContentsSigners
            } else {
                signing?.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            packages.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        certificates?.map { digest(it.toByteArray()) }?.toSet()?.takeIf { it.isNotEmpty() }
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
