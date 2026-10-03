package org.jarsi.arkstore.ui

import android.content.Context
import android.icu.text.CompactDecimalFormat
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.NumberFormat
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import org.jarsi.arkstore.BuildConfig
import org.jarsi.arkstore.R
import org.jarsi.arkstore.data.AppStatus
import org.jarsi.arkstore.data.CatalogRules
import org.jarsi.arkstore.data.Categories
import org.jarsi.arkstore.data.StoreApp
import org.jarsi.arkstore.install.FailReason
import org.jarsi.arkstore.install.InstallManager
import org.jarsi.arkstore.install.InstallState
import org.jarsi.arkstore.work.UpdateCheckWorker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(viewModel: StoreViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installs by InstallManager.states.collectAsStateWithLifecycle()
    val catalogues by viewModel.catalogues.collectAsStateWithLifecycle()
    var selectedRepo by rememberSaveable { mutableStateOf<String?>(null) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var place by rememberSaveable { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var searchFocused by remember { mutableStateOf(false) }
    var searchBounds by remember { mutableStateOf(Rect.Zero) }
    val preferences = remember { context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE) }
    var sortOrder by remember {
        mutableStateOf(
            SortOrder.entries.firstOrNull { it.name == preferences.getString(PREF_SORT, null) }
                ?: SortOrder.NAME
        )
    }
    var material by remember { mutableStateOf(preferences.getBoolean(PREF_MATERIAL, false)) }

    InstallHaptics(installs)

    // With materials on, the screen itself is a sheet of brushed metal, and the bar and the
    // list lie on it; see Surfaces.
    CompositionLocalProvider(LocalMaterial provides (material && Surfaces.available)) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .material(MaterialTheme.colorScheme.background, Relief.PLATE, radius = 0.dp, grain = 0.3f)
    ) {
    Scaffold(
        containerColor = if (LocalMaterial.current) Color.Transparent else MaterialTheme.colorScheme.background,
        // The search field gives up the keyboard as soon as the user touches anything else.
        // Watching every touch from here covers each button, chip and list without their
        // having to know about it, and leaves navigation with a keyboard alone.
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (searchFocused && down.position !in searchBounds) focusManager.clearFocus()
            }
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (LocalMaterial.current) Color.Transparent else MaterialTheme.colorScheme.surface
                ),
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            showSources = true
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_sources),
                            contentDescription = stringResource(R.string.action_sources)
                        )
                    }
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            showSettings = true
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.action_settings)
                        )
                    }
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            viewModel.refresh()
                        },
                        enabled = !state.refreshing
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.action_refresh)
                        )
                    }
                }
            )
        }
    ) { padding ->
        // A category that no longer has apps (a source was removed) must not stay selected,
        // and neither may a place.
        val categories = Categories.ALL.filter { id -> state.rows.any { it.app.category == id } }
        val activeCategory = category?.takeIf { it in categories }
        val activePlace = place
        // The places whose apps are turned on in the settings; GitHub always is.
        val enabledPlaces = CatalogRules.PLACES.filter { it == StoreApp.SOURCE_GITHUB || it in catalogues }.toSet()
        // With a search, the apps whose name answers it come first, then those whose
        // repository, developer or package does, and last those matched by their description
        // alone; within each the chosen order holds.
        val words = remember(query) { searchWords(query) }
        val visible = state.rows.mapNotNull { row ->
            val shown = (activeCategory == null || row.app.category == activeCategory) &&
                (activePlace == null || CatalogRules.offeredFrom(row.app, row.alsoFrom, activePlace))
            if (shown) matchRank(row, words)?.let { Pair(row, it) } else null
        }
            .sortedWith(compareBy<Pair<AppRow, Int>> { it.second }.thenBy(sortOrder.comparator) { it.first })
            .map { it.first }
        // A changed search or filter shows its result from the top; a list restored as it
        // was, after a turn of the screen, stays where it was.
        val listState = rememberLazyListState()
        val filterKey = "$query\u0000$activeCategory\u0000$activePlace"
        var shownFor by rememberSaveable { mutableStateOf(filterKey) }
        LaunchedEffect(filterKey) {
            if (shownFor != filterKey) {
                shownFor = filterKey
                listState.scrollToItem(0)
            }
        }
        val pullState = rememberPullToRefreshState()
        PullThresholdHaptics(pullState)

        // The search, the chips, the count and the order: above the list, so that a card
        // scrolling out goes under them instead of taking them along, or, in a window too
        // low to leave the list room beside them, at the top of the list.
        val header: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth()) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .onGloballyPositioned { searchBounds = it.boundsInRoot() }
                        .onFocusChanged { searchFocused = it.hasFocus }
                )
                CategoryChips(
                    categories = categories,
                    counts = state.rows.groupingBy { it.app.category }.eachCount(),
                    total = state.rows.size,
                    selected = activeCategory,
                    onSelect = { category = it }
                )
                PlaceChips(
                    counts = CatalogRules.PLACES.associateWith { place ->
                        state.rows.count { CatalogRules.offeredFrom(it.app, it.alsoFrom, place) }
                    },
                    enabled = enabledPlaces,
                    selected = activePlace,
                    onSelect = { chosen ->
                        // A quick way in: choosing a place that is off turns it on as well.
                        if (chosen != null && chosen !in enabledPlaces) viewModel.setCatalogue(chosen, true)
                        place = chosen
                    }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                ) {
                    Text(
                        text = if (visible.size == state.rows.size) {
                            pluralStringResource(
                                R.plurals.count_apps,
                                state.rows.size,
                                state.rows.size
                            )
                        } else {
                            pluralStringResource(
                                R.plurals.count_apps_filtered,
                                state.rows.size,
                                visible.size,
                                state.rows.size
                            )
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                    SortMenu(
                        selected = sortOrder,
                        onSelect = {
                            sortOrder = it
                            preferences.edit { putString(PREF_SORT, it.name) }
                        }
                    )
                }
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val pinned = maxHeight >= PINNED_HEADER_MIN_HEIGHT
            Column(modifier = Modifier.fillMaxSize()) {
                if (pinned && state.rows.isNotEmpty()) header()
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { viewModel.refresh() },
                    state = pullState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    val updates = visible.filter { it.status == AppStatus.UPDATE_AVAILABLE }
                    val installed = visible.filter {
                        it.status == AppStatus.UP_TO_DATE || it.status == AppStatus.OTHER_SIGNER ||
                            it.status == AppStatus.OTHER_BUILD
                    }
                    val available = visible.filter { it.status == AppStatus.NOT_INSTALLED }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!pinned && state.rows.isNotEmpty()) {
                            // The header keeps its own edges, past the list's side padding.
                            item(key = "header") { Box(Modifier.pastSidePadding(16.dp)) { header() } }
                        }
                        state.error?.let { error ->
                            item(key = "error") { ErrorBanner(error) }
                        }

                        if (state.rows.isNotEmpty() && visible.isEmpty()) {
                            item(key = "no-match") {
                                Text(
                                    text = stringResource(R.string.list_no_match),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(vertical = 32.dp)
                                )
                            }
                        }

                        if (state.rows.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    text = stringResource(
                                        when {
                                            state.refreshing -> R.string.list_loading
                                            state.error != null -> R.string.list_empty_error
                                            else -> R.string.list_empty
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(vertical = 48.dp)
                                        .semantics { liveRegion = LiveRegionMode.Polite }
                                )
                            }
                        }

                        section(
                            key = "updates",
                            rows = updates,
                            installs = installs,
                            viewModel = viewModel,
                            onSelect = { selectedRepo = it },
                            header = {
                                SectionHeader(
                                    title = pluralStringResource(
                                        R.plurals.section_updates,
                                        updates.size,
                                        updates.size
                                    ),
                                    action = if (updates.size > 1) {
                                        stringResource(R.string.action_update_all)
                                    } else {
                                        null
                                    },
                                    onAction = {
                                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        viewModel.installAll(updates.map { it.app })
                                    }
                                )
                            }
                        )
                        section(
                            key = "installed",
                            rows = installed,
                            installs = installs,
                            viewModel = viewModel,
                            onSelect = { selectedRepo = it },
                            header = { SectionHeader(stringResource(R.string.section_installed)) }
                        )
                        section(
                            key = "available",
                            rows = available,
                            installs = installs,
                            viewModel = viewModel,
                            onSelect = { selectedRepo = it },
                            header = { SectionHeader(stringResource(R.string.section_available)) }
                        )

                        if (state.checkedAt > 0) {
                            item(key = "checked") {
                                state.storeDownloads?.let { downloads ->
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.footer_store_downloads,
                                            downloads.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                            fullNumber(downloads)
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 16.dp)
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        R.string.footer_checked,
                                        DateUtils.getRelativeTimeSpanString(
                                            state.checkedAt,
                                            System.currentTimeMillis(),
                                            DateUtils.MINUTE_IN_MILLIS
                                        ).toString()
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        top = if (state.storeDownloads == null) 16.dp else 4.dp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val selected = state.rows.firstOrNull { it.app.fullName == selectedRepo }
    if (selected != null) {
        StoreSheet(onDismissRequest = { selectedRepo = null }) {
            DetailsSheet(selected, onInstall = { viewModel.install(selected.app) })
        }
    }
    if (showSources) {
        StoreSheet(
            onDismissRequest = {
                showSources = false
                viewModel.clearSourceError()
            }
        ) {
            SourcesSheet(viewModel)
        }
    }
    if (showSettings) {
        StoreSheet(onDismissRequest = { showSettings = false }) {
            SettingsSheet(
                viewModel = viewModel,
                material = material,
                onMaterialChange = {
                    material = it
                    preferences.edit { putBoolean(PREF_MATERIAL, it) }
                }
            )
        }
    }
    }
    }
}

/** A bottom sheet, drawn as a plate of metal when materials are on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val metal = LocalMaterial.current
    val color = BottomSheetDefaults.ContainerColor
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = if (metal) Color.Transparent else color,
        // The text is set against the plate's colour, whether the container shows it or not.
        contentColor = contentColorFor(color),
        // The handle keeps the tap and the accessibility actions Material puts around this
        // slot. The plate is drawn from here down behind the whole sheet, which clips it to
        // its shape, so that the plate reaches the top of the sheet in one piece.
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .material(color, Relief.PLATE, radius = 28.dp, reachBelow = 4000.dp)
            ) {
                BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.Center))
            }
        },
        content = content
    )
}

private fun LazyListScope.section(
    key: String,
    rows: List<AppRow>,
    installs: Map<String, InstallState>,
    viewModel: StoreViewModel,
    onSelect: (String) -> Unit,
    header: @Composable () -> Unit
) {
    if (rows.isEmpty()) return
    item(key = "header-$key") { header() }
    items(rows, key = { it.app.fullName }) { row ->
        AppCard(
            row = row,
            install = installs[row.app.fullName],
            onInstall = { viewModel.install(row.app) },
            onDismissFailure = { viewModel.dismissFailure(row.app.fullName) },
            onClick = { onSelect(row.app.fullName) }
        )
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        if (action != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun ErrorBanner(error: LoadError) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Text(
            text = stringResource(
                when (error) {
                    LoadError.NETWORK -> R.string.error_network
                    LoadError.RATE_LIMIT -> R.string.error_rate_limit
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

@Composable
private fun AppCard(
    row: AppRow,
    install: InstallState?,
    onInstall: () -> Unit,
    onDismissFailure: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val app = row.app
    val startInstall: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onInstall()
    }
    val metal = LocalMaterial.current
    val surface = MaterialTheme.colorScheme.surfaceContainer

    Card(
        colors = CardDefaults.cardColors(containerColor = surface),
        // The fill alone is too close to the background to show where a card ends in bright
        // light, so the edge is drawn as well; the metal has a bevelled edge of its own.
        border = if (metal) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // A metal plate casts a shadow; it is drawn here, outside the clip that bounds the
        // touch feedback, where the card's own elevation shadow would be clipped away.
        modifier = Modifier
            .fillMaxWidth()
            .then(if (metal) Modifier.shadow(6.dp, CardDefaults.shape) else Modifier)
            .clip(CardDefaults.shape)
            .clickable(onClickLabel = stringResource(R.string.action_details), onClick = onClick)
    ) {
        // The metal is drawn over the card's own fill, inside its shape, so that the card
        // stays opaque and its shadow falls outside it only.
        Column(
            modifier = Modifier
                .material(surface, Relief.PLATE, radius = 12.dp)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowIcon(app.title, app.packageName, row.installed != null, app.icon)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = app.title, style = MaterialTheme.typography.titleMedium)
                    if (app.prerelease) Badge(stringResource(R.string.badge_beta))
                    sourceBadge(app)?.let { Badge(stringResource(it)) }
                    Text(
                        text = stringResource(R.string.card_byline, origin(app), versionLine(row)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                when (install) {
                    is InstallState.Downloading -> Progress(install.progress)
                    InstallState.Installing -> Progress(null)
                    else -> when (row.status) {
                        AppStatus.UPDATE_AVAILABLE -> StoreButton(onClick = startInstall) {
                            Text(stringResource(R.string.action_update))
                        }
                        AppStatus.NOT_INSTALLED -> StoreButton(onClick = startInstall) {
                            Text(stringResource(R.string.action_install))
                        }
                        AppStatus.UP_TO_DATE, AppStatus.OTHER_SIGNER, AppStatus.OTHER_BUILD -> {
                            val launch = remember(app.packageName) {
                                app.packageName?.let {
                                    context.packageManager.getLaunchIntentForPackage(it)
                                }
                            }
                            if (launch != null) {
                                StoreOutlinedButton(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                        context.startActivity(launch)
                                    }
                                ) {
                                    Text(stringResource(R.string.action_open))
                                }
                            }
                        }
                    }
                }
            }

            if (app.description.isNotBlank()) {
                Text(
                    text = app.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            val note = when {
                row.status == AppStatus.OTHER_SIGNER -> R.string.other_signer_note
                row.status == AppStatus.OTHER_BUILD -> R.string.other_build_note
                row.betaInstalled -> R.string.newer_installed_note
                row.newerInstalled -> R.string.newer_version_note
                else -> null
            }
            if (note != null) {
                Text(
                    text = stringResource(note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            // A catalogue tells neither stars nor downloads.
            if (app.fromRepository) {
                Stats(app.stars, app.downloads, modifier = Modifier.padding(top = 12.dp))
            }

            if (install is InstallState.Failed) {
                FailureRow(install, app.packageName, onRetry = startInstall, onDismiss = onDismissFailure)
            }
        }
    }
}

@Composable
private fun FailureRow(
    failure: InstallState.Failed,
    packageName: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            text = stringResource(
                when (failure.reason) {
                    FailReason.DOWNLOAD -> R.string.fail_download
                    FailReason.INVALID_APK -> R.string.fail_invalid_apk
                    FailReason.SIGNATURE_MISMATCH -> R.string.fail_signature
                    FailReason.INSTALL -> R.string.fail_install
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        if (failure.reason == FailReason.INSTALL && !failure.detail.isNullOrBlank()) {
            Text(
                text = failure.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (failure.reason == FailReason.SIGNATURE_MISMATCH && packageName != null) {
                TextButton(onClick = { uninstall(context, packageName) }) {
                    Text(stringResource(R.string.action_uninstall))
                }
            }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}

/** Stars and downloads, large enough to read at a glance and announced as full phrases. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Stats(stars: Int, downloads: Long, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Stat(
            icon = R.drawable.ic_star,
            value = compactNumber(stars.toLong()),
            description = pluralStringResource(R.plurals.stat_stars, stars, stars)
        )
        Stat(
            icon = R.drawable.ic_download,
            value = compactNumber(downloads),
            description = pluralStringResource(
                R.plurals.stat_downloads,
                downloads.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                downloads
            )
        )
    }
}

@Composable
private fun Stat(icon: Int, value: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { contentDescription = description }
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun Progress(progress: Float?) {
    val description = stringResource(
        if (progress == null) R.string.state_installing else R.string.state_downloading
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        if (progress == null) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        } else {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun DetailsSheet(row: AppRow, onInstall: () -> Unit) {
    val context = LocalContext.current
    val app = row.app

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
    ) {
        Text(
            text = app.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )
        if (app.description.isNotBlank()) {
            Text(
                text = app.description,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
        val origin = when {
            !app.fromRepository ->
                stringResource(R.string.catalogue_detail, stringResource(sourceName(app.source)))
            app.auto && app.source == StoreApp.SOURCE_CODEBERG ->
                stringResource(R.string.auto_detail_codeberg)
            app.auto && app.source == StoreApp.SOURCE_GITLAB ->
                stringResource(R.string.auto_detail_gitlab)
            app.auto -> stringResource(R.string.auto_detail)
            else -> null
        }
        if (origin != null) {
            Text(
                text = origin,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        if (app.developer.isNotBlank()) {
            DetailLine(stringResource(R.string.detail_developer), app.developer)
        }
        DetailLine(stringResource(R.string.detail_source), stringResource(sourceName(app.source)))
        if (row.alsoFrom.isNotEmpty()) {
            val others = row.alsoFrom.map { stringResource(sourceName(it)) }.joinToString(", ")
            DetailLine(stringResource(R.string.detail_also_from), others)
        }
        if (app.fromRepository) {
            DetailLine(stringResource(R.string.detail_repository), app.repoPath)
        }
        DetailLine(stringResource(R.string.detail_category), stringResource(categoryLabel(app.category)))
        if (app.license.isNotBlank()) {
            DetailLine(stringResource(R.string.detail_license), app.license)
        }
        if (app.antiFeatures.isNotEmpty()) {
            DetailLine(stringResource(R.string.detail_warnings), app.antiFeatures.joinToString(", "))
        }
        if (app.fromRepository) {
            DetailLine(stringResource(R.string.detail_stars), fullNumber(app.stars.toLong()))
            DetailLine(stringResource(R.string.detail_downloads), fullNumber(app.downloads))
        }
        DetailLine(
            stringResource(R.string.detail_latest),
            if (app.prerelease) {
                stringResource(R.string.version_beta, app.displayVersion)
            } else {
                app.displayVersion
            }
        )
        row.installed?.let {
            DetailLine(
                stringResource(R.string.detail_installed),
                it.versionName ?: it.versionCode.toString()
            )
            DetailLine(
                stringResource(R.string.detail_installed_from),
                stringResource(installerName(it.installer, context.packageName))
            )
        }
        formatDate(app.publishedAt)?.let {
            DetailLine(stringResource(R.string.detail_published), it)
        }
        DetailLine(
            stringResource(R.string.detail_size),
            Formatter.formatShortFileSize(context, app.apkSize)
        )
        app.packageName?.let { DetailLine(stringResource(R.string.detail_package), it) }

        if (app.releaseNotes.isNotBlank()) {
            Text(
                text = stringResource(R.string.detail_notes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            Text(text = app.releaseNotes.trim(), style = MaterialTheme.typography.bodyMedium)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 16.dp)
        ) {
            StoreOutlinedButton(onClick = { openUrl(context, app.repoUrl) }) {
                Text(stringResource(R.string.action_view_source))
            }
            if (row.installed != null && app.packageName != null) {
                TextButton(onClick = { uninstall(context, app.packageName) }) {
                    Text(stringResource(R.string.action_uninstall))
                }
            }
        }

        if (row.status == AppStatus.OTHER_SIGNER) {
            Text(
                text = stringResource(R.string.other_signer_detail),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            // The way out when a later release is signed with the right key after all: the
            // attempt compares the keys again and forgets the conflict when they match.
            StoreOutlinedButton(onClick = onInstall, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.action_retry))
            }
        } else if (row.status == AppStatus.OTHER_BUILD) {
            // Another project's build, as far as is known: not offered as the update, but
            // the keys are compared after the download for whoever tries.
            Text(
                text = stringResource(R.string.other_build_detail),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            StoreOutlinedButton(onClick = onInstall, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.action_update))
            }
        } else if (row.installed != null && row.status == AppStatus.UPDATE_AVAILABLE) {
            // Whether the update will go through: known from the keys when the place that
            // offers the file tells how it is signed, otherwise only once it is downloaded.
            Text(
                text = stringResource(
                    if (row.installed.sameSigner) R.string.same_signer_detail else R.string.unknown_signer_detail
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        if (row.betaInstalled && app.packageName != null) {
            ReturnToStable(app.packageName, isStore = app.packageName == context.packageName)
        }
    }
}

/**
 * Explains how to get from an installed beta back to the stable version. Android does not
 * install an older version over a newer one, so the way back is to remove the app first.
 */
@Composable
private fun ReturnToStable(packageName: String, isStore: Boolean) {
    val context = LocalContext.current
    var confirming by rememberSaveable { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.stable_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 24.dp, bottom = 4.dp)
            .semantics { heading() }
    )
    Text(
        text = stringResource(
            if (isStore) R.string.stable_description_store else R.string.stable_description
        ),
        style = MaterialTheme.typography.bodyMedium
    )
    if (!isStore) {
        StoreOutlinedButton(onClick = { confirming = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.action_return_to_stable))
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.stable_confirm_title)) },
            text = { Text(stringResource(R.string.stable_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        uninstall(context, packageName)
                    }
                ) {
                    Text(stringResource(R.string.action_uninstall))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(0.6f)
        )
    }
}

