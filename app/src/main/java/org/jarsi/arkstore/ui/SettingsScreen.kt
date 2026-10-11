package org.jarsi.arkstore.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkstore.BuildConfig
import org.jarsi.arkstore.R
import org.jarsi.arkstore.data.Backup
import org.jarsi.arkstore.data.StoreApp
import org.jarsi.arkstore.work.AutoUpdate
import org.jarsi.arkstore.work.UpdateCheckWorker

/**
 * A page opened from the list: a bar with the way back and the [title], and below it [content]
 * that scrolls. With materials on, the page lies on the same plate of metal as the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorePage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    /** Whether the content scrolls as a whole; a page with a list of its own scrolls that instead. */
    scrolling: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val background = MaterialTheme.colorScheme.background
    val metal = LocalMaterial.current
    val haptics = LocalHapticFeedback.current
    Scaffold(
        containerColor = if (metal && Surfaces.takes(background)) Color.Transparent else background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (metal) Color.Transparent else MaterialTheme.colorScheme.surface
                ),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            onBack()
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                actions = actions
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .then(if (scrolling) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .navigationBarsPadding(),
            content = content
        )
    }
}

/** The settings, as a page of their own; the backup and the restore open from it. */
@Composable
internal fun SettingsScreen(
    viewModel: StoreViewModel,
    palette: Palette,
    onPaletteChange: (Palette) -> Unit,
    black: Boolean,
    onBlackChange: (Boolean) -> Unit,
    material: Boolean,
    onMaterialChange: (Boolean) -> Unit,
    hideTop: Boolean,
    onHideTopChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    StorePage(title = stringResource(R.string.settings_title), onBack = onBack) {
        BetaSwitch(viewModel)
        Text(
            text = stringResource(R.string.beta_return_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        OriginSettings(viewModel)
        GitHubSettings(viewModel)
        ThemePicker(
            heading = stringResource(R.string.appearance_title),
            palette = palette,
            onPaletteChange = onPaletteChange
        )
        SettingSwitch(
            heading = null,
            label = stringResource(R.string.black_switch),
            description = stringResource(R.string.black_description),
            checked = black,
            onCheckedChange = onBlackChange
        )
        SettingSwitch(
            heading = null,
            label = stringResource(R.string.hide_top_switch),
            description = stringResource(R.string.hide_top_description),
            checked = hideTop,
            onCheckedChange = onHideTopChange
        )
        if (Surfaces.supported) {
            // A device whose graphics driver could not draw the materials keeps the flat
            // colours, and the switch says so rather than disappearing.
            val unavailable = Surfaces.failed(LocalContext.current)
            SettingSwitch(
                heading = null,
                label = stringResource(R.string.material_switch),
                description = stringResource(
                    if (unavailable) R.string.material_unavailable else R.string.material_description
                ),
                checked = material && !unavailable,
                enabled = !unavailable,
                onCheckedChange = onMaterialChange
            )
        }
        AutoUpdateSettings(viewModel)
        NotificationSetting()
        TransferSettings(viewModel)
        AppBackupSettings(onBackup, onRestore)
        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp)
        )
    }
}

/**
 * The personal access token for GitHub, see [GitHubToken]: typed and checked with GitHub
 * before it is kept, and shown only as whether one is held and what it allows.
 */
