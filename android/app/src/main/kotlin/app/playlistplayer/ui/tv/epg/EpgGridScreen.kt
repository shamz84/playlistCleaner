package app.playlistplayer.ui.tv.epg

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.feature.epg.AiringChoice
import app.playlistplayer.feature.epg.EpgCell
import app.playlistplayer.feature.epg.EpgDay
import app.playlistplayer.feature.epg.EpgEvent
import app.playlistplayer.feature.epg.EpgFocus
import app.playlistplayer.feature.epg.EpgGridUiState
import app.playlistplayer.feature.epg.EpgGridViewModel
import app.playlistplayer.feature.epg.EpgRow
import app.playlistplayer.feature.epg.ProgrammeDetails
import app.playlistplayer.feature.epg.ProgrammeTiming
import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.components.TvButton
import app.playlistplayer.ui.tv.components.TvEmptyState
import app.playlistplayer.ui.tv.components.TvErrorState
import app.playlistplayer.ui.tv.components.TvLoadingState
import app.playlistplayer.ui.tv.theme.TvDebugColors
import app.playlistplayer.ui.tv.theme.TvDensity
import app.playlistplayer.ui.tv.theme.TvDimens
import app.playlistplayer.ui.tv.theme.debugTint
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import java.time.format.DateTimeFormatter
import java.util.Locale
import app.playlistplayer.ui.common.rememberTimeFormatter

object EpgTags {
    const val GRID = "epg.grid"
    const val DAYS = "epg.days"
    const val NOW_LINE = "epg.nowLine"
    fun channel(ordinal: Int) = "epg.channel.$ordinal"
}

/** Rows per lazily loaded block of the TV guide (about three screens of rows). */
const val TV_GUIDE_ROW_BLOCK = 24

/**
 * Dense TiviMate-style guide grid for the current group (U9, U21). D-pad moves between programmes;
 * Left/Right past the window edge page in time; OK plays a catch-up programme, offers Watch live /
 * Restart on the airing one, or shows details; long-press OK (or Info) shows details for any
 * programme; OK on the channel (logo, number, name) or a "No guide data" row watches the channel
 * live. Left on the channel column and Menu anywhere call [onOpenGroups]. Focus returns to the
 * remembered programme when the screen is shown again (e.g. back from the player).
 *
 * Performance: rows have stable keys and keep their instance while unchanged; the focus is read per
 * row through `derivedStateOf`, so a focus move recomposes only the rows it leaves and enters (and
 * the details area that observes `EpgGridViewModel.focusedProgramme`); the visible rows are
 * reported to [EpgGridViewModel.onVisibleRows] so programmes load lazily around them.
 *
 * [title] (the group) is shown above the channel column. With [autoFocus] false the grid does not
 * take focus when it (re)loads, unless focus is requested by bumping [focusRequest].
 */
