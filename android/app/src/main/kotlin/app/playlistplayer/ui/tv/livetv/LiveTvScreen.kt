package app.playlistplayer.ui.tv.livetv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as storedViewModel
import app.playlistplayer.R
import app.playlistplayer.feature.epg.EpgGridViewModel
import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.LiveChannel
import app.playlistplayer.ui.common.GuideDependencies
import app.playlistplayer.ui.common.UiState
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.common.live.LiveTvViewModel
import app.playlistplayer.ui.common.live.NumberEntryController
import app.playlistplayer.ui.tv.components.TvUiState
import app.playlistplayer.ui.tv.epg.EpgGridScreen
import app.playlistplayer.ui.tv.live.GroupRail
import app.playlistplayer.ui.tv.live.NumberEntryOverlay
import app.playlistplayer.ui.tv.live.digitFor
import app.playlistplayer.ui.tv.theme.TvDimens
import kotlinx.coroutines.delay

object LiveTvTags {
    const val ROOT = "livetv"
    const val GRID_PANE = "livetv.grid"
}

/**
 * Live TV (U9; the former Live and Guide destinations): the group rail sectioned by playlist name
 * (R25) beside the guide grid of the selected group ([EpgGridScreen] over the shared
 * [EpgGridViewModel], one per group in the activity store so returning from a player restores its
 * window and focus).
 *
 * - OK on a group selects it and moves focus into its grid; focusing a group alone does not
 *   (no guide load per focused group).
 * - In the grid, OK on a channel name (or a "No guide data" row) plays the channel live via
 *   [onPlayChannel]; programmes keep the guide's routing (eligible past → catch-up, airing →
 *   Watch live / Restart, otherwise details).
 * - Shown (cold start or back from a player): focus goes into the grid at the remembered channel,
 *   on first open the last channel played ([LiveTvViewModel] restores its group).
 * - Digits jump to the Nth channel of the group ([NumberEntryController]); Back in the grid returns
 *   to the group rail, Back on the rail is left to the shell.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveTvScreen(
    viewModel: LiveTvViewModel,
    guide: GuideDependencies,
    onPlayChannel: (LiveChannel) -> Unit,
    onPlayCatchup: (CatchupRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val groupsFocus = remember { FocusRequester() }
    var railHasFocus by remember { mutableStateOf(false) }
    var gridHasFocus by remember { mutableStateOf(false) }
    /** Starts pending: the grid takes focus once loaded each time the screen is shown. */
    var gridFocusRequest by remember { mutableIntStateOf(1) }
    val numberEntry = remember { NumberEntryController() }
    var entry by remember { mutableStateOf(NumberEntryController.State()) }
    var notFound by remember { mutableStateOf<String?>(null) }

    val group = state.selectedGroup
    val grid: EpgGridViewModel? = group?.let { key ->
        storedViewModel(key = "tv-livetv-${key.playlistId}-${key.groupOrdinal}") {
            EpgGridViewModel(key, guide.channels, guide.grid, guide.coordinator, guide.clock, guide.zone)
        }
    }
    val rows = grid?.state?.collectAsStateWithLifecycle()?.value?.rows.orEmpty()
    val playableCount = rows.size

    // First open: focus the last channel played in its restored group.
    LaunchedEffect(grid, state.restoreChannel) {
        val ordinal = state.restoreChannel ?: return@LaunchedEffect
        if (grid == null) return@LaunchedEffect
        grid.onFocus(ordinal, null)
        viewModel.onRestoreHandled()
        gridFocusRequest++
    }

    fun handle(outcome: NumberEntryController.Outcome?) {
        when (outcome) {
            is NumberEntryController.Outcome.Jump -> rows.firstOrNull { it.number == outcome.number }?.let { row ->
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

    BackHandler(enabled = entry.isActive) {
        numberEntry.cancel()
        entry = numberEntry.state
    }
    BackHandler(enabled = !entry.isActive && gridHasFocus) { runCatching { groupsFocus.requestFocus() } }

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
                    else -> false
                }
            },
    ) {
        TvUiState(state = state.sections, modifier = Modifier.fillMaxSize().padding(TvDimens.ScreenPadding)) { sections ->
            Row(
                // The grid pads itself (overscan); the rail takes the left and vertical overscan.
                modifier = Modifier.fillMaxSize().padding(start = UiTokens.OverscanHorizontal),
            ) {
                GroupRail(
                    sections = sections,
                    selected = group,
                    onFocusGroup = {},
                    onOpenGroup = { key ->
                        viewModel.selectGroup(key)
                        gridFocusRequest++
                    },
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .padding(vertical = UiTokens.OverscanVertical)
                        .focusRequester(groupsFocus)
                        .focusRestorer()
                        .onFocusChanged { railHasFocus = it.hasFocus },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
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
                                autoFocus = !railHasFocus,
                                focusRequest = gridFocusRequest,
                            )
                        }
                    }
                }
            }
        }
        if (state.sections is UiState.Content) {
            NumberEntryOverlay(digits = entry.digits, notFound = notFound, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}
