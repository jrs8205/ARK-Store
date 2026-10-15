package org.jarsi.arkstore.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import java.time.LocalDate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkstore.R
import org.jarsi.arkstore.backup.AppBackup
import org.jarsi.arkstore.backup.BackupCandidate
import org.jarsi.arkstore.backup.BackupEntry
import org.jarsi.arkstore.backup.BackupError
import org.jarsi.arkstore.backup.BackupState
import org.jarsi.arkstore.backup.Installers
import org.jarsi.arkstore.backup.RestoreStatus
import org.jarsi.arkstore.backup.ZipBackup
import org.jarsi.arkstore.install.FailReason
import org.jarsi.arkstore.install.InstallManager
import org.jarsi.arkstore.install.InstallState
import org.jarsi.arkstore.install.localKey

/**
 * Chooses the installed apps to copy, and copies them to the folder or to a zip file. The
 * list of apps has the height of the page: the search and the ways to choose stay above
 * it, and what goes with the apps (their data, the password) and where they go stay below.
 */
@Composable
internal fun BackupScreen(viewModel: BackupViewModel, settingsJson: () -> String, onBack: () -> Unit) {
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dataAvailable by viewModel.dataAvailable.collectAsStateWithLifecycle()
    val includeData by viewModel.includeData.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val installedChanged by InstallManager.installedChanged.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val running = state is BackupState.Running
    val withData = dataAvailable && includeData
    var noPicker by rememberSaveable { mutableStateOf(false) }
    // A save to a folder not chosen yet asks for one first, and goes on once it is chosen.
    var saveWhenChosen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var outsidePlayOnly by rememberSaveable { mutableStateOf(false) }
    var explainWays by rememberSaveable { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            viewModel.chooseTree(uri)
            if (saveWhenChosen) viewModel.startBackup(settingsJson())
        }
        saveWhenChosen = false
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZipBackup.MIME_TYPE)) { uri ->
        if (uri != null) viewModel.startZipBackup(uri, settingsJson())
    }
    // Read on coming here and again after every install: the versions and files change with them.
    LaunchedEffect(installedChanged) { viewModel.loadCandidates() }
    LaunchedEffect(Unit) { viewModel.checkDataAvailable() }
    StorePage(title = stringResource(R.string.action_backup_apps), onBack = onBack, scrolling = false) {
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
            val shown = list.filter { candidate ->
                (!outsidePlayOnly || Installers.preselected(candidate.entry.installer)) && matches(candidate.entry, query)
            }
            SearchField(query = query, onQueryChange = { query = it }, modifier = Modifier.padding(top = 4.dp))
            SelectionTools(
                count = chosen.size,
                bytes = chosen.sumOf { it.entry.size + if (withData) dataBytes(it) else 0 },
                enabled = !running,
                onAll = { viewModel.selectAll(true, shown.map { it.entry.packageName }) },
                onNone = { viewModel.selectAll(false, shown.map { it.entry.packageName }) },
                filterLabel = stringResource(R.string.backup_filter_outside_play),
                filterOn = outsidePlayOnly,
                onFilter = { outsidePlayOnly = !outsidePlayOnly }
            )
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (shown.isEmpty()) {
                    item(key = "none") { NoMatch() }
                }
                items(shown, key = { it.entry.packageName }) { candidate ->
                    val entry = candidate.entry
                    val data = candidate.dataSize?.takeIf { withData }?.let {
                        stringResource(R.string.backup_data_size, Formatter.formatShortFileSize(context, it))
                    }
                    CheckRow(
                        checked = entry.packageName in selected && candidate.readable,
                        enabled = candidate.readable && !running,
                        onCheckedChange = { viewModel.toggle(entry.packageName) },
                        title = entry.label,
                        detail = listOfNotNull(
                            version(entry),
                            installerName(entry.installer),
                            Formatter.formatShortFileSize(context, entry.size),
                            data
                        ).joinToString(" · "),
                        note = if (candidate.readable) null else stringResource(R.string.backup_unreadable),
                        noteIsError = false
                    )
                }
            }
        }
        val canStart = list != null && list.any { it.readable && it.entry.packageName in selected }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        JobFooter(state = state, onCancel = viewModel::cancel, onDismiss = viewModel::dismiss) {
            if (dataAvailable) {
                val chosenData = candidates.orEmpty().filter { it.entry.packageName in selected }.sumOf { dataBytes(it) }
                CheckRow(
                    checked = includeData,
                    enabled = !running,
                    onCheckedChange = viewModel::setIncludeData,
                    title = stringResource(R.string.backup_include_data),
                    detail = stringResource(R.string.backup_include_data_detail, Formatter.formatShortFileSize(context, chosenData)),
                    note = null,
                    noteIsError = false
                )
                if (includeData) {
                    PasswordField(
                        value = password,
                        onValueChange = viewModel::setPassword,
                        enabled = !running,
                        label = stringResource(R.string.backup_password_label),
                        supporting = stringResource(
                            if (password.isEmpty()) R.string.backup_password_empty else R.string.backup_password_hint
                        ),
                        warn = password.isEmpty()
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StoreButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        if (tree != null) {
                            viewModel.startBackup(settingsJson())
                        } else {
                            saveWhenChosen = true
                            noPicker = !launch { folderPicker.launch(null) }
                        }
                    },
                    enabled = canStart,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_save_backup))
                }
                StoreOutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        noPicker = !launch { zipPicker.launch(ZipBackup.fileName(LocalDate.now())) }
                    },
                    enabled = canStart,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_to_zip))
                }
            }
            if (explainWays) {
                Text(
                    text = stringResource(R.string.backup_ways),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            DestinationLine(
                text = when {
                    noPicker -> stringResource(R.string.backup_no_picker)
                    tree == null -> stringResource(R.string.backup_no_folder)
                    else -> stringResource(R.string.backup_folder, folderName(tree!!))
                },
                error = noPicker,
                enabled = !running,
                actions = listOf(
                    stringResource(if (tree == null) R.string.action_choose_folder else R.string.action_change_folder) to {
                        noPicker = !launch { folderPicker.launch(tree) }
                    },
                    stringResource(R.string.backup_ways_question) to { explainWays = !explainWays }
                )
            )
        }
    }
}