@Composable
fun EpgGridScreen(
    viewModel: EpgGridViewModel,
    onPlayCatchup: (CatchupRequest) -> Unit,
    onWatchLive: (LiveChannel) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    autoFocus: Boolean = true,
    focusRequest: Int = 0,
    onOpenGroups: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playCatchup by rememberUpdatedState(onPlayCatchup)
    val watchLive by rememberUpdatedState(onWatchLive)

    LaunchedEffect(viewModel) {
        // Shown again (e.g. back from the player): refresh now, keeping the window and focus.
        if (viewModel.state.value.rows.isNotEmpty()) viewModel.onReturn()
        viewModel.events.collect { event ->
            when (event) {
                is EpgEvent.PlayCatchup -> playCatchup(event.request)
                is EpgEvent.WatchLive -> watchLive(event.channel)
            }
        }
    }
    LaunchedEffect(viewModel) {
        while (isActive) {
            delay(TICK_MS)
            viewModel.tick()
        }
    }

    if (!state.emptyGroup && !state.loadFailed && state.rows.isNotEmpty()) {
        Box(modifier = modifier.fillMaxSize()) { Grid(state, viewModel, title, autoFocus, focusRequest, onOpenGroups) }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            // The grid shows the group in its ruler; the states show it above.
            title?.let { Text(text = it, style = MaterialTheme.typography.labelSmall, color = UiTokens.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            when {
                state.emptyGroup -> TvEmptyState(title = stringResource(R.string.epg_empty_group))
                state.loadFailed -> TvErrorState(message = stringResource(R.string.epg_load_failed), onRetry = viewModel::refresh)
                else -> TvLoadingState()
            }
        }
    }

    state.airingChoice?.let { choice ->
        AiringChoiceDialog(choice, onWatchLive = viewModel::chooseWatchLive, onRestart = viewModel::chooseRestart, onDismiss = viewModel::dismissChoice)
    }
    state.details?.let { details ->
        DetailsDialog(details, onPlay = viewModel::playFromDetails, onDismiss = viewModel::dismissDetails)
    }
}

/** "Now" and the day jumps of the group's guide (compact buttons). */
@Composable
fun EpgDayRow(viewModel: EpgGridViewModel, modifier: Modifier = Modifier) {
    val gridState = viewModel.state.collectAsStateWithLifecycle()
    // Days and the selected day only: row reloads and ticks do not recompose the day row.
    val days by remember(gridState) { derivedStateOf { gridState.value.days to gridState.value.selectedDayOffset } }
    val formatter = remember { DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()) }
    val (list, selected) = days
    if (list.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs), modifier = modifier.testTag(EpgTags.DAYS)) {
        item(key = "now") { TvButton(text = stringResource(R.string.epg_now), onClick = viewModel::jumpToNow) }
        items(list, key = { it.offset }) { day ->
            val label = when (day.offset) {
                0 -> stringResource(R.string.epg_today)
                -1 -> stringResource(R.string.epg_yesterday)
                1 -> stringResource(R.string.epg_tomorrow)
                else -> formatter.format(day.date)
            }
            TvButton(text = if (day.offset == selected) "• $label" else label, onClick = { viewModel.jumpToDay(day.offset) })
        }
    }
}