@Composable
private fun GitHubSettings(viewModel: StoreViewModel) {
    val state by viewModel.token.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    // Not saved with the screen's state: a token belongs in its own preferences alone.
    var input by remember { mutableStateOf("") }
    LaunchedEffect(state.present) {
        if (state.present) input = ""
    }
    Text(
        text = stringResource(R.string.github_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 24.dp)
            .semantics { heading() }
    )
    Text(
        text = stringResource(R.string.github_token_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
    Text(
        text = if (state.present) {
            val limit = state.limit
            if (limit != null) {
                stringResource(R.string.github_token_saved, fullNumber(limit.toLong()))
            } else {
                stringResource(R.string.github_token_saved_unknown)
            }
        } else {
            stringResource(R.string.github_token_none)
        },
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp)
    )
    if (state.present) {
        StoreOutlinedButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                viewModel.removeToken()
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(stringResource(R.string.action_remove_token))
        }
        return
    }
    OutlinedTextField(
        value = input,
        onValueChange = {
            input = it
            viewModel.clearTokenError()
        },
        enabled = !state.checking,
        label = { Text(stringResource(R.string.github_token_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = state.error != null,
        supportingText = state.error?.let { error ->
            {
                Text(
                    stringResource(
                        when (error) {
                            TokenError.REJECTED -> R.string.github_token_rejected
                            TokenError.NETWORK -> R.string.error_network
                            TokenError.RATE_LIMIT -> R.string.error_rate_limit
                        }
                    )
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) viewModel.saveToken(input) }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    )
    Button(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            viewModel.saveToken(input)
        },
        enabled = input.isNotBlank() && !state.checking,
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Text(stringResource(R.string.action_save_token))
    }
}

/**
 * Whether the background check installs updates unasked, and when; see [AutoUpdate]. Before
 * Android 12 the switch stays off and says why.
 */
@Composable
private fun AutoUpdateSettings(viewModel: StoreViewModel) {
    val context = LocalContext.current
    val imports by viewModel.imports.collectAsStateWithLifecycle()
    var settings by remember(imports) { mutableStateOf(AutoUpdate.read(context)) }
    val supported = Build.VERSION.SDK_INT >= AutoUpdate.SUPPORTED_SDK
    fun change(changed: AutoUpdate) {
        settings = changed
        AutoUpdate.write(context, changed)
    }
    SettingSwitch(
        heading = stringResource(R.string.auto_update_title),
        label = stringResource(R.string.auto_update_switch),
        description = stringResource(
            if (supported) R.string.auto_update_description else R.string.auto_update_unsupported
        ),
        checked = settings.enabled && supported,
        enabled = supported,
        onCheckedChange = { change(settings.copy(enabled = it)) }
    )
    SettingSwitch(
        heading = null,
        label = stringResource(R.string.auto_update_unmetered_switch),
        description = stringResource(R.string.auto_update_unmetered_description),
        checked = settings.unmeteredOnly,
        enabled = supported && settings.enabled,
        onCheckedChange = { change(settings.copy(unmeteredOnly = it)) }
    )
    SettingSwitch(
        heading = null,
        label = stringResource(R.string.auto_update_charging_switch),
        description = stringResource(R.string.auto_update_charging_description),
        checked = settings.chargingOnly,
        enabled = supported && settings.enabled,
        onCheckedChange = { change(settings.copy(chargingOnly = it)) }
    )
}

/**
 * Saves the sources, the bookmarks and the settings to a file the user picks, and brings
 * them back from one; see [Backup]. The system's file picker does the picking.
 */
@Composable
private fun TransferSettings(viewModel: StoreViewModel) {
    val state by viewModel.transfer.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME_TYPE)) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }
    Text(
        text = stringResource(R.string.transfer_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 24.dp)
            .semantics { heading() }
    )
    Text(
        text = stringResource(R.string.transfer_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        StoreOutlinedButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                export.launch(Backup.FILE_NAME)
            },
            enabled = !state.busy
        ) {
            Text(stringResource(R.string.action_export))
        }
        StoreOutlinedButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                import.launch(arrayOf("*/*"))
            },
            enabled = !state.busy
        ) {
            Text(stringResource(R.string.action_import))
        }
    }
    val imported = state.imported
    val outcome = when {
        state.error == TransferError.NOT_A_BACKUP -> stringResource(R.string.transfer_not_backup)
        state.error == TransferError.FAILED -> stringResource(R.string.transfer_failed)
        imported != null -> stringResource(R.string.transfer_imported, imported.first, imported.second)
        state.exported -> stringResource(R.string.transfer_exported)
        else -> null
    }
    if (outcome != null) {
        Text(
            text = outcome,
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Leads to the pages that copy the installed apps to a folder and bring them back from one. */
@Composable
private fun AppBackupSettings(onBackup: () -> Unit, onRestore: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Text(
        text = stringResource(R.string.backup_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 24.dp)
            .semantics { heading() }
    )
    Text(
        text = stringResource(R.string.backup_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
    Text(
        text = stringResource(R.string.backup_ways),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        StoreOutlinedButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onBackup()
            }
        ) {
            Text(stringResource(R.string.action_backup_apps))
        }
        StoreOutlinedButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onRestore()
            }
        ) {
            Text(stringResource(R.string.action_restore_apps))
        }
    }
}

