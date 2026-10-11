package org.jarsi.arkstore.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkstore.R
import org.jarsi.arkstore.backup.AppBackup
import org.jarsi.arkstore.backup.BackupEntry
import org.jarsi.arkstore.backup.BackupError
import org.jarsi.arkstore.backup.BackupState
import org.jarsi.arkstore.backup.Installers
import org.jarsi.arkstore.backup.RestoreStatus
import org.jarsi.arkstore.install.FailReason
import org.jarsi.arkstore.install.InstallManager
import org.jarsi.arkstore.install.InstallState
import org.jarsi.arkstore.install.localKey

/** Chooses the installed apps to copy to the folder, and copies them. */
@Composable
internal fun BackupScreen(viewModel: BackupViewModel, settingsJson: () -> String, onBack: () -> Unit) {
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installedChanged by InstallManager.installedChanged.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val running = state is BackupState.Running
    // Read on coming here and again after every install: the versions and files change with them.
    LaunchedEffect(installedChanged) { viewModel.loadCandidates() }
    StorePage(title = stringResource(R.string.action_backup_apps), onBack = onBack, scrolling = false) {
        FolderChooser(tree, name = null, enabled = !running, onChosen = viewModel::chooseTree)
        val list = candidates
        if (list == null) {
            Text(
                text = stringResource(R.string.backup_reading),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 16.dp)
            )
            Spacer(Modifier.weight(1f))
        } else {
            val chosen = list.filter { it.entry.packageName in selected }
            SelectionBar(
                count = chosen.size,
                bytes = chosen.sumOf { it.entry.size },
                enabled = !running,
                onAll = { viewModel.selectAll(true) },
                onNone = { viewModel.selectAll(false) }
            )
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(list, key = { it.entry.packageName }) { candidate ->
                    val entry = candidate.entry
                    CheckRow(
                        checked = entry.packageName in selected && candidate.readable,
                        enabled = candidate.readable && !running,
                        onCheckedChange = { viewModel.toggle(entry.packageName) },
                        title = entry.label,
                        detail = listOf(
                            version(entry),
                            installerName(entry.installer),
                            Formatter.formatShortFileSize(context, entry.size)
                        ).joinToString(" · "),
                        note = if (candidate.readable) null else stringResource(R.string.backup_unreadable),
                        noteIsError = false
                    )
                }
            }
        }
        JobFooter(
            state = state,
            canStart = tree != null && list != null && list.any { it.readable && it.entry.packageName in selected },
            startLabel = R.string.action_save_backup,
            onStart = { viewModel.startBackup(settingsJson()) },
            onCancel = viewModel::cancel,
            onDismiss = viewModel::dismiss
        )
    }
}