@Composable
private fun Grid(
    state: EpgGridUiState,
    viewModel: EpgGridViewModel,
    title: String?,
    autoFocus: Boolean,
    focusRequest: Int,
    onOpenGroups: () -> Unit,
) {
    val time = rememberTimeFormatter()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val focusState = viewModel.focusedCell.collectAsStateWithLifecycle()
    val windowStart = state.windowStartMillis
    val windowEnd = state.windowEndMillis
    val windowMillis = (windowEnd - windowStart).coerceAtLeast(1)

    val latest by rememberUpdatedState(state)
    val auto by rememberUpdatedState(autoFocus)
    val openGroups by rememberUpdatedState(onOpenGroups)
    var handledRequest by remember { mutableIntStateOf(0) }

    // Restore the remembered programme when the window changes or the screen is shown again, and
    // move focus there when requested.
    LaunchedEffect(windowStart, state.rows.size, focusRequest) {
        val requested = focusRequest != handledRequest
        handledRequest = focusRequest
        // A requested focus was set on the ViewModel just before: let it reach this composition.
        if (requested) withFrameNanos { }
        val f = focusState.value ?: return@LaunchedEffect
        val index = latest.rows.indexOfFirst { it.channel.ordinal == f.channelOrdinal }
        if (index >= 0) listState.scrollToItem(index)
        if (auto || requested) {
            if (requested) withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    // Programmes load lazily around the visible rows.
    LaunchedEffect(listState, viewModel) {
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo
            (info.firstOrNull()?.index ?: 0) to (info.lastOrNull()?.index ?: 0)
        }.distinctUntilChanged().collect { (first, last) -> viewModel.onVisibleRows(first, last) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val channelColumn = maxWidth * TvDensity.ChannelColumnFraction
        val timelineWidth = (maxWidth - channelColumn - TvDensity.GuideRowGap).coerceAtLeast(120.dp)
        val dpPerMs = timelineWidth.value / windowMillis
        val geometry = remember(windowStart, windowEnd, dpPerMs, channelColumn, timelineWidth, state.canPageBack, state.canPageForward) {
            RowGeometry(windowStart, windowEnd, dpPerMs, channelColumn, timelineWidth, state.canPageBack, state.canPageForward)
        }
        Column {
            // Time ruler: group name over the channel column, slot times over the timeline.
            Row(modifier = Modifier.height(TvDensity.RulerHeight).debugTint(TvDebugColors.Ruler), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = UiTokens.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(channelColumn + TvDensity.GuideRowGap).padding(end = UiTokens.SpaceS),
                )
                Box(Modifier.width(timelineWidth).fillMaxHeight()) {
                    state.slots.forEach { slot ->
                        Text(
                            text = time(slot),
                            style = MaterialTheme.typography.labelSmall,
                            color = UiTokens.OnSurfaceMuted,
                            modifier = Modifier.offset(x = ((slot - windowStart) * dpPerMs).dp).padding(start = UiTokens.SpaceXs),
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(TvDensity.GuideRowGap),
                    modifier = Modifier.fillMaxSize().testTag(EpgTags.GRID),
                ) {
                    items(state.rows, key = { it.channel.ordinal }, contentType = { "row" }) { row ->
                        GuideRow(
                            row = row,
                            geometry = geometry,
                            focusState = focusState,
                            focusRequester = focusRequester,
                            time = time,
                            viewModel = viewModel,
                            onOpenGroups = { openGroups() },
                        )
                    }
                }
                if (state.showNowLine) {
                    Box(
                        modifier = Modifier
                            .offset(x = channelColumn + TvDensity.GuideRowGap + ((state.nowMillis - windowStart) * dpPerMs).dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(UiTokens.Error)
                            .testTag(EpgTags.NOW_LINE),
                    )
                }
            }
        }
    }
}

/** Window and column geometry shared by every row (one instance per window, so rows can skip). */
private class RowGeometry(
    val windowStart: Long,
    val windowEnd: Long,
    val dpPerMs: Float,
    val channelColumn: Dp,
    val timelineWidth: Dp,
    val canPageBack: Boolean,
    val canPageForward: Boolean,
)

@Composable
private fun GuideRow(
    row: EpgRow,
    geometry: RowGeometry,
    focusState: State<EpgFocus?>,
    focusRequester: FocusRequester,
    time: (Long) -> String,
    viewModel: EpgGridViewModel,
    onOpenGroups: () -> Unit,
) {
    val ordinal = row.channel.ordinal
    // Only this row's share of the focus: other rows do not recompose when focus moves.
    val rowFocus by remember(ordinal) { derivedStateOf { focusState.value?.takeIf { it.channelOrdinal == ordinal } } }
    val focus = rowFocus
    Row(
        horizontalArrangement = Arrangement.spacedBy(TvDensity.GuideRowGap),
        modifier = Modifier.height(TvDensity.GuideRowHeight).semantics { contentDescription = "${row.number} ${row.channel.name}" },
    ) {
        // Channel: logo, number and name; OK watches the channel live, Left opens the groups.
        ChannelCell(
            row = row,
            modifier = Modifier
                .width(geometry.channelColumn)
                .fillMaxHeight()
                .then(if (focus != null && focus.programmeId == null && !row.noGuideData && row.loaded) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { if (it.isFocused) viewModel.onFocus(ordinal, null) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MENU -> {
                            onOpenGroups()
                            true
                        }
                        else -> false
                    }
                }
                .testTag(EpgTags.channel(ordinal)),
            onClick = { viewModel.selectChannel(ordinal) },
        )
        Box(modifier = Modifier.width(geometry.timelineWidth).fillMaxHeight().debugTint(TvDebugColors.Timeline)) {
            if (row.noGuideData || !row.loaded) {
                GuideCell(
                    title = stringResource(if (row.loaded) R.string.epg_no_guide_data else R.string.livetv_row_loading),
                    subtitle = null,
                    timing = null,
                    catchup = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (focus != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { if (it.isFocused) viewModel.onFocus(ordinal, null) }
                        .pagingKeys(atStart = true, atEnd = true, geometry = geometry, viewModel = viewModel, onMenu = onOpenGroups),
                    onClick = { viewModel.selectChannel(ordinal) },
                    onLongClick = null,
                )
            } else {
                row.cells.forEach { cell ->
                    ProgrammeCell(
                        cell = cell,
                        ordinal = ordinal,
                        geometry = geometry,
                        focused = focus != null && focus.programmeId == cell.programmeId,
                        focusRequester = focusRequester,
                        time = time,
                        viewModel = viewModel,
                        onOpenGroups = onOpenGroups,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCell(
    cell: EpgCell,
    ordinal: Int,
    geometry: RowGeometry,
    focused: Boolean,
    focusRequester: FocusRequester,
    time: (Long) -> String,
    viewModel: EpgGridViewModel,
    onOpenGroups: () -> Unit,
) {
    val start = maxOf(cell.startMillis, geometry.windowStart)
    val end = minOf(cell.stopMillis, geometry.windowEnd)
    GuideCell(
        title = cell.title ?: stringResource(R.string.epg_untitled),
        subtitle = "${time(cell.startMillis)} – ${time(cell.stopMillis)}",
        timing = cell.timing,
        catchup = cell.catchup,
        modifier = Modifier
            .offset(x = ((start - geometry.windowStart) * geometry.dpPerMs).dp)
            .width((((end - start) * geometry.dpPerMs).dp - TvDensity.GuideRowGap).coerceAtLeast(6.dp))
            .fillMaxHeight()
            .then(if (focused) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) viewModel.onFocus(ordinal, cell.programmeId) }
            .pagingKeys(
                atStart = cell.startMillis <= geometry.windowStart,
                atEnd = cell.stopMillis >= geometry.windowEnd,
                geometry = geometry,
                viewModel = viewModel,
                onInfo = { viewModel.showDetails(ordinal, cell.programmeId) },
                onMenu = onOpenGroups,
            ),
        onClick = { viewModel.select(ordinal, cell.programmeId) },
        onLongClick = { viewModel.showDetails(ordinal, cell.programmeId) },
    )
}

/** Left on the first cell / Right on the last cell pages in time; Info opens details; Menu the groups. */
private fun Modifier.pagingKeys(
    atStart: Boolean,
    atEnd: Boolean,
    geometry: RowGeometry,
    viewModel: EpgGridViewModel,
    onInfo: (() -> Unit)? = null,
    onMenu: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.nativeKeyEvent.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (atStart && geometry.canPageBack) {
            viewModel.pageBack()
            true
        } else {
            false
        }
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (atEnd && geometry.canPageForward) {
            viewModel.pageForward()
            true
        } else {
            false
        }
        AndroidKeyEvent.KEYCODE_INFO -> {
            onInfo?.invoke()
            onInfo != null
        }
        AndroidKeyEvent.KEYCODE_MENU -> {
            onMenu()
            true
        }
        else -> false
    }
}

@Composable
private fun ChannelCell(row: EpgRow, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(TvDensity.CellShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (TvDebugColors.ENABLED) CHANNEL_DEBUG_CONTAINER else UiTokens.Surface,
            focusedContainerColor = UiTokens.SurfaceFocused,
            contentColor = UiTokens.OnBackground,
            focusedContentColor = UiTokens.OnBackground,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = TvDensity.CellFocusBorder),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvDensity.CellPadding),
            modifier = Modifier.fillMaxSize().padding(horizontal = TvDensity.CellPadding),
        ) {
            Box(modifier = Modifier.size(TvDensity.LogoSize), contentAlignment = Alignment.Center) {
                row.channel.logoUrl?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(text = row.number.toString(), style = MaterialTheme.typography.labelSmall, color = UiTokens.OnSurfaceMuted, maxLines = 1)
                Text(text = row.channel.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GuideCell(
    title: String,
    subtitle: String?,
    timing: ProgrammeTiming?,
    catchup: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val container = when (timing) {
        ProgrammeTiming.AIRING -> UiTokens.SurfaceVariant
        ProgrammeTiming.PAST -> UiTokens.GuidePast
        else -> UiTokens.Surface
    }
    val catchupDescription = stringResource(R.string.epg_catchup_icon_description)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.semantics {
            contentDescription = listOfNotNull(title, subtitle, if (catchup) catchupDescription else null).joinToString(", ")
        },
        shape = ClickableSurfaceDefaults.shape(TvDensity.CellShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            focusedContainerColor = UiTokens.SurfaceFocused,
            contentColor = UiTokens.OnBackground,
            focusedContentColor = UiTokens.OnBackground,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = TvDensity.CellFocusBorder),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = TvDensity.CellPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
                if (catchup) Text(text = CATCHUP_ICON, color = UiTokens.Accent, style = MaterialTheme.typography.bodyMedium)
                Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            subtitle?.let { Text(text = it, maxLines = 1, color = UiTokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun AiringChoiceDialog(choice: AiringChoice, onWatchLive: () -> Unit, onRestart: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.background(UiTokens.Surface, TvDimens.Shape).padding(UiTokens.SpaceXl),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
        ) {
            Text(text = choice.title ?: stringResource(R.string.epg_untitled), style = MaterialTheme.typography.titleLarge, color = UiTokens.OnBackground)
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
                TvButton(text = stringResource(R.string.epg_watch_live), onClick = onWatchLive, modifier = Modifier.focusRequester(focus))
                if (choice.canRestart) TvButton(text = stringResource(R.string.epg_restart), onClick = onRestart)
                TvButton(text = stringResource(R.string.epg_close), onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(choice) { runCatching { focus.requestFocus() } }
}

@Composable
private fun DetailsDialog(details: ProgrammeDetails, onPlay: () -> Unit, onDismiss: () -> Unit) {
    val time = rememberTimeFormatter()
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).background(UiTokens.Surface, TvDimens.Shape).padding(UiTokens.SpaceXl),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
        ) {
            Text(text = details.title ?: stringResource(R.string.epg_untitled), style = MaterialTheme.typography.titleLarge, color = UiTokens.OnBackground)
            details.subTitle?.let { Text(text = it, color = UiTokens.OnBackground) }
            val timing = when (details.timing) {
                ProgrammeTiming.AIRING -> stringResource(R.string.epg_details_airing)
                ProgrammeTiming.FUTURE -> stringResource(R.string.epg_details_upcoming)
                ProgrammeTiming.PAST -> null
            }
            Text(
                text = listOfNotNull(details.channelName, "${time(details.startMillis)} – ${time(details.stopMillis)}", details.episodeNum, timing).joinToString(" · "),
                color = UiTokens.OnSurfaceMuted,
            )
            if (details.categories.isNotEmpty()) Text(text = details.categories.joinToString(", "), color = UiTokens.OnSurfaceMuted)
            details.description?.let { Text(text = it, color = UiTokens.OnBackground, maxLines = 8, overflow = TextOverflow.Ellipsis) }
            if (details.timing == ProgrammeTiming.PAST && !details.catchupAvailable) {
                Text(text = stringResource(R.string.epg_details_not_available), color = UiTokens.OnSurfaceMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM), modifier = Modifier.padding(top = UiTokens.SpaceS)) {
                if (details.catchupAvailable) {
                    TvButton(text = stringResource(R.string.epg_play_from_start), onClick = onPlay, modifier = Modifier.focusRequester(focus))
                    TvButton(text = stringResource(R.string.epg_close), onClick = onDismiss)
                } else {
                    TvButton(text = stringResource(R.string.epg_close), onClick = onDismiss, modifier = Modifier.focusRequester(focus))
                }
            }
        }
    }
    LaunchedEffect(details) { runCatching { focus.requestFocus() } }
}

private val CHANNEL_DEBUG_CONTAINER = Color(0xFF0B5560)
private const val CATCHUP_ICON = "↺" // ↺
private const val TICK_MS = 60_000L
