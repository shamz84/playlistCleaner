package app.playlistplayer.ui.tv.live

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.ui.common.GroupItem
import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.UiState
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.common.live.ChannelNavigator
import app.playlistplayer.ui.common.live.ChannelRow
import app.playlistplayer.ui.common.live.LiveBrowseState
import app.playlistplayer.ui.common.live.LiveBrowseViewModel
import app.playlistplayer.ui.common.live.indexOfNumber
import app.playlistplayer.ui.tv.components.TvBadge
import app.playlistplayer.ui.tv.components.TvUiState
import app.playlistplayer.ui.tv.theme.TvDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.playlistplayer.ui.common.live.NumberEntryController

object LiveTags {
    const val GROUPS = "live.groups"
    const val CHANNELS = "live.channels"
    const val NUMBER_OVERLAY = "live.numberEntry"
}

/** Live browse (U9): group rail sectioned by playlist name (R25), channel list, number entry. */
@Composable
fun LiveScreen(
    viewModel: LiveBrowseViewModel,
    onPlay: (ChannelRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LiveContent(state = state, onSelectGroup = viewModel::selectGroup, onPlay = onPlay, modifier = modifier)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveContent(
    state: LiveBrowseState,
    onSelectGroup: (GroupKey) -> Unit,
    onPlay: (ChannelRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groupsFocus = remember { FocusRequester() }
    val channelsFocus = remember { FocusRequester() }
    val channelListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val numberEntry = remember { NumberEntryController() }
    var entry by remember { mutableStateOf(NumberEntryController.State()) }
    var notFound by remember { mutableStateOf<String?>(null) }
    var channelsHaveFocus by remember { mutableStateOf(false) }
    var focusedChannelIndex by remember { mutableStateOf(-1) }
    /** Index of the channel row that should take focus once composed (number entry, channel up/down). */
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }

    val channels = (state.channels as? UiState.Content)?.value.orEmpty()
    val playableCount = state.playableCount

    fun focusChannel(index: Int) {
        pendingFocusIndex = index
        scope.launch { channelListState.scrollToItem(index) }
    }

    fun handle(outcome: NumberEntryController.Outcome?) {
        when (outcome) {
            is NumberEntryController.Outcome.Jump -> indexOfNumber(channels, outcome.number)?.let(::focusChannel)
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

    // Back inside the channel list returns to the group rail (focus restored to the selected group);
    // a pending number entry is cancelled first. Back on the group rail is left to the shell.
    BackHandler(enabled = entry.isActive) {
        numberEntry.cancel()
        entry = numberEntry.state
    }
    BackHandler(enabled = !entry.isActive && channelsHaveFocus) { runCatching { groupsFocus.requestFocus() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(UiTokens.Background)
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
                    code == AndroidKeyEvent.KEYCODE_DPAD_CENTER && entry.isActive ||
                        code == AndroidKeyEvent.KEYCODE_ENTER && entry.isActive -> {
                        handle(numberEntry.commit(playableCount))
                        true
                    }
                    code == AndroidKeyEvent.KEYCODE_CHANNEL_UP || code == AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        val navigator = ChannelNavigator.forRows(channels)
                        val target = if (code == AndroidKeyEvent.KEYCODE_CHANNEL_UP) {
                            navigator.channelUp(focusedChannelIndex)
                        } else {
                            navigator.channelDown(if (focusedChannelIndex < 0) channels.size else focusedChannelIndex)
                        }
                        target?.let(::focusChannel)
                        true
                    }
                    else -> false
                }
            },
    ) {
        TvUiState(state = state.sections, modifier = Modifier.fillMaxSize().padding(TvDimens.ScreenPadding)) { sections ->
            Row(
                modifier = Modifier.fillMaxSize().padding(TvDimens.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceL),
            ) {
                GroupRail(
                    sections = sections,
                    selected = state.selectedGroup,
                    onFocusGroup = onSelectGroup,
                    onOpenGroup = { key ->
                        onSelectGroup(key)
                        runCatching { channelsFocus.requestFocus() }
                    },
                    modifier = Modifier
                        .width(340.dp)
                        .fillMaxHeight()
                        .focusRequester(groupsFocus)
                        .focusRestorer(),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onFocusChanged { channelsHaveFocus = it.hasFocus },
                ) {
                    TvUiState(state = state.channels, modifier = Modifier.fillMaxSize()) { rows ->
                        ChannelList(
                            rows = rows,
                            listState = channelListState,
                            pendingFocusIndex = pendingFocusIndex,
                            onPendingFocusHandled = { pendingFocusIndex = null },
                            onChannelFocused = { focusedChannelIndex = it },
                            onPlay = onPlay,
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(channelsFocus)
                                .focusRestorer(),
                        )
                    }
                }
            }
        }
        NumberEntryOverlay(digits = entry.digits, notFound = notFound, modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun GroupRail(
    sections: List<PlaylistSection>,
    selected: GroupKey?,
    onFocusGroup: (GroupKey) -> Unit,
    onOpenGroup: (GroupKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.live_groups_description)
    LazyColumn(
        modifier = modifier.testTag(LiveTags.GROUPS).semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs),
    ) {
        sections.forEach { section ->
            // Section header: playlist name, not focusable (R25).
            item(key = "section-${section.playlistId}", contentType = "header") {
                Text(
                    text = section.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = UiTokens.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = UiTokens.SpaceM, bottom = UiTokens.SpaceXs, start = UiTokens.SpaceS)
                        .semantics { heading() },
                )
            }
            section.groups.forEach { group ->
                item(key = "group-${group.playlistId}-${group.ordinal}", contentType = "group") {
                    GroupRow(group, selected = group.key == selected, onFocus = onFocusGroup, onOpen = onOpenGroup)
                }
            }
        }
    }
}

@Composable
private fun GroupRow(group: GroupItem, selected: Boolean, onFocus: (GroupKey) -> Unit, onOpen: (GroupKey) -> Unit) {
    val description = stringResource(R.string.live_group_item_description, group.name, group.channelCount)
    ListItem(
        selected = selected,
        onClick = { onOpen(group.key) },
        headlineContent = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Text(group.channelCount.toString(), style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier
            .onFocusChanged { if (it.isFocused) onFocus(group.key) }
            .semantics { contentDescription = description },
        scale = ListItemDefaults.scale(focusedScale = 1.03f, focusedSelectedScale = 1.03f),
        border = ListItemDefaults.border(focusedBorder = TvDimens.FocusBorder, focusedSelectedBorder = TvDimens.FocusBorder),
    )
}

@Composable
private fun ChannelList(
    rows: List<ChannelRow>,
    listState: LazyListState,
    pendingFocusIndex: Int?,
    onPendingFocusHandled: () -> Unit,
    onChannelFocused: (Int) -> Unit,
    onPlay: (ChannelRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.live_channels_description)
    LazyColumn(
        state = listState,
        modifier = modifier.testTag(LiveTags.CHANNELS).semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs),
    ) {
        itemsIndexed(rows, key = { _, row -> "${row.playlistId}-${row.ordinal}" }, contentType = { _, row -> row.isHeader }) { index, row ->
            if (row.isHeader) {
                // Separator entries are listed (R11) but are neither focusable nor playable.
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = UiTokens.OnSurfaceMuted,
                    modifier = Modifier.padding(vertical = UiTokens.SpaceS, horizontal = UiTokens.SpaceM).semantics { heading() },
                )
            } else {
                val requester = remember { FocusRequester() }
                LaunchedEffect(pendingFocusIndex) {
                    if (pendingFocusIndex == index) {
                        runCatching { requester.requestFocus() }
                        onPendingFocusHandled()
                    }
                }
                ChannelItem(
                    row = row,
                    onPlay = onPlay,
                    modifier = Modifier
                        .focusRequester(requester)
                        .onFocusChanged { if (it.isFocused) onChannelFocused(index) },
                )
            }
        }
    }
}

@Composable
private fun ChannelItem(row: ChannelRow, onPlay: (ChannelRow) -> Unit, modifier: Modifier = Modifier) {
    val number = row.number ?: 0
    val description = if (row.hasCatchup) {
        stringResource(R.string.live_channel_catchup_description, number, row.name)
    } else {
        stringResource(R.string.live_channel_item_description, number, row.name)
    }
    ListItem(
        selected = false,
        onClick = { onPlay(row) },
        headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = UiTokens.OnSurfaceMuted,
                modifier = Modifier.width(56.dp),
            )
        },
        trailingContent = if (row.hasCatchup) {
            { TvBadge(stringResource(R.string.live_catchup_badge)) }
        } else {
            null
        },
        modifier = modifier.semantics { contentDescription = description },
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
        border = ListItemDefaults.border(focusedBorder = TvDimens.FocusBorder),
    )
}

@Composable
private fun NumberEntryOverlay(digits: String, notFound: String?, modifier: Modifier = Modifier) {
    val text = when {
        digits.isNotEmpty() -> digits
        notFound != null -> stringResource(R.string.live_number_entry_not_found, notFound)
        else -> return
    }
    val description = if (digits.isNotEmpty()) stringResource(R.string.live_number_entry_description, digits) else text
    Column(
        modifier = modifier
            .padding(TvDimens.ScreenPadding)
            .background(UiTokens.SurfaceFocused, TvDimens.Shape)
            .padding(horizontal = UiTokens.SpaceXl, vertical = UiTokens.SpaceM)
            .testTag(LiveTags.NUMBER_OVERLAY)
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = if (digits.isNotEmpty()) 56.sp else 24.sp),
            color = if (digits.isNotEmpty()) UiTokens.OnBackground else UiTokens.Warning,
        )
    }
}

private fun digitFor(keyCode: Int): Int? = when (keyCode) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> keyCode - AndroidKeyEvent.KEYCODE_0
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> keyCode - AndroidKeyEvent.KEYCODE_NUMPAD_0
    else -> null
}