@Composable
private fun versionLine(row: AppRow): String {
    val installed = row.installed
    return when {
        row.status == AppStatus.UPDATE_AVAILABLE && installed != null -> stringResource(
            R.string.version_update,
            installed.versionName ?: installed.versionCode.toString(),
            row.app.displayVersion
        )
        installed != null -> installed.versionName ?: row.app.displayVersion
        else -> row.app.displayVersion
    }
}

/** A short label on a card: a prerelease, or an app nobody published to the store. */
@Composable
private fun Badge(text: String) {
    val color = MaterialTheme.colorScheme.tertiaryContainer
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .padding(top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .material(color, Relief.RAISED, radius = 8.dp, grain = 0.3f)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/**
 * A filled button; raised metal of the primary colour when materials are on. The metal is
 * drawn inside the button's own surface, which keeps its size: the touch target Material
 * adds around it stays clear.
 */
@Composable
private fun StoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val metal = LocalMaterial.current
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = if (metal) {
            ButtonDefaults.buttonColors(containerColor = Color.Transparent)
        } else {
            ButtonDefaults.buttonColors()
        },
        contentPadding = if (metal) PaddingValues(0.dp) else ButtonDefaults.ContentPadding
    ) {
        ButtonFace(MaterialTheme.colorScheme.primary, metal, content)
    }
}

