package org.jarsi.arkstore.ui

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import java.util.Locale

/**
 * A permission as the device describes it: its [name], what the device calls it in the
 * user's language, or null when it has no name for it, and whether it is one the user is
 * asked about before the app gets it, as the camera or the location is.
 */
data class DescribedPermission(val name: String, val label: String?, val sensitive: Boolean)

/**
 * The words for the permissions an app asks for. They are the device's own, the ones its
 * settings show, so they come in the user's language and need no translation here; a
 * permission the device does not know, as one another app makes or a newer Android has, is
 * shown by its name.
 */
object PermissionTexts {

    /** [name] as this device describes it. Asks the system, so not for the main thread with a long list. */
    fun describe(packages: PackageManager, name: String): DescribedPermission {
        val info = try {
            packages.getPermissionInfo(name, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return DescribedPermission(name, null, false)
        } catch (_: RuntimeException) {
            return DescribedPermission(name, null, false)
        }
        val label = try {
            labelOf(name, info.loadLabel(packages))
        } catch (_: RuntimeException) {
            null
        }
        @Suppress("DEPRECATION")
        val protection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.protection
        } else {
            info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        }
        return DescribedPermission(name, label, protection == PermissionInfo.PROTECTION_DANGEROUS)
    }

    /**
     * What [loaded], the label the system gave for the permission [name], says, or null when
     * it says nothing: a permission without a label of its own is given its name for one.
     */
    fun labelOf(name: String, loaded: CharSequence?): String? =
        loaded?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != name }

    /** The line that shows [permission]: what the device calls it, begun with a capital, or its name. */
    fun line(permission: DescribedPermission, locale: Locale): String =
        permission.label?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() } ?: permission.name

    /** [all] with those the user is asked about first, each kind in the order it came. */
    fun ordered(all: List<DescribedPermission>): List<DescribedPermission> = all.sortedBy { !it.sensitive }

    /** The lines that show [all], in their order; two permissions the device calls the same are one line. */
    fun lines(all: List<DescribedPermission>, locale: Locale): List<String> = all.map { line(it, locale) }.distinct()
}