/** Shows what the folder holds against this device, and installs what is chosen. */
@Composable
internal fun RestoreScreen(viewModel: BackupViewModel, onImportSettings: (Uri) -> Unit, onBack: () -> Unit) {
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    val folderError by viewModel.folderError.collectAsStateWithLifecycle()
    val selected by viewModel.restoreSelected.collectAsStateWithLifecycle()
    val importSettings by viewModel.importSettings.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installs by InstallManager.states.collectAsStateWithLifecycle()
    val installedChanged by InstallManager.installedChanged.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val running = state is BackupState.Running
    // Read afresh each time the page is opened: a backup may have been written since.
    LaunchedEffect(tree) { viewModel.loadFolder() }
    // An install that has gone through changes what the folder has to offer this device.
    LaunchedEffect(installedChanged) { if (installedChanged > 0) viewModel.refreshStatuses() }
    StorePage(title = stringResource(R.string.action_restore_apps), onBack = onBack, scrolling = false) {
        val read = folder
        FolderChooser(tree, name = read?.name, enabled = !running, onChosen = viewModel::chooseTree)
        when {
            tree == null -> Spacer(Modifier.weight(1f))
            read == null -> {
                Text(
                    text = stringResource(if (folderError && !reading) R.string.backup_folder_unavailable else R.string.restore_reading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (folderError && !reading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                Spacer(Modifier.weight(1f))
            }
            read.entries.isEmpty() -> {
                Text(
                    text = stringResource(R.string.restore_nothing),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                Spacer(Modifier.weight(1f))
            }
            else -> {
                val chosen = read.entries.filter { it.status.selectable && it.entry.packageName in selected }
                SelectionBar(
                    count = chosen.size,
                    bytes = chosen.sumOf { it.entry.size },
                    enabled = !running,
                    onAll = { viewModel.selectAllRestore(true) },
                    onNone = { viewModel.selectAllRestore(false) }
                )
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (read.settings != null) {
                        item(key = AppBackup.SETTINGS_NAME) {
                            CheckRow(
                                checked = importSettings,
                                enabled = !running,
                                onCheckedChange = viewModel::setImportSettings,
                                title = stringResource(R.string.restore_import_settings),
                                detail = AppBackup.SETTINGS_NAME,
                                note = null,
                                noteIsError = false
                            )
                        }
                    }
                    items(read.entries, key = { it.entry.packageName }) { row ->
                        val entry = row.entry
                        val install = installs[localKey(entry.packageName)]
                        val note = when {
                            install is InstallState.Failed -> failureText(install)
                            install != null -> stringResource(R.string.restore_installing)
                            else -> statusText(row.status)
                        }
                        CheckRow(
                            checked = row.status.selectable && entry.packageName in selected,
                            enabled = row.status.selectable && !running && install == null,
                            onCheckedChange = { viewModel.toggleRestore(entry.packageName) },
                            title = entry.label,
                            detail = listOf(version(entry), Formatter.formatShortFileSize(context, entry.size)).joinToString(" · "),
                            note = note,
                            noteIsError = install is InstallState.Failed
                        )
                    }
                }
            }
        }
        JobFooter(
            state = state,
            canStart = read != null && (
                read.entries.any { it.status.selectable && it.entry.packageName in selected } ||
                    (importSettings && read.settings != null)
                ),
            startLabel = R.string.action_restore,
            onStart = { viewModel.startRestore(onImportSettings) },
            onCancel = viewModel::cancel,
            onDismiss = viewModel::dismiss
        )
    }
}

/** The folder in use, with the way to choose another; asks for one at once when none is chosen. */
@Composable
private fun FolderChooser(tree: Uri?, name: String?, enabled: Boolean, onChosen: (Uri) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onChosen(uri)
    }
    var noPicker by rememberSaveable { mutableStateOf(false) }
    fun open() {
        try {
            picker.launch(tree)
        } catch (_: ActivityNotFoundException) {
            noPicker = true
        }
    }
    // Asked once per visit: a user who closed the picker without choosing is not asked again
    // until they come back, and a turn of the screen does not ask either.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tree) {
        if (tree == null && !asked) {
            asked = true
            open()
        }
    }
    Text(
        text = when {
            noPicker -> stringResource(R.string.backup_no_picker)
            tree == null -> stringResource(R.string.backup_no_folder)
            else -> stringResource(R.string.backup_folder, name ?: folderName(tree))
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (noPicker) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
    StoreOutlinedButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            open()
        },
        enabled = enabled,
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Text(stringResource(if (tree == null) R.string.action_choose_folder else R.string.action_change_folder))
    }
}

/** The folder's path as the provider names it, for a folder whose own name has not been read. */
internal fun folderName(tree: Uri): String {
    val id = try {
        DocumentsContract.getTreeDocumentId(tree)
    } catch (_: IllegalArgumentException) {
        return tree.toString()
    }
    return id.substringAfter(':').ifEmpty { id }
}

/** How many are chosen and how much they weigh, with the way to choose all or none. */
@Composable
private fun SelectionBar(count: Int, bytes: Long, enabled: Boolean, onAll: () -> Unit, onNone: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Text(
        text = pluralStringResource(R.plurals.backup_selected, count, count, Formatter.formatShortFileSize(context, bytes)),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 16.dp)
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    ) {
        StoreOutlinedButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onAll()
            },
            enabled = enabled
        ) {
            Text(stringResource(R.string.backup_select_all))
        }
        StoreOutlinedButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onNone()
            },
            enabled = enabled
        ) {
            Text(stringResource(R.string.backup_select_none))
        }
    }
}