/** An outlined button; raised metal of the surface's colour when materials are on. */
@Composable
private fun StoreOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val metal = LocalMaterial.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        border = if (metal) null else ButtonDefaults.outlinedButtonBorder(enabled = true),
        contentPadding = if (metal) PaddingValues(0.dp) else ButtonDefaults.ContentPadding
    ) {
        ButtonFace(MaterialTheme.colorScheme.surfaceContainerHigh, metal, content)
    }
}

/** The face of a button: its content, on metal of the colour [base] when [metal]. */
@Composable
private fun RowScope.ButtonFace(base: Color, metal: Boolean, content: @Composable RowScope.() -> Unit) {
    if (!metal) {
        content()
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
            .material(base, Relief.RAISED, radius = null, grain = 0.5f)
            .padding(ButtonDefaults.ContentPadding),
        content = content
    )
}

/**
 * The colouring of a row of chips: the categories in the secondary tone, the places in the
 * tertiary tone that the badges naming a place on the cards have too.
 */
private enum class ChipTone { CATEGORY, PLACE }

/** A chip of a row of choices; raised metal, pressed in when chosen, when materials are on. */
@Composable
private fun ChoiceChip(selected: Boolean, label: String, onClick: () -> Unit, tone: ChipTone = ChipTone.CATEGORY) {
    val haptics = LocalHapticFeedback.current
    val metal = LocalMaterial.current
    val scheme = MaterialTheme.colorScheme
    val container = when (tone) {
        ChipTone.CATEGORY -> if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh
        ChipTone.PLACE -> if (selected) scheme.tertiary else scheme.tertiaryContainer
    }
    val content = when (tone) {
        ChipTone.CATEGORY -> if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
        ChipTone.PLACE -> if (selected) scheme.onTertiary else scheme.onTertiaryContainer
    }
    // Built on the selectable surface a filter chip is built on, so that the metal can be
    // drawn inside the chip's own 32 dp surface: the touch target around it stays clear.
    Surface(
        selected = selected,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        },
        shape = FilterChipDefaults.shape,
        color = if (metal) Color.Transparent else container,
        contentColor = content,
        border = if (metal || selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.semantics { role = Role.Checkbox }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .material(container, if (selected) Relief.RECESSED else Relief.RAISED, radius = 8.dp, grain = 0.4f)
                .defaultMinSize(minHeight = FilterChipDefaults.Height)
                .padding(start = if (selected) 8.dp else 16.dp, end = 16.dp)
        ) {
            if (selected) {
                SelectedMark()
                Spacer(Modifier.width(8.dp))
            }
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SettingsSheet(viewModel: StoreViewModel, material: Boolean, onMaterialChange: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )
        BetaSwitch(viewModel)
        Text(
            text = stringResource(R.string.beta_return_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        OriginSettings(viewModel)
        if (Surfaces.available) {
            SettingSwitch(
                heading = stringResource(R.string.appearance_title),
                label = stringResource(R.string.material_switch),
                description = stringResource(R.string.material_description),
                checked = material,
                onCheckedChange = onMaterialChange
            )
        }
        NotificationSetting()
        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp)
        )
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
    onCheckedChange: (Boolean) -> Unit
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
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SourcesSheet(viewModel: StoreViewModel) {
    val state by viewModel.sources.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current

    // Tell the outcome of adding a source by touch as well.
    LaunchedEffect(state.error) {
        if (state.error != null) haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
    ) {
        Text(
            text = stringResource(R.string.sources_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = stringResource(R.string.sources_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = stringResource(R.string.sources_requirements),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )

        if (state.sources.isNotEmpty()) {
            Text(
                text = stringResource(R.string.sources_list_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
        }
        state.sources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = source,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                val removeLabel = stringResource(R.string.action_remove_source, source)
                TextButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        viewModel.removeSource(source)
                    },
                    modifier = Modifier.semantics { contentDescription = removeLabel }
                ) {
                    Text(stringResource(R.string.action_remove))
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                viewModel.clearSourceError()
            },
            enabled = !state.adding,
            label = { Text(stringResource(R.string.sources_hint)) },
            singleLine = true,
            isError = state.error != null,
            supportingText = state.error?.let { error ->
                {
                    Text(
                        stringResource(
                            when (error) {
                                SourceError.INVALID -> R.string.sources_error_invalid
                                SourceError.DUPLICATE -> R.string.sources_error_duplicate
                                SourceError.NOT_FOUND -> R.string.sources_error_not_found
                                SourceError.NOT_OPEN -> R.string.sources_error_not_open
                                SourceError.NETWORK -> R.string.error_network
                                SourceError.RATE_LIMIT -> R.string.error_rate_limit
                            }
                        )
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { viewModel.addSource(input) { input = "" } }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        Button(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                viewModel.addSource(input) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    input = ""
                }
            },
            enabled = input.isNotBlank() && !state.adding,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(stringResource(R.string.action_add_source))
        }
    }
}

private const val PREFS_UI = "ui"
private const val PREF_SORT = "sort"
private const val PREF_MATERIAL = "material"

/**
 * The height of the list's room below which the search, the chips and the count scroll
 * with the list instead of staying above it: together they take about 200 dp, and the
 * list needs room of its own in a low window or with a large font.
 */
private val PINNED_HEADER_MIN_HEIGHT = 480.dp

/**
 * Lays the content out [side] wider on each side than the room given, for a thing in a
 * list that keeps its own edges past the list's side padding.
 */
private fun Modifier.pastSidePadding(side: Dp): Modifier = layout { measurable, constraints ->
    val extra = (side * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra)
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** The order of apps within each section of the list. */
private enum class SortOrder(val label: Int, val comparator: Comparator<AppRow>) {
    NAME(R.string.sort_name, compareBy { it.app.title.lowercase() }),
    DOWNLOADS(
        R.string.sort_downloads,
        compareByDescending<AppRow> { it.app.downloads }.thenBy { it.app.title.lowercase() }
    ),
    STARS(
        R.string.sort_stars,
        compareByDescending<AppRow> { it.app.stars }.thenBy { it.app.title.lowercase() }
    ),

    // Release dates are ISO 8601 in UTC, which sort correctly as text.
    NEWEST(
        R.string.sort_newest,
        compareByDescending<AppRow> { it.app.publishedAt }.thenBy { it.app.title.lowercase() }
    ),
    OLDEST(
        R.string.sort_oldest,
        compareBy<AppRow> { it.app.publishedAt }.thenBy { it.app.title.lowercase() }
    )
}

@Composable
private fun SortMenu(selected: SortOrder, onSelect: (SortOrder) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                expanded = true
            }
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_sort),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sort_button, stringResource(selected.label)))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { order ->
                val isSelected = order == selected
                DropdownMenuItem(
                    text = { Text(stringResource(order.label)) },
                    leadingIcon = {
                        // Keeps the labels aligned and marks the choice without relying on colour.
                        if (isSelected) SelectedMark() else Spacer(Modifier.size(18.dp))
                    },
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        expanded = false
                        onSelect(order)
                    },
                    modifier = Modifier.semantics { this.selected = isSelected }
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    Box(modifier = modifier) {
    // Pressed into the surface, with the outline as its lip. The field keeps half a line of
    // small text above the outline for the floating label, whatever the font size, so the
    // metal starts where the outline does.
    val labelRoom = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2 }
    Box(
        modifier = Modifier
            .matchParentSize()
            .padding(top = labelRoom)
            .clip(RoundedCornerShape(28.dp))
            .material(MaterialTheme.colorScheme.surfaceContainerLow, Relief.RECESSED, radius = 28.dp, grain = 0.4f)
    )
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        label = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = {
            Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null)
        },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onQueryChange("")
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_clear_search)
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // The list is filtered while typing, so the search key only puts the keyboard away.
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
    )
    }
}

