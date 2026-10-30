package app.playlistplayer.ui.mobile.epg

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.playlistplayer.R
import app.playlistplayer.feature.epg.AiringChoice
import app.playlistplayer.feature.epg.EpgCell
import app.playlistplayer.feature.epg.EpgEvent
import app.playlistplayer.feature.epg.EpgGridUiState
import app.playlistplayer.feature.epg.EpgGridViewModel
import app.playlistplayer.feature.epg.EpgRow
import app.playlistplayer.feature.epg.ProgrammeDetails
import app.playlistplayer.feature.epg.ProgrammeTiming
import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.mobile.MobileDimens
import app.playlistplayer.ui.mobile.MobileEmpty
import app.playlistplayer.ui.mobile.MobileError
import app.playlistplayer.ui.mobile.MobileLoading
import app.playlistplayer.ui.tv.player.rememberTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import java.time.format.DateTimeFormatter
import java.util.Locale

object MobileGuideTags {
    const val ROOT = "mobile.guide"
    const val GROUP_BUTTON = "mobile.guide.group"
    const val DAYS = "mobile.guide.days"
    const val GRID = "mobile.guide.grid"
    const val NOW_LINE = "mobile.guide.nowLine"
    const val EARLIER = "mobile.guide.earlier"
    const val LATER = "mobile.guide.later"
}

private sealed interface SectionsLoad {
    data object Loading : SectionsLoad
    data object Failed : SectionsLoad
    data class Loaded(val sections: List<PlaylistSection>) : SectionsLoad
}

private val GroupKeySaver = listSaver<GroupKey?, Long>(
    save = { key -> if (key == null) emptyList() else listOf(key.playlistId, key.groupOrdinal.toLong()) },
    restore = { values -> if (values.size == 2) GroupKey(values[0], values[1].toInt()) else null },
)

/**
 * Phone Guide (U13 over the shared U9 [EpgGridViewModel]): a group picker (playlist-name sections →
 * groups, in a bottom sheet), then the touch grid of that group. One ViewModel per selected group,
 * kept while the app runs so returning from a player restores the window and the focused programme.
 *
 * [sections] is the live browse source's group list (PIN-hidden groups already filtered out).
 */
