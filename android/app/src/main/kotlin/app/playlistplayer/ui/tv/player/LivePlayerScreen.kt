package app.playlistplayer.ui.tv.player

import app.playlistplayer.ui.common.ScreenWakePolicy
import app.playlistplayer.ui.common.KeepScreenOn
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.core.player.ConfiguredPlayer
import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.LivePlayerEvent
import app.playlistplayer.feature.live.LivePlayerUiState
import app.playlistplayer.feature.live.LivePlayerViewModel
import app.playlistplayer.feature.live.LiveStartError
import app.playlistplayer.feature.live.VersionItem
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.components.TvBadge
import app.playlistplayer.ui.tv.components.TvButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import app.playlistplayer.ui.common.badge
import app.playlistplayer.ui.common.rememberTimeFormatter

object LivePlayerTags {
    const val ROOT = "livePlayer.root"
    const val OVERLAY = "livePlayer.overlay"
    const val VERSIONS = "livePlayer.versions"
    const val NUMBER_ENTRY = "livePlayer.numberEntry"
    const val LAST_CHANNEL = "livePlayer.lastChannel"
    const val CORNER_INDICATOR = "livePlayer.cornerIndicator"
}

/**
 * Live playback (U9): full-screen video with the channel overlay. Remote keys:
 * - OK: show the overlay (long-press: "Other versions"); commits a pending number entry;
 * - Up / Channel+ and Down / Channel−: channel up/down within the current group;
 * - digits: number entry (position within the group);
 * - Menu / Info: "Other versions";
 * - Last channel (KEYCODE_LAST_CHANNEL, or "Last channel" on the overlay): the previous channel;
 * - Back: closes the panel, then the overlay, then leaves the player.
 *
 * Zapping (U22): the overlay shows the new channel at once while the SurfaceView keeps the last
 * frame until the new stream renders; buffering is a small corner indicator, never a centre
 * spinner. With [keepPlayingOnExit] true when leaving (back to Live TV), the stream keeps playing
 * for the Live TV preview.
 *
 * [player] is the controller's [ConfiguredPlayer] (video surface only); the ViewModel drives
 * playback through its `PlayerDriver`.
 */