@Composable
private fun CategoryChips(
    categories: List<String>,
    counts: Map<String, Int>,
    total: Int,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    // One category tells nothing the full list does not.
    if (categories.size < 2) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 16.dp)
    ) {
        item(key = "all") {
            ChoiceChip(
                selected = selected == null,
                label = stringResource(R.string.category_chip, stringResource(R.string.category_all), total),
                onClick = { onSelect(null) }
            )
        }
        items(categories, key = { it }) { id ->
            ChoiceChip(
                selected = selected == id,
                label = stringResource(R.string.category_chip, stringResource(categoryLabel(id)), counts[id] ?: 0),
                onClick = { onSelect(if (selected == id) null else id) }
            )
        }
    }
}

/**
 * A chip for each place apps are offered from, to see one place's apps alone. A place that
 * is turned off in the settings, among [enabled] it is not, is named without a count;
 * choosing it is the quick way to turn it on.
 */
@Composable
private fun PlaceChips(
    counts: Map<String, Int>,
    enabled: Set<String>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        item(key = "all") {
            ChoiceChip(
                selected = selected == null,
                label = stringResource(R.string.category_all),
                onClick = { onSelect(null) },
                tone = ChipTone.PLACE
            )
        }
        items(CatalogRules.PLACES, key = { it }) { place ->
            val count = counts[place] ?: 0
            val name = stringResource(sourceName(place))
            ChoiceChip(
                selected = selected == place,
                label = if (place in enabled || count > 0) stringResource(R.string.category_chip, name, count) else name,
                onClick = { onSelect(if (selected == place) null else place) },
                tone = ChipTone.PLACE
            )
        }
    }
}