@Composable
fun MobileGuideScreen(
    sections: Flow<List<PlaylistSection>>,
    guide: MobileGuideDependencies,
    onPlayCatchup: (CatchupRequest) -> Unit,
    onWatchLive: (LiveChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val load by remember(sections, attempt) {
        sections
            .map<List<PlaylistSection>, SectionsLoad> { list -> SectionsLoad.Loaded(list.filter { it.groups.isNotEmpty() }) }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(SectionsLoad.Failed)
            }
    }.collectAsStateWithLifecycle(initialValue = SectionsLoad.Loading)
    var selected by rememberSaveable(stateSaver = GroupKeySaver) { mutableStateOf<GroupKey?>(null) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().testTag(MobileGuideTags.ROOT)) {
        when (val l = load) {
            SectionsLoad.Loading -> MobileLoading()
            SectionsLoad.Failed -> MobileError(stringResource(R.string.epg_load_failed), onRetry = { attempt++ })
            is SectionsLoad.Loaded -> {
                val group = resolveGuideGroup(l.sections, selected)
                if (group == null) {
                    MobileEmpty(title = stringResource(R.string.live_no_playlists))
                } else {
                    GroupButton(guideGroupLabel(l.sections, group), onClick = { pickerOpen = true })
                    val viewModel = viewModel(key = "mobile-guide-${group.playlistId}-${group.groupOrdinal}") {
                        EpgGridViewModel(
                            group = group,
                            channels = guide.channels,
                            grid = guide.grid,
                            otherVersions = guide.coordinator,
                            clock = guide.clock,
                            zone = guide.zone,
                            windowSlots = GuideGeometry.PHONE_WINDOW_SLOTS,
                        )
                    }
                    // Scroll positions belong to the group: a new group opens at its top and at now.
                    key(group) {
                        MobileGuideGrid(viewModel, onPlayCatchup = onPlayCatchup, onWatchLive = onWatchLive, modifier = Modifier.weight(1f))
                    }
                }
                if (pickerOpen) {
                    GroupPickerSheet(
                        sections = l.sections,
                        selected = group,
                        onPick = { key ->
                            selected = key
                            pickerOpen = false
                        },
                        onDismiss = { pickerOpen = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupButton(label: Pair<String, String>?, onClick: () -> Unit) {
    val (section, group) = label ?: ("" to "")
    val description = stringResource(R.string.mobile_guide_group_button_description, section, group)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = MobileDimens.MinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = MobileDimens.ScreenPadding, vertical = UiTokens.SpaceXs)
            .testTag(MobileGuideTags.GROUP_BUTTON)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(section, style = MaterialTheme.typography.labelMedium, color = UiTokens.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(group, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("▾", style = MaterialTheme.typography.titleMedium, color = UiTokens.OnSurfaceMuted)
    }
}

/** Playlist-name sections → groups (R25); groups keep the playlist's own order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupPickerSheet(
    sections: List<PlaylistSection>,
    selected: GroupKey?,
    onPick: (GroupKey) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedText = stringResource(R.string.mobile_guide_selected)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            item {
                Text(
                    text = stringResource(R.string.mobile_guide_choose_group),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = MobileDimens.ScreenPadding, vertical = UiTokens.SpaceS).semantics { heading() },
                )
            }
            sections.forEach { section ->
                item(key = "section-${section.playlistId}") {
                    Text(
                        text = section.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = UiTokens.Accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(UiTokens.Surface)
                            .padding(horizontal = MobileDimens.ScreenPadding, vertical = UiTokens.SpaceS)
                            .semantics { heading() },
                    )
                }
                items(section.groups, key = { "group-${it.playlistId}-${it.ordinal}" }) { group ->
                    val isSelected = group.key == selected
                    ListItem(
                        headlineContent = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Text(group.channelCount.toString(), style = MaterialTheme.typography.labelMedium) },
                        colors = ListItemDefaults.colors(containerColor = if (isSelected) UiTokens.SurfaceFocused else Color.Transparent),
                        modifier = Modifier
                            .clickable(role = Role.Button) { onPick(group.key) }
                            .semantics(mergeDescendants = true) {
                                this.selected = isSelected
                                if (isSelected) stateDescription = selectedText
                            },
                    )
                }
            }
        }
    }
}

/**
 * The touch grid for one group: day chips (Now, −archive days … +2), Earlier / Later paging, then
 * channels as rows with a sticky channel column and time scrolling horizontally (30-minute slots,
 * now-line). Taps route exactly as OK on TV through the shared ViewModel: an eligible past programme
 * plays from the archive, the airing one offers Watch live / Restart, anything else shows details;
 * long press shows details; a "No guide data" row or a channel name plays the channel live.
 */
@Composable
fun MobileGuideGrid(
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

    Column(modifier = modifier.fillMaxSize()) {
        when {
            state.emptyGroup -> MobileEmpty(title = stringResource(R.string.epg_empty_group))
            state.loadFailed && state.rows.isEmpty() -> MobileError(stringResource(R.string.epg_load_failed), onRetry = viewModel::refresh)
            state.rows.isEmpty() -> MobileLoading()
            else -> {
                DayChips(state, onDay = viewModel::jumpToDay, onNow = viewModel::jumpToNow)
                PagingRow(state, onEarlier = viewModel::pageBack, onLater = viewModel::pageForward)
                if (state.loadFailed) {
                    MobileError(stringResource(R.string.epg_load_failed), modifier = Modifier.height(160.dp), onRetry = viewModel::refresh)
                }
                Grid(state, viewModel, Modifier.weight(1f))
            }
        }
    }

    state.airingChoice?.let { choice ->
        AiringSheet(choice, onWatchLive = viewModel::chooseWatchLive, onRestart = viewModel::chooseRestart, onDismiss = viewModel::dismissChoice)
    }
    state.details?.let { details ->
        DetailsSheet(details, onPlay = viewModel::playFromDetails, onDismiss = viewModel::dismissDetails)
    }
}

@Composable
private fun DayChips(state: EpgGridUiState, onDay: (Int) -> Unit, onNow: () -> Unit) {
    val formatter = remember { DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()) }
    val chips = dayChips(state.days, state.selectedDayOffset)
    val description = stringResource(R.string.mobile_guide_days_description)
    val listState = rememberLazyListState()
    LaunchedEffect(chips.size) {
        val today = chips.indexOfFirst { it.label == DayLabel.TODAY }
        if (today > 0) listState.scrollToItem(today) // Now chip first, then today among the archive days.
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = UiTokens.SpaceS)
            .testTag(MobileGuideTags.DAYS)
            .semantics { contentDescription = description },
    ) {
        item(key = "now") { AssistChip(onClick = onNow, label = { Text(stringResource(R.string.epg_now)) }) }
        items(chips, key = { it.offset }) { chip ->
            val label = when (chip.label) {
                DayLabel.TODAY -> stringResource(R.string.epg_today)
                DayLabel.YESTERDAY -> stringResource(R.string.epg_yesterday)
                DayLabel.TOMORROW -> stringResource(R.string.epg_tomorrow)
                DayLabel.DATE -> formatter.format(chip.date)
            }
            FilterChip(selected = chip.selected, onClick = { onDay(chip.offset) }, label = { Text(label) })
        }
    }
}

@Composable
private fun PagingRow(state: EpgGridUiState, onEarlier: () -> Unit, onLater: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onEarlier, enabled = state.canPageBack, modifier = Modifier.testTag(MobileGuideTags.EARLIER)) {
            Text("‹ " + stringResource(R.string.mobile_guide_earlier))
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (state.loading) {
                val updating = stringResource(R.string.mobile_guide_updating)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.5f).semantics { contentDescription = updating })
            }
        }
        TextButton(onClick = onLater, enabled = state.canPageForward, modifier = Modifier.testTag(MobileGuideTags.LATER)) {
            Text(stringResource(R.string.mobile_guide_later) + " ›")
        }
    }
}

@Composable
private fun Grid(state: EpgGridUiState, viewModel: EpgGridViewModel, modifier: Modifier) {
    val time = rememberTimeFormatter()
    val geometry = GuideGeometry.of(state)
    val timelineWidth = geometry.timelineWidthDp.dp
    // One horizontal scroll position shared by the header and every row: the channel column stays put.
    val scroll = rememberScrollState()
    val listState = rememberLazyListState()
    var shownStart by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(state.windowStartMillis) {
        val windowMillis = state.windowEndMillis - state.windowStartMillis
        when (GuidePaging.anchorAfter(shownStart, state.windowStartMillis, windowMillis)) {
            ScrollAnchor.KEEP -> Unit
            ScrollAnchor.START -> scroll.scrollTo(0)
            ScrollAnchor.END -> scroll.scrollTo(scroll.maxValue)
        }
        shownStart = state.windowStartMillis
    }

    val gridDescription = stringResource(R.string.mobile_guide_grid_description)
    Column(modifier = modifier.fillMaxSize().semantics { contentDescription = gridDescription }) {
        // Slot header.
        Row {
            Box(Modifier.width(CHANNEL_COLUMN))
            TimelineScroller(scroll, Modifier.weight(1f)) {
                Box(Modifier.width(timelineWidth).height(HEADER_HEIGHT)) {
                    geometry.slotXs(state.slots).zip(state.slots).forEach { (x, slot) ->
                        Text(
                            text = time(slot),
                            style = MaterialTheme.typography.labelMedium,
                            color = UiTokens.OnSurfaceMuted,
                            modifier = Modifier.offset(x = x.dp + UiTokens.SpaceXs).align(Alignment.CenterStart),
                        )
                    }
                    geometry.nowLineX(state.nowMillis)?.let { x -> NowLine(x, Modifier.testTag(MobileGuideTags.NOW_LINE)) }
                }
            }
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs),
            modifier = Modifier.fillMaxSize().testTag(MobileGuideTags.GRID),
        ) {
            items(state.rows, key = { it.channel.ordinal }) { row ->
                GridRow(row, state, geometry, scroll, time, onTap = viewModel::handle)
            }
        }
    }
}

