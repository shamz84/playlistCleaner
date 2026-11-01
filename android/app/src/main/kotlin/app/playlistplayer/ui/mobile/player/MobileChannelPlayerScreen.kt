package app.playlistplayer.ui.mobile.player

import app.playlistplayer.ui.common.ScreenWakePolicy
import app.playlistplayer.ui.common.KeepScreenOn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import app.playlistplayer.R
import app.playlistplayer.core.player.ConfiguredPlayer
import app.playlistplayer.feature.live.CatchupRequest
import app.playlistplayer.feature.live.LivePlayerEvent
import app.playlistplayer.feature.live.LivePlayerUiState
import app.playlistplayer.feature.live.LivePlayerViewModel
import app.playlistplayer.feature.live.LiveStartError
import app.playlistplayer.ui.tv.player.indicatorText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import app.playlistplayer.ui.mobile.vod.OptionsSheet
import app.playlistplayer.ui.mobile.vod.SheetOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import app.playlistplayer.ui.common.badge
import app.playlistplayer.ui.common.Media3Tracks

object MobileChannelPlayerTags {
    const val ROOT = "mobile.channelPlayer"
    const val CONTROLS = "mobile.channelPlayer.controls"
    const val LAST_CHANNEL = "mobile.channelPlayer.lastChannel"
    const val CORNER_INDICATOR = "mobile.channelPlayer.cornerIndicator"
}

/**
 * Fullscreen landscape live player driven by the shared U9 [LivePlayerViewModel] (used when the
 * guide dependencies are wired): now/next with progress, quality badge, "Other versions" sheet
 * (switch manually; failover uses the same list), previous / next channel within the group,
 * "Restart programme" when the airing programme is on catch-up (→ [onStartCatchup]), audio /
 * subtitle sheet, and the U6 status / error panels. Tap toggles the overlay (no double tap on live).
 *
 * [onChannelChanged] reports the tuned channel's ordinal so the host can keep its back-stack entry
 * current (a later re-creation then re-tunes the channel the user was watching).
 */