/** Marks the chosen chip with a shape, so that the choice does not rest on colour alone. */
@Composable
private fun SelectedMark() {
    Icon(
        painter = painterResource(R.drawable.ic_check),
        contentDescription = null,
        modifier = Modifier.size(18.dp)
    )
}

/** A tick when the pull passes the point where letting go starts a refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullThresholdHaptics(state: PullToRefreshState) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state) {
        snapshotFlow { state.distanceFraction >= 1f }
            .distinctUntilChanged()
            .drop(1)
            .collect { armed ->
                if (armed) {
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                }
            }
    }
}

/** Confirms a finished install and signals a failed one by touch. */
@Composable
private fun InstallHaptics(installs: Map<String, InstallState>) {
    val haptics = LocalHapticFeedback.current
    var previous by remember { mutableStateOf(installs) }
    LaunchedEffect(installs) {
        val failed = installs.any { (name, state) ->
            state is InstallState.Failed && previous[name] !is InstallState.Failed
        }
        val finished = previous.any { (name, state) ->
            state is InstallState.Installing && name !in installs
        }
        when {
            failed -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            finished -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
        previous = installs
    }
}

/**
 * Where an app comes from, for its card. An app can call itself anything, so one whose name
 * is not that of its repository is shown with the repository as well as the owner.
 */
private fun origin(app: StoreApp): String = when {
    !app.fromRepository -> app.developer.ifBlank { app.packageName.orEmpty() }
    app.title.equals(app.repo, ignoreCase = true) -> app.owner
    else -> app.repoPath
}

/** The name of the place an app is offered from. */
private fun sourceName(source: String): Int = when (source) {
    StoreApp.SOURCE_IZZY -> R.string.source_izzy
    StoreApp.SOURCE_FDROID -> R.string.source_fdroid
    StoreApp.SOURCE_CODEBERG -> R.string.source_codeberg
    StoreApp.SOURCE_GITLAB -> R.string.source_gitlab
    else -> R.string.source_github
}

/** The name of the app that installed a package, [installer], for what the system tells. */
private fun installerName(installer: String?, self: String): Int = when (installer) {
    "com.android.vending" -> R.string.installer_play
    "org.fdroid.fdroid", "org.fdroid.basic", "org.fdroid.fdroid.privileged" -> R.string.installer_fdroid
    "com.aurora.store" -> R.string.installer_aurora
    self -> R.string.installer_store
    else -> R.string.installer_other
}

/** The badge that tells an app was not published to the store by its developer, if any. */
private fun sourceBadge(app: StoreApp): Int? = when {
    !app.fromRepository -> sourceName(app.source)
    app.auto && app.source == StoreApp.SOURCE_CODEBERG -> R.string.codeberg_switch
    app.auto && app.source == StoreApp.SOURCE_GITLAB -> R.string.gitlab_switch
    app.auto -> R.string.badge_auto
    else -> null
}

/** The words of a search, each of which an app must carry somewhere. */
private fun searchWords(query: String): List<String> =
    query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * How well [row] answers a search for [words]: 0 when its name carries them all, 1 when its
 * name together with its repository, developer and package does, 2 when its description is
 * needed as well; null when it does not answer. No words match every app by name.
 */
private fun matchRank(row: AppRow, words: List<String>): Int? {
    if (words.isEmpty()) return 0
    val app = row.app
    fun String.carries() = words.all { contains(it, ignoreCase = true) }
    val name = app.title
    val identity = "$name ${app.repo} ${app.developer} ${app.packageName.orEmpty()}"
    return when {
        name.carries() -> 0
        identity.carries() -> 1
        "$identity ${app.description}".carries() -> 2
        else -> null
    }
}

private fun categoryLabel(id: String): Int = when (id) {
    "communication" -> R.string.category_communication
    "productivity" -> R.string.category_productivity
    "tools" -> R.string.category_tools
    "personalization" -> R.string.category_personalization
    "media" -> R.string.category_media
    "games" -> R.string.category_games
    "travel" -> R.string.category_travel
    "news" -> R.string.category_news
    "system" -> R.string.category_system
    "health" -> R.string.category_health
    "finance" -> R.string.category_finance
    "education" -> R.string.category_education
    else -> R.string.category_other
}

private fun fullNumber(value: Long): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

private fun compactNumber(value: Long): String =
    CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
        .format(value)

private fun formatDate(iso: String): String? = runCatching {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(iso))
}.getOrNull()

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

private fun uninstall(context: Context, packageName: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_DELETE, "package:$packageName".toUri()))
    }
}