@Composable
private fun TimelineScroller(scroll: ScrollState, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.horizontalScroll(scroll)) { content() }
}

@Composable
private fun NowLine(xDp: Float, modifier: Modifier = Modifier) {
    Box(modifier = modifier.offset(x = xDp.dp).width(2.dp).fillMaxHeight().background(UiTokens.Error))
}

@Composable
private fun GridRow(
    row: EpgRow,
    state: EpgGridUiState,
    geometry: GuideGeometry,
    scroll: ScrollState,
    time: (Long) -> String,
    onTap: (GuideTap) -> Unit,
) {
    val channelDescription = stringResource(R.string.mobile_guide_channel_description, row.number, row.channel.name)
    Row(modifier = Modifier.height(ROW_HEIGHT)) {
        // Sticky channel column (outside the horizontal scroll). Tap watches live.
        Column(
            modifier = Modifier
                .width(CHANNEL_COLUMN)
                .fillMaxHeight()
                .background(UiTokens.Surface)
                .clickable(role = Role.Button) { onTap(guideTapFor(row, cell = null)) }
                .padding(horizontal = UiTokens.SpaceS)
                .semantics(mergeDescendants = true) { contentDescription = channelDescription },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(row.number.toString(), style = MaterialTheme.typography.labelSmall, color = UiTokens.OnSurfaceMuted)
            Text(row.channel.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TimelineScroller(scroll, Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.width(geometry.timelineWidthDp.dp).fillMaxHeight()) {
                if (row.noGuideData) {
                    val description = stringResource(R.string.mobile_guide_no_data_description, row.channel.name)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 2.dp)
                            .background(UiTokens.Surface, MaterialTheme.shapes.extraSmall)
                            .clickable(role = Role.Button) { onTap(guideTapFor(row, cell = null)) }
                            .semantics(mergeDescendants = true) { contentDescription = description },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            stringResource(R.string.epg_no_guide_data),
                            color = UiTokens.OnSurfaceMuted,
                            // Keep the label on screen whatever the scroll position.
                            modifier = Modifier.offset { IntOffset(scroll.value, 0) }.padding(horizontal = UiTokens.SpaceS),
                        )
                    }
                } else {
                    row.cells.forEach { cell ->
                        GuideCell(
                            cell = cell,
                            geometry = geometry,
                            subtitle = "${time(cell.startMillis)} – ${time(cell.stopMillis)}",
                            onTap = { onTap(guideTapFor(row, cell)) },
                            onLongPress = { onTap(guideTapFor(row, cell, longPress = true)) },
                        )
                    }
                }
                geometry.nowLineX(state.nowMillis)?.let { x -> NowLine(x) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideCell(
    cell: EpgCell,
    geometry: GuideGeometry,
    subtitle: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val span = geometry.spanOf(cell)
    val title = cell.title ?: stringResource(R.string.epg_untitled)
    val catchupDescription = stringResource(R.string.epg_catchup_icon_description)
    val container = when (cell.timing) {
        ProgrammeTiming.AIRING -> UiTokens.SurfaceVariant
        ProgrammeTiming.PAST -> PastCell
        ProgrammeTiming.FUTURE -> UiTokens.Surface
    }
    Column(
        modifier = Modifier
            .offset(x = span.xDp.dp)
            .width(span.widthDp.dp)
            .fillMaxHeight()
            .background(container, MaterialTheme.shapes.extraSmall)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.mobile_guide_play_action),
                onLongClickLabel = stringResource(R.string.mobile_guide_details_action),
                onLongClick = onLongPress,
                onClick = onTap,
            )
            .padding(horizontal = UiTokens.SpaceS, vertical = UiTokens.SpaceXs)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(title, subtitle, if (cell.catchup) catchupDescription else null).joinToString(", ")
            },
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
            if (cell.catchup) Text(CATCHUP_ICON, color = UiTokens.Accent, style = MaterialTheme.typography.bodyMedium)
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = UiTokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
    }
}

