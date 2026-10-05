package org.jarsi.arkstore.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class InstalledVersion(
    val versionCode: Long,
    val versionName: String?,
    /**
     * The installed app is known to be signed with a different key than the one offered here,
     * typically because it was installed from another store. Android will not update it.
     */
    val otherSigner: Boolean = false,
    /** This very version was installed by the store as a beta. */
    val beta: Boolean = false,
    /** The installed app is known to be signed with the same key as the one offered here. */
    val sameSigner: Boolean = false,
    /**
     * The file offered here is another project's build of the package, as far as is known:
     * the installed app carries a key another place is known to use, and this file's key is
     * not known. Not a conflict seen, see [otherSigner], but no update to offer either.
     */
    val otherBuild: Boolean = false,
    /**
     * The package that installed the app, such as "com.android.vending" for Google Play, or
     * null when the system does not tell: an app installed by hand, or by a tool.
     */
    val installer: String? = null,
    /** The name the installed app goes by on the device, when it could be read. */
    val label: String? = null,
    /**
     * What is installed under this package name is another app altogether: signed with
     * another key and going by another name, like Google's own app whose package name an
     * app offered here has taken for itself. The app offered here is not installed at all.
     */
    val otherApp: Boolean = false
)

enum class AppStatus {
    NOT_INSTALLED,
    UPDATE_AVAILABLE,
    UP_TO_DATE,

    /** A newer version is offered, but the installed app is signed with a different key. */
    OTHER_SIGNER,

    /**
     * A newer version is offered by another project than the one whose key the installed
     * app carries, as far as is known: not offered as the update, though its key, not known
     * yet, may turn out to match.
     */
    OTHER_BUILD,

    /**
     * Another app altogether is installed under this package name, signed with another key
     * and going by another name; this app is not installed, and cannot be while that is.
     */
    OTHER_APP,