@Composable
fun MobileChannelPlayerScreen(
    viewModel: LivePlayerViewModel,
    player: ConfiguredPlayer,
    onStartCatchup: (CatchupRequest) -> Unit,
    onChannelChanged: (Int) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FullscreenLandscapeEffect()
    val exo = player.exoPlayer
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startCatchup by rememberUpdatedState(onStartCatchup)
    val channelChanged by rememberUpdatedState(onChannelChanged)
    val exit by rememberUpdatedState(onExit)
    var tracksOpen by remember { mutableStateOf(false) }
    val tracks = state.tracks

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
    LaunchedEffect(state.channel?.ordinal) { state.channel?.ordinal?.let { channelChanged(it) } }

    DisposableEffect(viewModel, exo) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) {
                viewModel.onTracksChanged(Media3Tracks.options(t))
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            viewModel.stop()
        }
    }

    BackHandler {
        when {
            state.versionsOpen -> viewModel.closeVersions()
            else -> exit()
        }
    }

    val blocking = state.status.error != null || state.startError != null
    KeepScreenOn(ScreenWakePolicy.isActive(state.status, starting = state.loading, blocked = state.startError != null))
    Box(modifier = modifier.fillMaxSize().background(Color.Black).testTag(MobileChannelPlayerTags.ROOT)) {
        VideoSurface(exo, Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(viewModel) { detectTapGestures(onTap = { viewModel.toggleOverlay() }) },
        )

        // "Switched to" only; buffering is a small corner indicator instead of a centre spinner (U22).
        PlaybackStatusBanner(state.status.copy(indicator = null), Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 56.dp))
        state.cornerIndicator?.let { indicator ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(end = 16.dp, bottom = if (state.overlayVisible) 120.dp else 16.dp)
                    .background(Scrim, MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag(MobileChannelPlayerTags.CORNER_INDICATOR),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White, strokeWidth = 2.dp)
                Text(indicatorText(indicator), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }

        if ((state.overlayVisible || blocking) && !tracksOpen) {
            ChannelOverlay(
                state = state,
                hasTracks = tracks.hasChoices,
                onClose = { exit() },
                onVersions = viewModel::openVersions,
                onTracks = { tracksOpen = true },
                onPrevious = {
                    viewModel.channelDown()
                    viewModel.showOverlay()
                },
                onNext = {
                    viewModel.channelUp()
                    viewModel.showOverlay()
                },
                onRestart = viewModel::restart,
                onLastChannel = {
                    viewModel.lastChannel()
                    viewModel.showOverlay()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        state.startError?.let { error ->
            PlayerMessagePanel(
                title = state.channel?.name,
                message = stringResource(
                    when (error) {
                        LiveStartError.NOT_FOUND -> R.string.player_start_not_found
                        LiveStartError.NOT_PLAYABLE -> R.string.player_start_not_playable
                        LiveStartError.NO_ACCOUNT -> R.string.player_start_no_account
                    },
                ),
                actionLabel = if (error == LiveStartError.NOT_FOUND) stringResource(R.string.mobile_back) else stringResource(R.string.player_retry),
                onAction = { if (error == LiveStartError.NOT_FOUND) exit() else viewModel.retry() },
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (state.startError == null) {
            state.status.error?.let { PlaybackErrorPanelView(it, onRetry = viewModel::retry, modifier = Modifier.align(Alignment.Center)) }
        }
    }

    if (state.versionsOpen) {
        val catchupLabel = stringResource(R.string.player_version_catchup)
        val playingLabel = stringResource(R.string.mobile_channel_versions_current)
        OptionsSheet(
            title = stringResource(R.string.mobile_other_versions),
            options = state.versions.map { item ->
                val supporting = listOfNotNull(
                    item.version.quality.badge(),
                    if (item.version.hasArchive) catchupLabel else null,
                    if (item.isCurrent) playingLabel else null,
                ).joinToString("  ·  ").ifEmpty { null }
                SheetOption(item.version.id, item.version.label, supporting, selected = item.isCurrent)
            },
            onPick = viewModel::switchTo,
            onDismiss = viewModel::closeVersions,
            emptyText = stringResource(R.string.mobile_no_other_versions),
        )
    }
    if (tracksOpen) {
        TracksSheet(
            audio = tracks.audio,
            subtitles = tracks.subtitles,
            onAudio = viewModel::selectAudio,
            onSubtitle = viewModel::selectSubtitle,
            onDismiss = {
                tracksOpen = false
                viewModel.showOverlay()
            },
        )
    }
}

@Composable
private fun ChannelOverlay(
    state: LivePlayerUiState,
    hasTracks: Boolean,
    onClose: () -> Unit,
    onVersions: () -> Unit,
    onTracks: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRestart: () -> Unit,
    onLastChannel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.testTag(MobileChannelPlayerTags.CONTROLS)) {
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Scrim).safeDrawingPadding().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton("←", stringResource(R.string.mobile_close_player), onClose)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = listOfNotNull(state.number?.toString(), state.channel?.name).joinToString("  "),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    state.qualityBadge?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.background(Color(0x60FFFFFF), MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                val versionLabel = state.currentVersionLabel
                if (versionLabel != null && versionLabel != state.channel?.name) {
                    Text(versionLabel, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.hasOtherVersions) PlayerIconButton("⧉", stringResource(R.string.mobile_other_versions), onVersions)
            if (hasTracks) PlayerIconButton("💬", stringResource(R.string.vod_tracks), onTracks)
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Scrim).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val nowNext = state.nowNext
            val now = nowNext?.now
            if (nowNext != null) {
                Text(
                    text = now?.let { stringResource(R.string.player_now, it.title ?: stringResource(R.string.player_untitled)) }
                        ?: stringResource(R.string.player_no_guide),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                nowNext.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                nowNext.next?.let {
                    Text(
                        stringResource(R.string.player_next, it.title ?: stringResource(R.string.player_untitled)),
                        color = Color(0xFFCCCCCC),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerIconButton("⏮", stringResource(R.string.mobile_previous_channel), onPrevious)
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    if (state.canRestart) {
                        OutlinedButton(onClick = onRestart) { Text("↺  " + stringResource(R.string.player_restart_programme), color = Color.White) }
                    }
                    if (state.hasLastChannel) {
                        OutlinedButton(onClick = onLastChannel, modifier = Modifier.testTag(MobileChannelPlayerTags.LAST_CHANNEL)) {
                            Text(stringResource(R.string.player_last_channel), color = Color.White)
                        }
                    }
                }
                PlayerIconButton("⏭", stringResource(R.string.mobile_next_channel), onNext)
            }
        }
    }
}

private const val TICK_MS = 30_000L
