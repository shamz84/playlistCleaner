package app.playlistplayer.ui.tv.epg

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import app.playlistplayer.ui.tv.player.rememberTimeFormatter
import app.playlistplayer.ui.tv.theme.TvDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.format.DateTimeFormatter
import java.util.Locale

object EpgTags {
    const val GRID = "epg.grid"
    const val DAYS = "epg.days"
    const val NOW_LINE = "epg.nowLine"
}

/**
 * Guide grid for the current group (U9). D-pad moves between programmes; Left/Right past the
 * window edge page in time; the day row jumps by day within the archive window; OK plays a
 * catch-up programme, offers Watch live / Restart on the airing one, or shows details; long-press
 * OK (or Info) shows details for any programme. Focus returns to the remembered programme when the
 * screen is shown again (e.g. back from the player).
 */
@Composable
fun EpgGridScreen(
    viewModel: EpgGridViewModel,
    onPlayCatchup: (CatchupRequest) -> Unit,
    onWatchLive: (LiveChannel) -> Unit,
    modifier: Modifier = Modifier,
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

    Column(modifier = modifier.fillMaxSize().padding(TvDimens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
        Text(text = stringResource(R.string.epg_title), style = MaterialTheme.typography.headlineSmall, color = UiTokens.OnBackground)
        when {
            state.emptyGroup -> TvEmptyState(title = stringResource(R.string.epg_empty_group))
            state.loadFailed -> TvErrorState(message = stringResource(R.string.epg_load_failed), onRetry = viewModel::refresh)
            state.rows.isEmpty() -> TvLoadingState()
            else -> {
                DayRow(state.days, state.selectedDayOffset, onDay = viewModel::jumpToDay, onNow = viewModel::jumpToNow)
                Grid(state, viewModel)
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

@Composable
private fun DayRow(days: List<EpgDay>, selected: Int, onDay: (Int) -> Unit, onNow: () -> Unit) {
    val formatter = remember { DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS), modifier = Modifier.testTag(EpgTags.DAYS)) {
        item { TvButton(text = stringResource(R.string.epg_now), onClick = onNow) }
        items(days, key = { it.offset }) { day ->
            val label = when (day.offset) {
                0 -> stringResource(R.string.epg_today)
                -1 -> stringResource(R.string.epg_yesterday)
                1 -> stringResource(R.string.epg_tomorrow)
                else -> formatter.format(day.date)
            }
            TvButton(text = if (day.offset == selected) "• $label" else label, onClick = { onDay(day.offset) })
        }
    }
}

@Composable
private fun Grid(state: EpgGridUiState, viewModel: EpgGridViewModel) {
    val time = rememberTimeFormatter()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val focus = state.focus
    val windowMillis = (state.windowEndMillis - state.windowStartMillis).coerceAtLeast(1)

    // Restore the remembered programme when the window changes or the screen is shown again.
    LaunchedEffect(state.windowStartMillis, state.rows.size) {
        val f = state.focus ?: return@LaunchedEffect
        val index = state.rows.indexOfFirst { it.channel.ordinal == f.channelOrdinal }
        if (index >= 0) listState.scrollToItem(index)
        runCatching { focusRequester.requestFocus() }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val timelineWidth = (maxWidth - CHANNEL_COLUMN - UiTokens.SpaceS).coerceAtLeast(120.dp)
        val dpPerMs = timelineWidth.value / windowMillis
        Column(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
            // Slot header.
            Row {
                Box(Modifier.width(CHANNEL_COLUMN + UiTokens.SpaceS))
                Box(Modifier.width(timelineWidth).height(28.dp)) {
                    state.slots.forEach { slot ->
                        Text(
                            text = time(slot),
                            color = UiTokens.OnSurfaceMuted,
                            modifier = Modifier.offset(x = ((slot - state.windowStartMillis) * dpPerMs).dp),
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs),
                    modifier = Modifier.fillMaxSize().testTag(EpgTags.GRID),
                ) {
                    items(state.rows, key = { it.channel.ordinal }) { row ->
                        GridRow(
                            row = row,
                            state = state,
                            focus = focus,
                            focusRequester = focusRequester,
                            timelineWidth = timelineWidth,
                            dpPerMs = dpPerMs,
                            time = time,
                            viewModel = viewModel,
                        )
                    }
                }
                if (state.showNowLine) {
                    Box(
                        modifier = Modifier
                            .offset(x = CHANNEL_COLUMN + UiTokens.SpaceS + ((state.nowMillis - state.windowStartMillis) * dpPerMs).dp)
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

@Composable
private fun GridRow(
    row: EpgRow,
    state: EpgGridUiState,
    focus: EpgFocus?,
    focusRequester: FocusRequester,
    timelineWidth: Dp,
    dpPerMs: Float,
    time: (Long) -> String,
    viewModel: EpgGridViewModel,
) {
    val rowFocused = focus?.channelOrdinal == row.channel.ordinal
    Row(
        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
        modifier = Modifier.semantics { contentDescription = "${row.number} ${row.channel.name}" },
    ) {
        Box(
            modifier = Modifier.width(CHANNEL_COLUMN).height(ROW_HEIGHT).background(UiTokens.Surface, TvDimens.Shape).padding(horizontal = UiTokens.SpaceS),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text = "${row.number}  ${row.channel.name}", color = UiTokens.OnBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(modifier = Modifier.width(timelineWidth).height(ROW_HEIGHT)) {
            if (row.noGuideData) {
                GuideCell(
                    title = stringResource(R.string.epg_no_guide_data),
                    subtitle = null,
                    timing = null,
                    catchup = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (rowFocused) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { if (it.isFocused) viewModel.onFocus(row.channel.ordinal, null) }
                        .pagingKeys(atStart = true, atEnd = true, state = state, viewModel = viewModel),
                    onClick = { viewModel.selectChannel(row.channel.ordinal) },
                    onLongClick = null,
                )
            } else {
                row.cells.forEach { cell ->
                    val start = maxOf(cell.startMillis, state.windowStartMillis)
                    val end = minOf(cell.stopMillis, state.windowEndMillis)
                    val focused = rowFocused && focus.programmeId == cell.programmeId
                    GuideCell(
                        title = cell.title ?: stringResource(R.string.epg_untitled),
                        subtitle = "${time(cell.startMillis)} – ${time(cell.stopMillis)}",
                        timing = cell.timing,
                        catchup = cell.catchup,
                        modifier = Modifier
                            .offset(x = ((start - state.windowStartMillis) * dpPerMs).dp)
                            .width((((end - start) * dpPerMs).dp - 2.dp).coerceAtLeast(8.dp))
                            .fillMaxHeight()
                            .then(if (focused) Modifier.focusRequester(focusRequester) else Modifier)
                            .onFocusChanged { if (it.isFocused) viewModel.onFocus(row.channel.ordinal, cell.programmeId) }
                            .pagingKeys(
                                atStart = cell.startMillis <= state.windowStartMillis,
                                atEnd = cell.stopMillis >= state.windowEndMillis,
                                state = state,
                                viewModel = viewModel,
                                onInfo = { viewModel.showDetails(row.channel.ordinal, cell.programmeId) },
                            ),
                        onClick = { viewModel.select(row.channel.ordinal, cell.programmeId) },
                        onLongClick = { viewModel.showDetails(row.channel.ordinal, cell.programmeId) },
                    )
                }
            }
        }
    }
}

/** Left on the first cell / Right on the last cell pages in time; Info opens details. */
private fun Modifier.pagingKeys(
    atStart: Boolean,
    atEnd: Boolean,
    state: EpgGridUiState,
    viewModel: EpgGridViewModel,
    onInfo: (() -> Unit)? = null,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.nativeKeyEvent.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (atStart && state.canPageBack) {
            viewModel.pageBack()
            true
        } else {
            false
        }
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (atEnd && state.canPageForward) {
            viewModel.pageForward()
            true
        } else {
            false
        }
        AndroidKeyEvent.KEYCODE_INFO, AndroidKeyEvent.KEYCODE_MENU -> {
            onInfo?.invoke()
            onInfo != null
        }
        else -> false
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
        ProgrammeTiming.PAST -> Color(0xFF141D26)
        else -> UiTokens.Surface
    }
    val catchupDescription = stringResource(R.string.epg_catchup_icon_description)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.semantics {
            contentDescription = listOfNotNull(title, subtitle, if (catchup) catchupDescription else null).joinToString(", ")
        },
        shape = ClickableSurfaceDefaults.shape(TvDimens.Shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            focusedContainerColor = UiTokens.SurfaceFocused,
            contentColor = UiTokens.OnBackground,
            focusedContentColor = UiTokens.OnBackground,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = TvDimens.FocusBorder),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(modifier = Modifier.padding(horizontal = UiTokens.SpaceS, vertical = UiTokens.SpaceXs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
                if (catchup) Text(text = CATCHUP_ICON, color = UiTokens.Accent)
                Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            }
            subtitle?.let { Text(text = it, maxLines = 1, color = UiTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall) }
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

private val CHANNEL_COLUMN = 240.dp
private val ROW_HEIGHT = 64.dp
private const val CATCHUP_ICON = "↺" // ↺
private const val TICK_MS = 60_000L