@Composable
fun LivePlayerScreen(
    viewModel: LivePlayerViewModel,
    player: ConfiguredPlayer,
    onStartCatchup: (CatchupRequest) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    keepPlayingOnExit: () -> Boolean = { false },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keepPlaying by rememberUpdatedState(keepPlayingOnExit)
    val startCatchup by rememberUpdatedState(onStartCatchup)
    val rootFocus = remember { FocusRequester() }
    val buttonsFocus = remember { FocusRequester() }
    var longPressHandled by remember { mutableStateOf(false) }

    // Starts (or, shown again after catch-up, resumes) live playback after the previous player's dispose.
    LaunchedEffect(viewModel) { viewModel.start() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LivePlayerEvent.StartCatchup -> startCatchup(event.request)
            }
        }
    }
    LaunchedEffect(viewModel) {
        while (isActive) {
            delay(TICK_MS)
            viewModel.tick()
        }
    }
    DisposableEffect(viewModel) { onDispose { if (!keepPlaying()) viewModel.stop() } }

    val panelOpen = state.versionsOpen
    val blockingPanel = state.status.error != null || state.startError != null
    KeepScreenOn(ScreenWakePolicy.isActive(state.status, starting = state.loading, blocked = state.startError != null))
    LaunchedEffect(state.overlayVisible, panelOpen, blockingPanel) {
        if (!panelOpen && !blockingPanel) {
            runCatching { if (state.overlayVisible) buttonsFocus.requestFocus() else rootFocus.requestFocus() }
                .onFailure { runCatching { rootFocus.requestFocus() } }
        }
    }

    BackHandler {
        when {
            state.versionsOpen -> viewModel.closeVersions()
            state.numberDigits.isNotEmpty() -> viewModel.cancelNumber()
            state.overlayVisible && !blockingPanel -> viewModel.hideOverlay()
            else -> onExit()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(LivePlayerTags.ROOT)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (state.versionsOpen) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                val down = event.type == KeyEventType.KeyDown
                val up = event.type == KeyEventType.KeyUp
                digitOf(native.keyCode)?.let { digit ->
                    if (down && native.repeatCount == 0) viewModel.onDigit(digit)
                    return@onPreviewKeyEvent true
                }
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP, AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (down) viewModel.channelUp()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (down) viewModel.channelDown()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> {
                        if (down) viewModel.lastChannel()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_INFO -> {
                        if (up) viewModel.openVersions()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        // With the overlay (or an error panel) showing, OK belongs to the focused button.
                        val pendingNumber = state.numberDigits.isNotEmpty()
                        if (!pendingNumber && (state.overlayVisible || blockingPanel)) return@onPreviewKeyEvent false
                        if (down && native.repeatCount > 0 && !longPressHandled && !pendingNumber) {
                            longPressHandled = true
                            viewModel.openVersions()
                        }
                        if (up) {
                            if (longPressHandled) {
                                longPressHandled = false
                            } else if (!viewModel.commitNumber()) {
                                viewModel.showOverlay()
                            }
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // Left/right reveal the overlay; once visible they move between its buttons.
                        if (blockingPanel) return@onPreviewKeyEvent false
                        if (down) viewModel.showOverlay() // also restarts the auto-hide timer
                        !state.overlayVisible
                    }
                    else -> false
                }
            },
    ) {
        PlayerVideoSurface(player.exoPlayer, Modifier.fillMaxSize())

        // Only the transient "Switched to" notice; buffering is the corner indicator.
        StatusBanner(state.status.copy(indicator = null), Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
        state.cornerIndicator?.let { CornerIndicator(it, Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 32.dp)) }

        if (state.numberDigits.isNotEmpty() || state.numberNotFound != null) {
            NumberEntryBadge(state.numberDigits, state.numberNotFound, Modifier.align(Alignment.TopEnd).padding(48.dp))
        }

        if (state.overlayVisible && !blockingPanel) {
            ChannelOverlay(
                state = state,
                buttonsFocus = buttonsFocus,
                onOtherVersions = viewModel::openVersions,
                onRestart = viewModel::restart,
                onLastChannel = viewModel::lastChannel,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (state.versionsOpen) {
            VersionsPanel(
                versions = state.versions,
                onSelect = viewModel::switchTo,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        state.startError?.let { error ->
            MessagePanel(
                title = state.channel?.name,
                message = stringResource(
                    when (error) {
                        LiveStartError.NOT_FOUND -> R.string.player_start_not_found
                        LiveStartError.NOT_PLAYABLE -> R.string.player_start_not_playable
                        LiveStartError.NO_ACCOUNT -> R.string.player_start_no_account
                    },
                ),
                actionLabel = if (error == LiveStartError.NOT_FOUND) null else stringResource(R.string.player_retry),
                onAction = viewModel::retry,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (state.startError == null) {
            state.status.error?.let { error ->
                PlayerErrorPanel(error = error, onRetry = viewModel::retry, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun ChannelOverlay(
    state: LivePlayerUiState,
    buttonsFocus: FocusRequester,
    onOtherVersions: () -> Unit,
    onRestart: () -> Unit,
    onLastChannel: () -> Unit,
    modifier: Modifier,
) {
    val time = rememberTimeFormatter()
    val channel = state.channel ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xC0000000))
            .padding(horizontal = UiTokens.OverscanHorizontal, vertical = UiTokens.SpaceL)
            .testTag(LivePlayerTags.OVERLAY),
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
            state.number?.let {
                Text(text = stringResource(R.string.player_channel_number, it), style = MaterialTheme.typography.headlineMedium, color = UiTokens.Accent)
            }
            Text(
                text = channel.name,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            state.qualityBadge?.let { TvBadge(it) }
            if (state.currentVersionLabel != null && state.currentVersionLabel != channel.name) {
                Text(text = state.currentVersionLabel, color = UiTokens.OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val nowNext = state.nowNext
        val now = nowNext?.now
        if (now != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
                Text(text = "${time(now.startMillis)} – ${time(now.stopMillis)}", color = UiTokens.OnSurfaceMuted)
                Text(
                    text = stringResource(R.string.player_now, now.title ?: stringResource(R.string.player_untitled)),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            nowNext.progress?.let { ProgressBar(it, Modifier.fillMaxWidth(0.6f)) }
        } else if (nowNext != null) {
            Text(text = stringResource(R.string.player_no_guide), color = UiTokens.OnSurfaceMuted)
        }
        nowNext?.next?.let { next ->
            Text(
                text = "${time(next.startMillis)}  " + stringResource(R.string.player_next, next.title ?: stringResource(R.string.player_untitled)),
                color = UiTokens.OnSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val hasButtons = state.hasOtherVersions || state.canRestart || state.hasLastChannel
        if (hasButtons) {
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM), modifier = Modifier.padding(top = UiTokens.SpaceS)) {
                if (state.hasOtherVersions) {
                    TvButton(
                        text = stringResource(R.string.player_other_versions_count, state.versions.size - 1),
                        onClick = onOtherVersions,
                        modifier = Modifier.focusRequester(buttonsFocus),
                    )
                }
                if (state.canRestart) {
                    TvButton(
                        text = stringResource(R.string.player_restart_programme),
                        onClick = onRestart,
                        modifier = if (state.hasOtherVersions) Modifier else Modifier.focusRequester(buttonsFocus),
                    )
                }
                if (state.hasLastChannel) {
                    TvButton(
                        text = stringResource(R.string.player_last_channel),
                        onClick = onLastChannel,
                        modifier = if (state.hasOtherVersions || state.canRestart) {
                            Modifier.testTag(LivePlayerTags.LAST_CHANNEL)
                        } else {
                            Modifier.focusRequester(buttonsFocus).testTag(LivePlayerTags.LAST_CHANNEL)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionsPanel(versions: List<VersionItem>, onSelect: (String) -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    Column(
        modifier = modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(Color(0xE0101010))
            .padding(UiTokens.SpaceL)
            .testTag(LivePlayerTags.VERSIONS),
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
    ) {
        Text(text = stringResource(R.string.player_other_versions), style = MaterialTheme.typography.titleLarge, color = Color.White)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceXs)) {
            itemsIndexed(versions, key = { _, item -> item.version.id }) { index, item ->
                val focusIndex = versions.indexOfFirst { it.isCurrent }.coerceAtLeast(0)
                ListItem(
                    selected = item.isCurrent,
                    onClick = { onSelect(item.version.id) },
                    modifier = if (index == focusIndex) Modifier.focusRequester(focus) else Modifier,
                    headlineContent = { Text(item.version.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val parts = listOfNotNull(
                            if (item.isCurrent) stringResource(R.string.player_version_playing) else null,
                            if (item.version.hasArchive) stringResource(R.string.player_version_catchup) else null,
                        )
                        if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
                    },
                    trailingContent = { item.version.quality.badge()?.let { TvBadge(it) } },
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun NumberEntryBadge(digits: String, notFound: String?, modifier: Modifier) {
    Text(
        text = if (digits.isNotEmpty()) digits else stringResource(R.string.player_number_not_found, notFound.orEmpty()),
        style = if (digits.isNotEmpty()) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = modifier
            .background(Color(0xC0000000), MaterialTheme.shapes.small)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .testTag(LivePlayerTags.NUMBER_ENTRY)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun digitOf(keyCode: Int): Int? = when (keyCode) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> keyCode - AndroidKeyEvent.KEYCODE_0
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> keyCode - AndroidKeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

private const val TICK_MS = 30_000L
