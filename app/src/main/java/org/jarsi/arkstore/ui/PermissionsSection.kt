package org.jarsi.arkstore.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jarsi.arkstore.R
import org.jarsi.arkstore.data.InstalledVersion
import org.jarsi.arkstore.data.Permissions
import org.jarsi.arkstore.data.StoreApp

/**
 * What the details show of the permissions an app asks for on this device: [all] of them, a
 * line each, and those among them an update would [added] to what the installed version has.
 */
internal data class PermissionsShown(val all: List<String>, val added: List<String>)

/**
 * The permissions [listed] for the app of [packageName] as this device words them. With
 * [compare], the ones the installed app of that package does not ask for are told apart.
 * Asks the system about every permission, so not for the main thread.
 */
internal fun permissionsShown(
    packages: PackageManager,
    listed: List<String>,
    packageName: String?,
    compare: Boolean,
    locale: Locale
): PermissionsShown {
    val names = Permissions.onDevice(listed, Build.VERSION.SDK_INT, packageName)
    val described = PermissionTexts.ordered(names.map { PermissionTexts.describe(packages, it) })
    val has = if (compare && packageName != null) installedPermissions(packages, packageName) else null
    val added = if (has == null) {
        emptyList()
    } else {
        val new = Permissions.added(names, has).toHashSet()
        described.filter { it.name in new }
    }
    return PermissionsShown(PermissionTexts.lines(described, locale), PermissionTexts.lines(added, locale))
}

/** The permissions the installed app of [packageName] asks for, or null when the system does not tell. */
private fun installedPermissions(packages: PackageManager, packageName: String): Set<String>? = try {
    packages.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
} catch (_: PackageManager.NameNotFoundException) {
    null
} catch (_: RuntimeException) {
    null
}

/**
 * What the permissions an update adds are told against: the version of [app]'s package that
 * is [installed] and when it was installed, or null when there is nothing to compare with,
 * as when no app is installed, another app has the name, or the file offered is no newer.
 * Another version installed meanwhile, or the same one again, is another comparison: what was
 * read of the one before says nothing of it.
 */
internal fun installedCompared(app: StoreApp, installed: InstalledVersion?): Pair<Long, Long>? =
    installed?.takeIf { !it.otherApp && app.versionCode > it.versionCode }?.let { it.versionCode to it.updatedAt }

/** What was read for a page, with what it was read for: shown only while that still holds. */
private data class PermissionsRead(val key: List<Any?>, val shown: PermissionsShown)

/**
 * The permissions [app] asks for, when the place it comes from tells them: how many, with the
 * list behind a tap, and above it the ones an update would add to those of the [installed]
 * version, which are shown without asking. Nothing when the permissions are not known.
 */
@Composable
internal fun PermissionsSection(app: StoreApp, installed: InstalledVersion?) {
    val listed = app.permissions ?: return
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    // Told only of the app itself, and of a version newer than the one installed.
    val compared = installedCompared(app, installed)
    val key = listOf(listed, app.packageName, compared, locale)
    val read by produceState<PermissionsRead?>(null, key) {
        value = withContext(Dispatchers.IO) {
            PermissionsRead(key, permissionsShown(context.packageManager, listed, app.packageName, compared != null, locale))
        }
    }
    // What was read for another app, or against another installed version, is not shown
    // while this one's is on its way.
    val permissions = read?.takeIf { it.key == key }?.shown ?: return
    var expanded by rememberSaveable(app.fullName) { mutableStateOf(false) }

    Column(modifier = Modifier.padding(top = 12.dp)) {
        if (permissions.all.isEmpty()) {
            Text(
                text = stringResource(R.string.permissions_title, 0),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 4.dp)
                    .semantics { heading() }
            )
            Text(
                text = stringResource(R.string.permissions_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics {
                    heading()
                    if (expanded) {
                        collapse {
                            expanded = false
                            true
                        }
                    } else {
                        expand {
                            expanded = true
                            true
                        }
                    }
                }
        ) {
            Text(
                text = stringResource(R.string.permissions_title, permissions.all.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                painter = painterResource(R.drawable.ic_expand),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
        if (permissions.added.isNotEmpty()) {
            Text(
                text = stringResource(R.string.permissions_new),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            permissions.added.forEach { PermissionLine(it) }
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = if (permissions.added.isNotEmpty()) 8.dp else 0.dp)) {
                permissions.all.forEach { PermissionLine(it) }
            }
        }
    }
}

/** One permission, a line of its own. */
@Composable
private fun PermissionLine(text: String) {
    Text(
        text = "•  $text",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
