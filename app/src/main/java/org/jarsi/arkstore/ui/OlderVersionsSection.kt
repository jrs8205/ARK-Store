package org.jarsi.arkstore.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jarsi.arkstore.R
import org.jarsi.arkstore.data.InstalledApps
import org.jarsi.arkstore.data.InstalledVersion
import org.jarsi.arkstore.data.OlderVersion
import org.jarsi.arkstore.data.StoreApp
import org.jarsi.arkstore.install.InstallManager
import org.jarsi.arkstore.install.InstallState
import org.jarsi.arkstore.install.SavedApk
import org.jarsi.arkstore.install.isOnItsWay

/** What is known of the earlier versions of the app whose details are open. */
sealed interface OlderUiState {
    data object Loading : OlderUiState
    data class Loaded(val versions: List<OlderVersion>) : OlderUiState
    data class Failed(val error: LoadError) : OlderUiState
}

/**
 * The file of an earlier version of [app] that waits in the folder for the user to install,
 * as [saved] records it: one of this app's own repository, older than the version the lists
 * offer, and not the version [installed]. The rows do not show such a file, see
 * [AppRow.downloaded]; the details do.
 *
 * The lists do not always know the version, or even the package, of the newest file: its
 * manifest could not be read, and saving an earlier version does not teach them. The file is
 * then told by its release, which the record carries: one of another release than the one
 * offered. What the record says of the file itself was read from the file once it was checked.
 */
internal fun olderSaved(app: StoreApp, installed: InstalledVersion?, saved: SavedApk?): SavedApk? =
    saved?.takeIf {
        val earlier = if (app.versionCode > 0) {
            it.versionCode < app.versionCode
        } else {
            it.tag.isNotEmpty() && it.tag != app.tag
        }
        it.repo == app.fullName && (app.packageName == null || it.packageName == app.packageName) && earlier &&
            (installed == null || installed.otherApp || installed.versionCode != it.versionCode)
    }

/**
 * [app] with the package of the file [saved] for it, when the lists do not know the app's
 * package; null when they do, or when the file is not this app's. A row of such an app tells
 * nothing of what is installed, as there is no package to look up; the saved file was read
 * when it was checked, and its package tells whether a newer version stands in its way.
 */
internal fun withPackageOf(app: StoreApp, saved: SavedApk?): StoreApp? =
    saved?.takeIf { app.packageName == null && it.repo == app.fullName }?.let { app.copy(packageName = it.packageName) }

/**
 * What [look] finds to be installed, or what was known [before] when the system fails the
 * look, as its package service can: a look that failed tells nothing, so the app is not taken
 * for one that is not installed. Being cancelled is not a failed look.
 */
internal fun foundOr(before: InstalledVersion?, look: () -> InstalledVersion?): InstalledVersion? = try {
    look()
} catch (e: CancellationException) {
    throw e
} catch (_: RuntimeException) {
    before
}

/**
 * What is installed of the app of [row], for the file [saved] for it: what the row tells, or,
 * for an app whose package the lists do not know, what is installed of the file's package
 * (see [withPackageOf]), looked up again whenever the installed apps may have changed.
 */
@Composable
private fun installedFor(row: AppRow, saved: SavedApk?): InstalledVersion? {
    val context = LocalContext.current.applicationContext
    val changed by InstallManager.installedChanged.collectAsStateWithLifecycle()
    val known = withPackageOf(row.app, saved)
    // Begun anew for another package: what was found of the one before says nothing of it.
    val found by key(known?.fullName, known?.packageName) {
        produceState<InstalledVersion?>(null, known, changed) {
            val app = known ?: return@produceState
            val before = value
            value = withContext(Dispatchers.Default) { foundOr(before) { InstalledApps.find(context, app) } }
        }
    }
    return row.installed ?: found
}

/**
 * Whether [version] is the one [installed], as far as their names tell: an earlier version's
 * file is not read before it is downloaded, so its release's tag is all there is to go by.
 * Such a version is told as installed rather than offered once more.
 */
internal fun isInstalled(installed: InstalledVersion?, version: OlderVersion): Boolean =
    installed != null && !installed.otherApp && installed.versionName != null &&
        installed.versionName.removePrefix("v") == version.displayVersion