/** Tap on the airing programme: Watch live, or Restart when its archive allows (R8). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiringSheet(choice: AiringChoice, onWatchLive: () -> Unit, onRestart: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = MobileDimens.ScreenPadding, vertical = UiTokens.SpaceS),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
        ) {
            Text(
                text = choice.title ?: stringResource(R.string.epg_untitled),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.epg_details_airing), color = UiTokens.OnSurfaceMuted)
            Button(onClick = onWatchLive, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MobileDimens.MinTouchTarget)) {
                Text(stringResource(R.string.epg_watch_live))
            }
            if (choice.canRestart) {
                OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MobileDimens.MinTouchTarget)) {
                    Text(CATCHUP_ICON + "  " + stringResource(R.string.epg_restart))
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MobileDimens.MinTouchTarget)) {
                Text(stringResource(R.string.epg_close))
            }
        }
    }
}

/** Programme details; "Play from start" only when the programme is on catch-up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(details: ProgrammeDetails, onPlay: () -> Unit, onDismiss: () -> Unit) {
    val time = rememberTimeFormatter()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = MobileDimens.ScreenPadding, vertical = UiTokens.SpaceS),
            verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
        ) {
            Text(
                text = details.title ?: stringResource(R.string.epg_untitled),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            details.subTitle?.let { Text(it) }
            val timing = when (details.timing) {
                ProgrammeTiming.AIRING -> stringResource(R.string.epg_details_airing)
                ProgrammeTiming.FUTURE -> stringResource(R.string.epg_details_upcoming)
                ProgrammeTiming.PAST -> null
            }
            Text(
                text = listOfNotNull(details.channelName, "${time(details.startMillis)} – ${time(details.stopMillis)}", details.episodeNum, timing).joinToString(" · "),
                color = UiTokens.OnSurfaceMuted,
            )
            if (details.categories.isNotEmpty()) Text(details.categories.joinToString(", "), color = UiTokens.OnSurfaceMuted)
            details.description?.let { Text(it, maxLines = 12, overflow = TextOverflow.Ellipsis) }
            if (details.timing == ProgrammeTiming.PAST && !details.catchupAvailable) {
                Text(stringResource(R.string.epg_details_not_available), color = UiTokens.OnSurfaceMuted)
            }
            if (details.catchupAvailable) {
                Button(onClick = onPlay, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MobileDimens.MinTouchTarget)) {
                    Text(CATCHUP_ICON + "  " + stringResource(R.string.epg_play_from_start))
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MobileDimens.MinTouchTarget)) {
                Text(stringResource(R.string.epg_close))
            }
        }
    }
}

private val CHANNEL_COLUMN = 112.dp
private val ROW_HEIGHT = 64.dp
private val HEADER_HEIGHT = 28.dp
private val PastCell = Color(0xFF141D26)
private const val CATCHUP_ICON = "↺" // same glyph as the TV guide
private const val TICK_MS = 60_000L