/** A row of the list: a box to tick, the app's name, a line about it, and a note when there is one. */
@Composable
private fun CheckRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    detail: String,
    note: String?,
    noteIsError: Boolean
) {
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = {
                    haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    onCheckedChange(it)
                }
            )
            .padding(vertical = 8.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (noteIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The job's standing at the foot of the page: the way to begin it, its progress, or how it ended. */
@Composable
private fun JobFooter(
    state: BackupState,
    canStart: Boolean,
    startLabel: Int,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        when (state) {
            BackupState.Idle -> StoreButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onStart()
                },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(startLabel))
            }
            is BackupState.Running -> {
                if (state.bytesTotal > 0) {
                    val fraction = (state.bytesDone.toFloat() / state.bytesTotal).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (state.total > 0) {
                    Text(
                        text = stringResource(R.string.backup_progress, state.index, state.total, state.label),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                StoreOutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onCancel()
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            is BackupState.Done -> {
                Text(
                    text = if (state.restoring) {
                        stringResource(R.string.restore_done, state.saved, state.failed.size)
                    } else {
                        stringResource(R.string.backup_done, state.saved, state.skipped, state.failed.size)
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                if (state.failed.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.backup_failed_apps, state.failed.joinToString(", ")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (!state.indexWritten) IndexNotWritten()
                StoreOutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onDismiss()
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.action_done))
                }
            }
            is BackupState.Failed -> {
                val cancelled = state.reason == BackupError.CANCELLED
                Text(
                    text = stringResource(if (cancelled) R.string.backup_cancelled else R.string.backup_folder_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (cancelled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                )
                if (!state.indexWritten) IndexNotWritten()
                StoreOutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onDismiss()
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }
}

/** The apps were copied, but not the folder's index: said in the colour of an error, since a restore then reads more slowly. */
@Composable
private fun IndexNotWritten() {
    Text(
        text = stringResource(R.string.backup_index_not_written),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp)
    )
}

private fun version(entry: BackupEntry): String = entry.versionName ?: entry.versionCode.toString()

@Composable
private fun installerName(installer: String?): String =
    Installers.knownName(installer)
        ?: if (Installers.byHand(installer)) stringResource(R.string.backup_installer_by_hand) else installer.orEmpty()

@Composable
private fun statusText(status: RestoreStatus): String = stringResource(
    when (status) {
        RestoreStatus.NOT_INSTALLED -> R.string.restore_status_not_installed
        RestoreStatus.OLDER_INSTALLED -> R.string.restore_status_older
        RestoreStatus.SAME_INSTALLED -> R.string.restore_status_same
        RestoreStatus.NEWER_INSTALLED -> R.string.restore_status_newer
        RestoreStatus.OTHER_SIGNER -> R.string.restore_status_other_signer
        RestoreStatus.NEEDS_NEWER_ANDROID -> R.string.restore_status_needs_newer_android
        RestoreStatus.FILES_MISSING -> R.string.restore_status_files_missing
    }
)

@Composable
private fun failureText(failure: InstallState.Failed): String {
    val reason = stringResource(
        when (failure.reason) {
            FailReason.INVALID_APK -> R.string.restore_fail_invalid
            FailReason.SIGNATURE_MISMATCH -> R.string.fail_signature
            FailReason.DOWNLOAD, FailReason.INSTALL -> R.string.fail_install
        }
    )
    val detail = failure.detail?.takeIf { failure.reason == FailReason.INSTALL && it.isNotBlank() }
    return if (detail != null) "$reason $detail" else reason
}
