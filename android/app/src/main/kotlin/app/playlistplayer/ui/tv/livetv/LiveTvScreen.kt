package app.playlistplayer.ui.tv.livetv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as storedViewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.feature.epg.EpgGridViewModel
import app.playlistplayer.feature.epg.FocusedProgramme
import app.playlistplayer.feature.epg.ProgrammeTiming
import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.ChannelRef
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.ui.common.GuideDependencies
import app.playlistplayer.ui.common.UiState
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.common.live.LiveTvViewModel
import app.playlistplayer.ui.common.live.NumberEntryController
import app.playlistplayer.ui.common.rememberTimeFormatter
import app.playlistplayer.ui.tv.components.TvButton
import app.playlistplayer.ui.tv.components.TvUiState
import app.playlistplayer.ui.tv.epg.EpgDayRow
import app.playlistplayer.ui.tv.epg.EpgGridScreen
import app.playlistplayer.ui.tv.epg.TV_GUIDE_ROW_BLOCK
import app.playlistplayer.ui.tv.live.GroupRail
import app.playlistplayer.ui.tv.live.NumberEntryOverlay
import app.playlistplayer.ui.tv.live.digitFor
import app.playlistplayer.ui.tv.player.ProgressBar
import app.playlistplayer.ui.tv.theme.TvDebugColors
import app.playlistplayer.ui.tv.theme.TvDensity
import app.playlistplayer.ui.tv.theme.debugTint
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

object LiveTvTags {
    const val ROOT = "livetv"
    const val GRID_PANE = "livetv.grid"
    const val DETAILS = "livetv.details"
    const val PREVIEW = "livetv.preview"
    const val GROUPS_PANEL = "livetv.groups"
    const val LAST_CHANNEL = "livetv.lastChannel"
}