    /**
     * A newer version is offered, but this Android cannot run it: it needs a newer one. The
     * installed version stays, and nothing is offered as the update.
     */
    NEEDS_NEWER_ANDROID
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
        // The index and the catalogues tell how their files are signed, so whether the
        // installed app is signed the same way is known without downloading anything.
        val signers = app.signer?.let { presentSigners(context.packageManager, packageName) }
        val names = labelsOf(context, info)
        return compareIdentity(
            app,
            InstalledVersion(
                versionCode,
                info.versionName,
                otherSigner = hasConflict(context, app, packageName),
                beta = betas(context).getLong(packageName, -1) == versionCode,
                installer = installerOf(context, packageName, info.lastUpdateTime),
                label = names?.shown
            ),
            signers,
            names?.own
        )
    }

    /** Compares current signing and default names, retaining a remembered conflict until disproved. */
    internal fun compareIdentity(
        app: StoreApp,
        installed: InstalledVersion,
        signers: Set<String>?,
        defaultLabel: String?
    ): InstalledVersion {
        val sameSigner = CatalogRules.sameSigner(app.signer, signers)
        val differentSigner = app.signer != null && !signers.isNullOrEmpty() && !sameSigner
        // Repository labels come from APK resources. Catalogue labels are translated
        // metadata, and a repository name standing in for a missing label is not an APK name.
        val offeredLabel = app.label?.trim()?.takeIf { app.fromRepository && it.isNotEmpty() }
        val installedLabel = defaultLabel?.trim()?.takeIf { it.isNotEmpty() }
        return installed.copy(
            sameSigner = sameSigner,
            otherSigner = differentSigner || (!sameSigner && installed.otherSigner),
            otherApp = differentSigner && offeredLabel != null && installedLabel != null &&
                !installedLabel.equals(offeredLabel, ignoreCase = true)
        )
    }

    /** The names an installed app goes by: [shown] in the device's language, [own] in its own default one. */
    private class Labels(val shown: String?, val own: String?)

    private val labels = ConcurrentHashMap<String, Labels>()

    /**
     * The names the installed app [info] goes by, or null when they cannot be read.
     * Remembered per installation ([PackageInfo.lastUpdateTime]) and per language of the
     * device, since the list asks for every installed app whenever it is shown.
     */
    private fun labelsOf(context: Context, info: PackageInfo): Labels? {
        val application = info.applicationInfo ?: return null
        val language = context.resources.configuration.locales.toLanguageTags()
        val key = "${info.packageName}:${info.lastUpdateTime}:$language"
        labels[key]?.let { return it }
        val shown = try {
            application.loadLabel(context.packageManager).toString().trim().ifEmpty { null }
        } catch (_: RuntimeException) {
            null
        }
        val own = try {
            if (application.labelRes == 0) {
                application.nonLocalizedLabel?.toString()?.trim()?.ifEmpty { null }
            } else {
                val plain = Configuration(context.resources.configuration).apply { setLocale(Locale.ROOT) }
                context.createPackageContext(info.packageName, 0)
                    .createConfigurationContext(plain)
                    .resources.getString(application.labelRes).trim().ifEmpty { null }
            }
        } catch (_: RuntimeException) {
            null
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val names = Labels(shown, own)
        labels[key] = names
        return names
    }

    private val installers = ConcurrentHashMap<String, String>()

    /**
     * The package that installed [packageName], or null when the system does not tell.
     * Remembered per installation ([lastUpdateTime]), since the list asks for every
     * installed app whenever it is shown.
     */
    fun installerOf(context: Context, packageName: String, lastUpdateTime: Long): String? {
        val key = "$packageName:$lastUpdateTime"
        val known = installers[key]
        if (known != null) return known.ifEmpty { null }
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(packageName)
                    .let { it.installingPackageName ?: it.initiatingPackageName }
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(packageName)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        installers[key] = installer.orEmpty()
        return installer
    }

    /**
     * [apps] as the list shows them: the same package offered by several places is listed
     * once, see [CatalogRules.merged]. [installed] is a [snapshot].
     */
    internal fun merged(
        context: Context,
        apps: List<StoreApp>,
        installed: Map<String, PackageInfo> = snapshot(context)
    ): List<CatalogRules.Merged> = CatalogRules.merged(apps, CatalogRules.Android.THIS) { packageName ->
        if (packageName in installed) presentSigners(context.packageManager, packageName) else null
    }

    /**
     * [find] for a row of the list: a row that is another project's build of the installed
     * package ([CatalogRules.Merged.otherBuild]) is not offered as its update. A key seen to
     * differ stays what it is; a key not known is left unknown, not taken for a conflict.
     */
    internal fun find(
        context: Context,
        row: CatalogRules.Merged,
        installed: Map<String, PackageInfo>? = null
    ): InstalledVersion? = find(context, row.app, installed)?.let {
        if (row.otherBuild && !it.otherSigner && !it.sameSigner) it.copy(otherBuild = true) else it
    }

    /**
     * How [app] stands against what is [installed] of it. [runs] says whether the file offered
     * runs on this Android ([CatalogRules.runsOn]); one that does not is no update, whatever
     * its key, since nothing could be installed.
     */
    fun status(app: StoreApp, installed: InstalledVersion?, runs: Boolean = true): AppStatus = when {
        installed == null -> AppStatus.NOT_INSTALLED
        installed.otherApp -> AppStatus.OTHER_APP
        app.versionCode <= installed.versionCode -> AppStatus.UP_TO_DATE
        !runs -> AppStatus.NEEDS_NEWER_ANDROID
        installed.otherSigner -> AppStatus.OTHER_SIGNER
        installed.otherBuild -> AppStatus.OTHER_BUILD
        else -> AppStatus.UPDATE_AVAILABLE
    }

    fun countUpdates(context: Context, apps: List<StoreApp>): List<StoreApp> {
        val installed = snapshot(context)
        val android = CatalogRules.Android.THIS
        return merged(context, apps, installed).filter {
            val runs = CatalogRules.runsOn(it.app, android)
            status(it.app, find(context, it, installed), runs) == AppStatus.UPDATE_AVAILABLE
        }.map { it.app }
    }

    /**
     * The certificates the installed [packageName] is signed with, as digests, or null when it
     * is not installed or they cannot be read. For an app whose signing key has been replaced
     * along the way this is the first key it had, which stays the same as long as the same
     * line of keys is installed: a conflict remembered is tied to it.
     */
    @Suppress("DEPRECATION")
    fun signers(context: Context, packageName: String): Set<String>? = try {
        context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            .signatures?.map { digest(it.toByteArray()) }?.toSet()?.takeIf { it.isNotEmpty() }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Every certificate the installed [packageName] is or has been signed with, as digests,
     * or null when it is not installed or they cannot be read. A downloaded file that is
     * signed with any of them may update the app, see [CatalogRules.mayUpdate]: a file that
     * carries the history of its keys reports the first of them, one that does not reports
     * the present one, and Android takes both.
     */
    @Suppress("DEPRECATION")
    fun keysHeld(packages: PackageManager, packageName: String): Set<String>? = try {
        val keys = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = packages.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
            // The history is reported for an app with a single signer only; it ends with the
            // present key.
            signing?.signingCertificateHistory?.mapTo(keys) { digest(it.toByteArray()) }
            signing?.apkContentsSigners?.mapTo(keys) { digest(it.toByteArray()) }
        }
        packages.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            .signatures?.mapTo(keys) { digest(it.toByteArray()) }
        keys.takeIf { it.isNotEmpty() }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * The certificates the installed [packageName] is signed with now, as digests, or null
     * when it is not installed or they cannot be read. A catalogue tells the key each of its
     * files is signed with, and it is the present key of the installed app that such a file
     * has to match: a key the app has had before and given up does not update it.
     */
    fun presentSigners(packages: PackageManager, packageName: String): Set<String>? = try {
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packages.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
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