/** Shows what the folder or the zip file holds against this device, and installs what is chosen. */
@Composable
internal fun RestoreScreen(viewModel: BackupViewModel, onImportSettings: (Uri) -> Unit, onBack: () -> Unit) {
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val zip by viewModel.zip.collectAsStateWithLifecycle()
    val readRequests by viewModel.readRequests.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val reading by viewModel.reading.collectAsStateWithLifecycle()
    val folderError by viewModel.folderError.collectAsStateWithLifecycle()
    val selected by viewModel.restoreSelected.collectAsStateWithLifecycle()
    val importSettings by viewModel.importSettings.collectAsStateWithLifecycle()
    val dataAvailable by viewModel.dataAvailable.collectAsStateWithLifecycle()
    val restoreData by viewModel.restoreData.collectAsStateWithLifecycle()
    val restorePassword by viewModel.restorePassword.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installs by InstallManager.states.collectAsStateWithLifecycle()
    val installedChanged by InstallManager.installedChanged.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val running = state is BackupState.Running
    var noPicker by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var missingOnly by rememberSaveable { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.chooseTree(uri)
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.chooseZip(uri)
    }
    val isZip = zip != null
    // Read afresh each time the page is opened or another backup is chosen: one may have been written since.
    LaunchedEffect(tree, zip, readRequests) { viewModel.loadFolder() }
    // An install that has gone through changes what the backup has to offer this device.
    LaunchedEffect(installedChanged) { if (installedChanged > 0) viewModel.refreshStatuses() }
    LaunchedEffect(Unit) { viewModel.checkDataAvailable() }
    StorePage(title = stringResource(R.string.action_restore_apps), onBack = onBack, scrolling = false) {
        val read = folder
        DestinationLine(
            text = when {
                noPicker -> stringResource(R.string.backup_no_picker)
                zip != null -> stringResource(R.string.backup_zip_file, read?.name ?: zip!!.lastPathSegment.orEmpty())
                tree != null -> stringResource(R.string.backup_folder, read?.name ?: folderName(tree!!))
                else -> stringResource(R.string.restore_choose_source)
            },
            error = noPicker,
            enabled = !running,
            actions = listOf(
                stringResource(if (tree == null || isZip) R.string.action_choose_folder else R.string.action_change_folder) to {
                    noPicker = !launch { folderPicker.launch(tree) }
                },
                stringResource(R.string.action_choose_zip) to {
                    noPicker = !launch { zipPicker.launch(arrayOf("*/*")) }
                }
            )
        )
        val hasData = read?.entries?.any { it.entry.data.isNotEmpty() } == true
        val withData = hasData && dataAvailable && restoreData
        when {
            zip == null && tree == null -> Spacer(Modifier.weight(1f))
            read == null -> {
                val failed = folderError && !reading
                Text(
                    text = stringResource(
                        when {
                            !failed -> R.string.restore_reading
                            isZip -> R.string.backup_zip_unreadable
                            else -> R.string.backup_folder_unavailable
                        }
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                Spacer(Modifier.weight(1f))
            }
            read.entries.isEmpty() -> {
                Text(
                    text = stringResource(if (read.zip) R.string.restore_zip_nothing else R.string.restore_nothing),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                Spacer(Modifier.weight(1f))
            }
            else -> {
                val chosen = read.entries.filter { it.status.selectable && it.entry.packageName in selected }
                val shown = read.entries.filter { row -> (!missingOnly || row.status.preselected) && matches(row.entry, query) }
                SearchField(query = query, onQueryChange = { query = it }, modifier = Modifier.padding(top = 4.dp))
                SelectionTools(
                    count = chosen.size,
                    bytes = chosen.sumOf { it.entry.size + if (withData) it.entry.dataSize else 0 },
                    enabled = !running,
                    onAll = { viewModel.selectAllRestore(true, shown.map { it.entry.packageName }) },
                    onNone = { viewModel.selectAllRestore(false, shown.map { it.entry.packageName }) },
                    filterLabel = stringResource(R.string.restore_filter_missing),
                    filterOn = missingOnly,
                    onFilter = { missingOnly = !missingOnly }
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
                    if (shown.isEmpty()) {
                        item(key = "none") { NoMatch() }
                    }
                    items(shown, key = { it.entry.packageName }) { row ->
                        val entry = row.entry
                        val install = installs[localKey(entry.packageName)]
                        val note = when {
                            install is InstallState.Failed -> failureText(install)
                            install != null -> stringResource(R.string.restore_installing)
                            else -> statusText(row.status)
                        }
                        val data = when {
                            entry.data.isEmpty() -> null
                            entry.data.any { it.encrypted } -> stringResource(R.string.restore_data_encrypted)
                            else -> stringResource(R.string.restore_data)
                        }
                        CheckRow(
                            checked = row.status.selectable && entry.packageName in selected,
                            enabled = row.status.selectable && !running && install == null,
                            onCheckedChange = { viewModel.toggleRestore(entry.packageName) },
                            title = entry.label,
                            detail = listOfNotNull(version(entry), Formatter.formatShortFileSize(context, entry.size), data)
                                .joinToString(" · "),
                            note = note,
                            noteIsError = install is InstallState.Failed
                        )
                    }
                }
            }
        }
        val canStart = read != null && (
            read.entries.any { it.status.selectable && it.entry.packageName in selected } ||
                (importSettings && read.settings != null)
            )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        JobFooter(state = state, onCancel = viewModel::cancel, onDismiss = viewModel::dismiss) {
            if (hasData && dataAvailable) {
                CheckRow(
                    checked = restoreData,
                    enabled = !running,
                    onCheckedChange = viewModel::setRestoreData,
                    title = stringResource(R.string.restore_include_data),
                    detail = stringResource(R.string.restore_include_data_detail),
                    note = null,
                    noteIsError = false
                )
                val chosen = read?.entries.orEmpty().filter { it.status.selectable && it.entry.packageName in selected }
                if (restoreData && chosen.any { row -> row.entry.data.any { it.encrypted } }) {
                    PasswordField(
                        value = restorePassword,
                        onValueChange = viewModel::setRestorePassword,
                        enabled = !running,
                        label = stringResource(R.string.restore_password_label),
                        supporting = null,
                        warn = false
                    )
                }
            } else if (hasData) {
                Text(
                    text = stringResource(R.string.restore_data_needs_root),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                StoreButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.startRestore(onImportSettings)
                    },
                    enabled = canStart,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_restore))
                }
            }
        }
    }
}