/** Whether Android refuses [saved] over what is [installed]: a newer version of the same app. */
internal fun needsUninstall(installed: InstalledVersion?, saved: SavedApk): Boolean =
    installed != null && !installed.otherApp && installed.versionCode > saved.versionCode

/**
 * The versions of the app of [row] before the one offered, for the user to go back to: read
 * from the place when asked for ([onLoad]), as [state] tells, each with a button that installs
 * it through root ([direct]) or saves its file for the user to install. Either is confirmed
 * first, with what going back means. Below them, how the install begun here stands
 * ([install]), and the file of an earlier version that is [saved] and waits.
 */
@Composable
internal fun OlderVersionsSection(
    row: AppRow,
    direct: Boolean,
    state: OlderUiState?,
    install: InstallState?,
    saved: SavedApk?,
    onLoad: () -> Unit,
    onInstall: (OlderVersion) -> Unit,
    onOpenFolder: () -> Unit
) {
    val context = LocalContext.current
    val app = row.app
    var asked by remember(app.fullName) { mutableStateOf<OlderVersion?>(null) }
    // The version whose install was begun from here: only its progress is told here, not that
    // of the newest version begun from the list.
    var begun by rememberSaveable(app.fullName) { mutableStateOf<String?>(null) }
    val busy = install.isOnItsWay()

    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            text = stringResource(R.string.older_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(bottom = 4.dp)
                .semantics { heading() }
        )
        when (state) {
            null -> {
                Text(
                    text = stringResource(R.string.older_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StoreOutlinedButton(onClick = onLoad, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.action_show_older))
                }
            }
            OlderUiState.Loading -> Text(
                text = stringResource(R.string.older_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            is OlderUiState.Failed -> {
                Text(
                    text = stringResource(
                        if (state.error == LoadError.RATE_LIMIT) R.string.error_rate_limit else R.string.older_failed
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                StoreOutlinedButton(onClick = onLoad, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.action_retry))
                }
            }
            is OlderUiState.Loaded -> if (state.versions.isEmpty()) {
                Text(
                    text = stringResource(R.string.older_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            } else {
                state.versions.forEach { version ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (version.prerelease) {
                                    stringResource(R.string.version_beta, version.displayVersion)
                                } else {
                                    version.displayVersion
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                            val facts = listOfNotNull(
                                formatDate(version.publishedAt),
                                version.apkSize.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
                            )
                            if (facts.isNotEmpty()) {
                                Text(
                                    text = facts.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (isInstalled(row.installed, version)) {
                            Text(
                                text = stringResource(R.string.detail_installed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            StoreOutlinedButton(onClick = { asked = version }, enabled = !busy) {
                                Text(stringResource(if (direct) R.string.action_install else R.string.action_download))
                            }
                        }
                    }
                }
            }
        }

        begun?.let { version ->
            val progress = when (install) {
                InstallState.Queued -> stringResource(R.string.state_queued)
                is InstallState.Downloading -> install.progress?.let {
                    "${stringResource(R.string.state_downloading)} ${(it * 100).toInt()} %"
                } ?: stringResource(R.string.state_downloading)
                InstallState.Installing -> stringResource(R.string.state_installing)
                is InstallState.Failed -> stringResource(failureText(install.reason, row.status))
                null -> null
            }
            if (progress != null) {
                Text(
                    text = "$version: $progress",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }

        val installed = installedFor(row, saved)
        olderSaved(app, installed, saved)?.let { file ->
            Text(
                text = stringResource(
                    if (needsUninstall(installed, file)) R.string.older_saved_uninstall else R.string.older_saved,
                    file.versionName ?: file.versionCode.toString()
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            StoreOutlinedButton(onClick = onOpenFolder, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.action_open_folder))
            }
        }
    }

    asked?.let { version ->
        val action = stringResource(if (direct) R.string.action_install else R.string.action_download)
        AlertDialog(
            onDismissRequest = { asked = null },
            title = { Text(stringResource(R.string.older_confirm_title, version.displayVersion)) },
            text = {
                Text(
                    stringResource(
                        when {
                            direct -> R.string.older_confirm_root
                            row.installed != null && !row.installed.otherApp -> R.string.older_confirm_download_installed
                            else -> R.string.older_confirm_download
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        asked = null
                        begun = version.displayVersion
                        onInstall(version)
                    }
                ) {
                    Text(action)
                }
            },
            dismissButton = {
                TextButton(onClick = { asked = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
