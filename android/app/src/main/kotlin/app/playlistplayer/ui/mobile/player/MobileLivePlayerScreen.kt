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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import app.playlistplayer.R
import app.playlistplayer.core.player.ConfiguredPlayer
import app.playlistplayer.core.player.ExoPlaybackController
import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.feature.vod.TrackSelectionModel
import app.playlistplayer.ui.common.live.ChannelNavigator
import app.playlistplayer.ui.common.live.ChannelRow
import app.playlistplayer.ui.mobile.MobileLoading
import app.playlistplayer.ui.mobile.vod.OptionsSheet
import app.playlistplayer.ui.mobile.vod.SheetOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import app.playlistplayer.ui.common.badge
import app.playlistplayer.ui.common.Media3Tracks

object MobileLivePlayerTags {
    const val ROOT = "mobile.livePlayer"
    const val CONTROLS = "mobile.livePlayer.controls"
}

/**
 * Fullscreen landscape live player for phones.
 *
 * - [liveSources] resolves a channel to its playable versions (the channel first, then its
 *   alternatives in [AlternativeFinder][app.playlistplayer.core.consolidation.AlternativeFinder]
 *   order); the controller fails over across them (U6).
 * - Tap toggles the controls (no double tap on live, so taps respond immediately).
 * - "Other versions" sheet: pick a version to play now; the rest stay as failover candidates.
 * - Previous / next channel stay within the group the channel was opened from (R26).
 * - Audio / subtitle sheet.
 *
 * [index] changes are reported through [onChannelChanged] so the host can keep its back stack entry
 * current; the stream is started only when the channel changes, never on recomposition or rotation.
 */
@Composable
fun MobileLivePlayerScreen(
    controller: ExoPlaybackController,
    player: ConfiguredPlayer,
    channels: List<ChannelRow>,
    index: Int,
    liveSources: suspend (ChannelRow) -> List<PlayableSource>,
    onChannelChanged: (Int) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FullscreenLandscapeEffect()
    val exo = player.exoPlayer
    val row = channels.getOrNull(index)
    val playbackState by controller.state.collectAsStateWithLifecycle()
    val uiState by controller.uiState.collectAsStateWithLifecycle()
    val resolve by rememberUpdatedState(liveSources)
    val exit by rememberUpdatedState(onExit)

    var sources by remember { mutableStateOf<List<PlayableSource>?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    var versionsOpen by remember { mutableStateOf(false) }
    var tracksOpen by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(TrackSelectionModel()) }
    KeepScreenOn(!unavailable && ScreenWakePolicy.isActive(playbackState))

    LaunchedEffect(row?.playlistId, row?.ordinal) {
        sources = null
        unavailable = false
        if (row == null || !row.isPlayable) {
            unavailable = true
            return@LaunchedEffect
        }
        val resolved = try {
            resolve(row)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        if (resolved.isEmpty()) {
            unavailable = true
        } else {
            sources = resolved
            controller.play(resolved)
        }
    }

    LaunchedEffect(controlsVisible, interactions, versionsOpen, tracksOpen) {
        if (controlsVisible && !versionsOpen && !tracksOpen) {
            delay(PlayerGestures.CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) {
                tracks = tracks.withTracks(Media3Tracks.options(t))
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            controller.stop()
        }
    }

    BackHandler { exit() }

    val navigator = remember(channels) { ChannelNavigator.forRows(channels) }
    val previous = navigator.channelDown(index)
    val next = navigator.channelUp(index)

    Box(modifier = modifier.fillMaxSize().background(Color.Black).testTag(MobileLivePlayerTags.ROOT)) {
        VideoSurface(exo, Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        controlsVisible = !controlsVisible
                        interactions++
                    })
                },
        )

        PlayerStatus(uiState, modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 56.dp))

        when {
            unavailable -> Text(
                text = stringResource(R.string.mobile_live_unavailable),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center).padding(24.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            )
            sources == null -> MobileLoading()
        }

        if (controlsVisible) {
            Box(modifier = Modifier.fillMaxSize().testTag(MobileLivePlayerTags.CONTROLS)) {
                Row(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Scrim).safeDrawingPadding().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerIconButton("←", stringResource(R.string.mobile_close_player), { exit() })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row?.let { r -> listOfNotNull(r.number?.toString(), r.name).joinToString("  ") }.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        playbackState.source?.label?.let {
                            Text(it, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if ((sources?.size ?: 0) > 1) {
                        PlayerIconButton("⧉", stringResource(R.string.mobile_other_versions), { versionsOpen = true })
                    }
                    if (tracks.hasChoices) PlayerIconButton("💬", stringResource(R.string.vod_tracks), { tracksOpen = true })
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Scrim).safeDrawingPadding().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (previous != null) {
                        PlayerIconButton("⏮", stringResource(R.string.mobile_previous_channel), { onChannelChanged(previous); interactions++ })
                    } else {
                        Box(Modifier)
                    }
                    if (next != null) {
                        PlayerIconButton("⏭", stringResource(R.string.mobile_next_channel), { onChannelChanged(next); interactions++ })
                    }
                }
            }
        }

        uiState.error?.let { error -> PlayerErrorPanel(error, onRetry = controller::retry, modifier = Modifier.align(Alignment.Center)) }
    }

    if (versionsOpen) {
        val all = sources.orEmpty()
        val playingId = playbackState.source?.id ?: all.firstOrNull()?.id
        OptionsSheet(
            title = stringResource(R.string.mobile_other_versions),
            options = all.map { SheetOption(it.id, it.label, it.versionTier?.badge(), selected = it.id == playingId) },
            onPick = { id ->
                if (id != playingId) {
                    val reordered = withPickedFirst(all, id)
                    sources = reordered
                    controller.play(reordered)
                }
            },
            onDismiss = { versionsOpen = false },
            emptyText = stringResource(R.string.mobile_no_other_versions),
        )
    }
    if (tracksOpen) {
        TracksSheet(
            audio = tracks.audio,
            subtitles = tracks.subtitles,
            onAudio = { id ->
                tracks = tracks.selectAudio(id)
                Media3Tracks.apply(exo, tracks.selectedAudioId, tracks.selectedSubtitleId)
            },
            onSubtitle = { id ->
                tracks = tracks.selectSubtitle(id)
                Media3Tracks.apply(exo, tracks.selectedAudioId, tracks.selectedSubtitleId)
            },
            onDismiss = { tracksOpen = false },
        )
    }
}