/**
 * Live TV (U9, U21; TiviMate layout): the top ~35 % shows the focused programme (channel logo,
 * number and name, title, time with progress, description, catch-up badge) beside a live preview;
 * below, the dense guide of the selected group ([EpgGridScreen] over the shared
 * [EpgGridViewModel], one per group in the activity store, rows loaded lazily). Groups (sectioned by
 * playlist name, R25) are in a side panel that slides in over the guide.
 *
 * - Preview: [preview] renders the app's single player while [LiveTvViewModel] reports a live
 *   channel playing (it IS the playing stream; nothing opens a second connection); otherwise the
 *   focused channel's logo. Selecting the preview calls [onFullscreen] with the playing channel
 *   (the player adopts the stream); "Last channel" tunes the previous one.
 * - Groups panel: Left on the channel column, Menu, or Back in the guide open it; OK on a group
 *   selects it, closes the panel and focuses the guide; Right or Back hides it.
 * - In the guide, OK on a channel plays it via [onPlayChannel] (the playing one goes fullscreen
 *   without re-tuning); programmes keep the guide's routing.
 * - Digits jump to the Nth channel of the group ([NumberEntryController]).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveTvScreen(
    viewModel: LiveTvViewModel,
    guide: GuideDependencies,
    onPlayChannel: (LiveChannel) -> Unit,
    onPlayCatchup: (CatchupRequest) -> Unit,
    modifier: Modifier = Modifier,
    preview: (@Composable (Modifier) -> Unit)? = null,
    onFullscreen: (ChannelRef) -> Unit = {},
    onReorderPlaylists: (List<Long>) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showOrder by remember { mutableStateOf(false) }
    var showRecent by remember { mutableStateOf(false) }
    val history by guide.history.state.collectAsStateWithLifecycle()
    val groupsFocus = remember { FocusRequester() }
    var gridHasFocus by remember { mutableStateOf(false) }
    /** Starts pending: the grid takes focus once loaded each time the screen is shown. */
    var gridFocusRequest by remember { mutableIntStateOf(1) }
    val numberEntry = remember { NumberEntryController() }
    var entry by remember { mutableStateOf(NumberEntryController.State()) }
    var notFound by remember { mutableStateOf<String?>(null) }

    val group = state.selectedGroup
    val grid: EpgGridViewModel? = group?.let { key ->
        storedViewModel(key = "tv-livetv-${key.playlistId}-${key.groupOrdinal}") {
            EpgGridViewModel(key, guide.channels, guide.grid, guide.coordinator, guide.clock, guide.zone, rowBlock = TV_GUIDE_ROW_BLOCK)
        }
    }
    val rowsState = grid?.state?.collectAsStateWithLifecycle()
    val playableCount by remember(rowsState) { derivedStateOf { rowsState?.value?.rows?.size ?: 0 } }

    // The focused cell of the selected group's guide drives the details area.
    LaunchedEffect(grid) {
        if (grid == null) viewModel.onFocused(null) else grid.focusedProgramme.collect(viewModel::onFocused)
    }
    // First open: focus the last channel played in its restored group.
    LaunchedEffect(grid, state.restoreChannel) {
        val ordinal = state.restoreChannel ?: return@LaunchedEffect
        if (grid == null) return@LaunchedEffect
        grid.onFocus(ordinal, null)
        viewModel.onRestoreHandled()
        gridFocusRequest++
    }
    LaunchedEffect(state.groupsPanelOpen) {
        if (state.groupsPanelOpen) {
            delay(PANEL_FOCUS_DELAY_MS) // let the panel compose
            runCatching { groupsFocus.requestFocus() }
        }
    }

    fun handle(outcome: NumberEntryController.Outcome?) {
        when (outcome) {
            is NumberEntryController.Outcome.Jump -> rowsState?.value?.rows?.firstOrNull { it.number == outcome.number }?.let { row ->
                grid?.onFocus(row.channel.ordinal, null)
                gridFocusRequest++
            }
            is NumberEntryController.Outcome.Ignored -> notFound = outcome.digits
            null -> Unit
        }
        entry = numberEntry.state
    }

    // Commit the pending number 1.5 s after the last digit.
    LaunchedEffect(entry.deadlineMillis) {
        val deadline = entry.deadlineMillis ?: return@LaunchedEffect
        delay((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
        handle(numberEntry.onTimeout(SystemClock.uptimeMillis(), playableCount))
    }
    LaunchedEffect(notFound) {
        if (notFound != null) {
            delay(NumberEntryController.DEFAULT_TIMEOUT_MILLIS)
            notFound = null
        }
    }

    fun closePanel() {
        viewModel.closeGroupsPanel()
        gridFocusRequest++
    }

    BackHandler(enabled = entry.isActive) {
        numberEntry.cancel()
        entry = numberEntry.state
    }
    BackHandler(enabled = !entry.isActive && state.groupsPanelOpen) { closePanel() }
    BackHandler(enabled = !entry.isActive && !state.groupsPanelOpen && gridHasFocus) { viewModel.openGroupsPanel() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(UiTokens.Background)
            .testTag(LiveTvTags.ROOT)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                val digit = digitFor(code)
                when {
                    digit != null && playableCount > 0 -> {
                        notFound = null
                        handle(numberEntry.onDigit(digit, SystemClock.uptimeMillis(), playableCount))
                        true
                    }
                    (code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER) && entry.isActive -> {
                        handle(numberEntry.commit(playableCount))
                        true
                    }
                    code == AndroidKeyEvent.KEYCODE_MENU -> {
                        showRecent = true
                        true
                    }
                    code == AndroidKeyEvent.KEYCODE_LAST_CHANNEL && state.lastChannel != null -> {
                        state.lastChannel?.let(onFullscreen)
                        true
                    }
                    else -> false
                }
            },
    ) {
        TvUiState(state = state.sections, modifier = Modifier.fillMaxSize().padding(TvDensity.ScreenPadding)) { sections ->
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
            // TiviMate-style: the compact playlist/group tree is always visible beside the guide.
            GroupRail(
                sections = sections,
                selected = group,
                onFocusGroup = {},
                onOpenGroup = { key ->
                    viewModel.selectGroup(key)
                    closePanel()
                },
                compact = true,
                modifier = Modifier
                    .width(TvDensity.GroupsPanelWidth)
                    .fillMaxHeight()
                    .background(UiTokens.GuideTree, TvDensity.CellShape)
                    .debugTint(TvDebugColors.Tree)
                    .padding(horizontal = UiTokens.SpaceXs, vertical = UiTokens.SpaceS)
                    .testTag(LiveTvTags.GROUPS_PANEL)
                    .focusRequester(groupsFocus)
                    .focusRestorer()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) {
                            closePanel()
                            true
                        } else {
                            false
                        }
                    },
            )
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                Row(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(TvDensity.DetailsFraction),
                    horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
                ) {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight().debugTint(TvDebugColors.Details), verticalArrangement = Arrangement.SpaceBetween) {
                        DetailsPane(viewModel, guide.clock, Modifier.weight(1f, fill = false))
                        if (grid != null) key(group) { EpgDayRow(grid) }
                    }
                    PreviewPane(
                        viewModel = viewModel,
                        preview = preview,
                        onFullscreen = onFullscreen,
                        modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true).debugTint(TvDebugColors.Preview),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag(LiveTvTags.GRID_PANE)
                        .onFocusChanged { gridHasFocus = it.hasFocus },
                ) {
                    if (group != null && grid != null) {
                        val section = sections.firstOrNull { it.playlistId == group.playlistId }
                        val groupName = section?.groups?.firstOrNull { it.key == group }?.name
                        // Per-group grid UI state (scroll, handled focus requests).
                        key(group) {
                            EpgGridScreen(
                                viewModel = grid,
                                onPlayCatchup = onPlayCatchup,
                                onWatchLive = onPlayChannel,
                                title = if (section != null && groupName != null) {
                                    stringResource(R.string.tv_guide_group_label, section.name, groupName)
                                } else {
                                    null
                                },
                                autoFocus = !state.groupsPanelOpen,
                                focusRequest = gridFocusRequest,
                                onOpenGroups = viewModel::openGroupsPanel,
                            )
                        }
                    }
                }
            }
            }
        }
        (state.sections as? UiState.Content)?.value?.let { sections ->
            if (showRecent) {
                RecentChannelsDialog(
                    recent = history.recent,
                    onPlay = onFullscreen,
                    onOrderPlaylists = if (sections.size > 1) ({ showOrder = true }) else null,
                    onDismiss = { showRecent = false },
                )
            }
            if (showOrder) {
                PlaylistOrderDialog(
                    playlists = sections.map { OrderablePlaylist(it.playlistId, it.name) },
                    onSave = onReorderPlaylists,
                    onDismiss = { showOrder = false },
                )
            }
        }
        if (state.sections is UiState.Content) {
            NumberEntryOverlay(digits = entry.digits, notFound = notFound, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

/** The focused programme; observes only [LiveTvViewModel.focused], so focus moves recompose this alone. */
@Composable
private fun DetailsPane(viewModel: LiveTvViewModel, clock: () -> Long, modifier: Modifier) {
    val focused by viewModel.focused.collectAsStateWithLifecycle()
    val f = focused ?: return
    val time = rememberTimeFormatter()
    Column(modifier = modifier.testTag(LiveTvTags.DETAILS), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
            Logo(f.channel.logoUrl, Modifier.size(TvDensity.LogoSize))
            Text(text = f.number.toString(), style = MaterialTheme.typography.titleMedium, color = UiTokens.Accent)
            Text(text = f.channel.name, style = MaterialTheme.typography.titleMedium, color = UiTokens.OnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            text = f.title ?: stringResource(if (f.programmeId == null) R.string.epg_no_guide_data else R.string.epg_untitled),
            style = MaterialTheme.typography.headlineSmall,
            color = UiTokens.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val start = f.startMillis
        val stop = f.stopMillis
        if (start != null && stop != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS)) {
                Text(text = "${time(start)} – ${time(stop)}", style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted)
                if (f.timing == ProgrammeTiming.AIRING && stop > start) {
                    val progress = ((clock() - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
                    ProgressBar(progress, Modifier.width(120.dp))
                }
                if (f.catchup) {
                    Text(
                        text = stringResource(R.string.livetv_catchup_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = UiTokens.OnAccent,
                        modifier = Modifier.background(UiTokens.Accent, TvDensity.CellShape).padding(horizontal = UiTokens.SpaceXs),
                    )
                }
            }
        }
        f.subTitle?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = UiTokens.OnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        f.description?.let { Text(text = it, style = MaterialTheme.typography.bodySmall, color = UiTokens.OnSurfaceMuted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * The live preview: the shared player's video while a channel plays (selecting it goes fullscreen),
 * else the focused channel's logo. Reads the focused channel through `derivedStateOf`, so only a
 * change of channel (not of programme) recomposes it.
 */
@Composable
private fun PreviewPane(
    viewModel: LiveTvViewModel,
    preview: (@Composable (Modifier) -> Unit)?,
    onFullscreen: (ChannelRef) -> Unit,
    modifier: Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusedState = viewModel.focused.collectAsStateWithLifecycle()
    val focusedChannel by remember(focusedState) { derivedStateOf { focusedState.value?.channel } }
    val playing = state.playing
    Box(modifier = modifier.background(Color.Black, TvDensity.CellShape).testTag(LiveTvTags.PREVIEW)) {
        if (playing != null && preview != null) {
            val description = stringResource(R.string.livetv_preview_description, focusedChannel?.takeIf { it.ordinal == playing.ordinal }?.name.orEmpty())
            Surface(
                onClick = { onFullscreen(playing) },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
                shape = ClickableSurfaceDefaults.shape(TvDensity.CellShape),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Black, focusedContainerColor = Color.Black),
                border = ClickableSurfaceDefaults.border(focusedBorder = TvDensity.CellFocusBorder),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                preview(Modifier.fillMaxSize())
            }
        } else {
            Logo(focusedChannel?.logoUrl, Modifier.fillMaxSize(0.5f).align(Alignment.Center))
            if (focusedChannel?.logoUrl == null) {
                Text(
                    text = focusedChannel?.name ?: stringResource(R.string.livetv_preview_idle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = UiTokens.OnSurfaceMuted,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        state.lastChannel?.let { last ->
            TvButton(
                text = stringResource(R.string.player_last_channel),
                onClick = { onFullscreen(last) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(UiTokens.SpaceXs).testTag(LiveTvTags.LAST_CHANNEL),
            )
        }
    }
}

@Composable
private fun Logo(url: String?, modifier: Modifier) {
    if (url == null) return
    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
}

private const val PANEL_FOCUS_DELAY_MS = 50L