/**
 * Shows whether update notifications can reach the user and leads to the system page where they
 * are turned on. The system decides this (permission, app and channel switches), so the app
 * reports the state instead of keeping a switch of its own that could disagree with it.
 */
@Composable
private fun NotificationSetting() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var enabled by remember { mutableStateOf(UpdateCheckWorker.notificationsEnabled(context)) }
    // The user may come back from the system settings with a different answer.
    LifecycleResumeEffect(Unit) {
        enabled = UpdateCheckWorker.notificationsEnabled(context)
        onPauseOrDispose {}
    }

    Text(
        text = stringResource(R.string.notifications_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 24.dp)
            .semantics { heading() }
    )
    Text(
        text = stringResource(
            if (enabled) R.string.notifications_on else R.string.notifications_off
        ),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 8.dp)
    )
    StoreOutlinedButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        },
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Text(stringResource(R.string.action_notification_settings))
    }
}

@Composable
private fun BetaSwitch(viewModel: StoreViewModel) {
    val includeBeta by viewModel.includeBeta.collectAsStateWithLifecycle()
    SettingSwitch(
        heading = stringResource(R.string.beta_title),
        label = stringResource(R.string.beta_switch),
        description = stringResource(R.string.beta_description),
        checked = includeBeta,
        onCheckedChange = viewModel::setIncludeBeta
    )
}

/**
 * Where the listed apps come from: what developers have published to the store, which is
 * always shown, and a switch for each further place the store can list apps from.
 */
@Composable
private fun OriginSettings(viewModel: StoreViewModel) {
    val includeAuto by viewModel.includeAuto.collectAsStateWithLifecycle()
    val catalogues by viewModel.catalogues.collectAsStateWithLifecycle()
    Text(
        text = stringResource(R.string.origins_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 16.dp)
            .semantics { heading() }
    )
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.origin_published),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = stringResource(R.string.origin_published_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    SettingSwitch(
        heading = null,
        label = stringResource(R.string.auto_switch),
        description = stringResource(R.string.auto_description),
        checked = includeAuto,
        onCheckedChange = viewModel::setIncludeAuto
    )
    SettingSwitch(
        heading = null,
        label = stringResource(R.string.codeberg_switch),
        description = stringResource(R.string.codeberg_description),
        checked = StoreApp.SOURCE_CODEBERG in catalogues,
        onCheckedChange = { viewModel.setCatalogue(StoreApp.SOURCE_CODEBERG, it) }
    )
    SettingSwitch(
        heading = null,
        label = stringResource(R.string.gitlab_switch),
        description = stringResource(R.string.gitlab_description),
        checked = StoreApp.SOURCE_GITLAB in catalogues,
        onCheckedChange = { viewModel.setCatalogue(StoreApp.SOURCE_GITLAB, it) }
    )
    SettingSwitch(
        heading = null,
        label = stringResource(R.string.source_izzy),
        description = stringResource(R.string.izzy_description),
        checked = StoreApp.SOURCE_IZZY in catalogues,
        onCheckedChange = { viewModel.setCatalogue(StoreApp.SOURCE_IZZY, it) }
    )
    SettingSwitch(
        heading = null,
        label = stringResource(R.string.source_fdroid),
        description = stringResource(R.string.fdroid_description),
        checked = StoreApp.SOURCE_FDROID in catalogues,
        onCheckedChange = { viewModel.setCatalogue(StoreApp.SOURCE_FDROID, it) }
    )
    Text(
        text = stringResource(R.string.origins_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun SettingSwitch(
    heading: String?,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current
    if (heading != null) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(top = 16.dp)
                .semantics { heading() }
        )
    }
    // The whole row is the switch, so the label is part of what gets announced and tapped.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    haptics.performHapticFeedback(
                        if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
                    )
                    onCheckedChange(it)
                }
            )
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