/** Opens a picker through [open]; false when the device has no app to pick with. */
private inline fun launch(open: () -> Unit): Boolean = try {
    open()
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** Whether [entry] is one the user looks for with [query]: by its name or its package. */
private fun matches(entry: BackupEntry, query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || entry.label.contains(q, ignoreCase = true) || entry.packageName.contains(q, ignoreCase = true)
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

/**
 * Where the backup goes or comes from, with the ways to change it as small buttons after
 * the text, on one line where they fit and wrapped where they do not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DestinationLine(text: String, error: Boolean, enabled: Boolean, actions: List<Pair<String, () -> Unit>>) {
    val haptics = LocalHapticFeedback.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        for ((label, action) in actions) {
            // No room at the start: a button that wraps to a line of its own begins where the text does.
            TextButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    action()
                },
                enabled = enabled,
                contentPadding = PaddingValues(start = 0.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
            ) {
                Text(label)
            }
        }
    }
}

/**
 * How many are chosen and how much they weigh, with the ways to choose all or none of the
 * apps on the screen, and a chip that narrows the screen to [filterLabel].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionTools(
    count: Int,
    bytes: Long,
    enabled: Boolean,
    onAll: () -> Unit,
    onNone: () -> Unit,
    filterLabel: String,
    filterOn: Boolean,
    onFilter: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Text(
        text = pluralStringResource(R.plurals.backup_selected, count, count, Formatter.formatShortFileSize(context, bytes)),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
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
        ChoiceChip(selected = filterOn, label = filterLabel, onClick = onFilter)
    }
}

/** Nothing on the screen: the search or the filter leaves no app. */
@Composable
private fun NoMatch() {
    Text(
        text = stringResource(R.string.backup_no_match),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp)
    )
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

/** The job's standing at the foot of the page: the ways to begin it ([idle]), its progress, or how it ended. */
@Composable
private fun JobFooter(
    state: BackupState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    idle: @Composable () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        when (state) {
            BackupState.Idle -> idle()
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
                    text = when {
                        state.restoring -> stringResource(R.string.restore_done, state.saved, state.failed.size)
                        state.zip -> pluralStringResource(R.plurals.backup_zip_done, state.saved, state.saved)
                        else -> stringResource(R.string.backup_done, state.saved, state.skipped, state.failed.size)
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                if (state.failed.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            if (state.zip && !state.restoring) R.string.backup_zip_left_out else R.string.backup_failed_apps,
                            state.failed.joinToString(", ")
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (state.dataFailed.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            if (state.restoring) R.string.restore_data_failed else R.string.backup_data_failed,
                            state.dataFailed.joinToString(", ")
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (state.wrongPassword) {
                    Text(
                        text = stringResource(R.string.restore_wrong_password),
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
                    text = stringResource(
                        when {
                            state.zip && !state.restoring && cancelled -> R.string.backup_zip_cancelled
                            state.zip && !state.restoring -> R.string.backup_zip_failed
                            state.zip && !cancelled -> R.string.backup_zip_unreadable
                            cancelled -> R.string.backup_cancelled
                            else -> R.string.backup_folder_unavailable
                        }
                    ),
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

/** About how much data [candidate] has, as far as root has told; nothing until then. */
private fun dataBytes(candidate: BackupCandidate): Long = candidate.dataSize ?: 0L

/** A field for the password of the data, shown as dots, with a line under it that may [warn]. */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    label: String,
    supporting: String?,
    warn: Boolean
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        supportingText = supporting?.let { text ->
            {
                Text(
                    text = text,
                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    )
}

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
