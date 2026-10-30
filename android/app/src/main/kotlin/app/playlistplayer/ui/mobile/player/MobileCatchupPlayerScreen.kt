package app.playlistplayer.ui.mobile.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.playlistplayer.R
import app.playlistplayer.core.player.ConfiguredPlayer
import app.playlistplayer.feature.live.CatchupError
import app.playlistplayer.feature.live.CatchupPlayerUiState
import app.playlistplayer.feature.live.CatchupPlayerViewModel
import app.playlistplayer.feature.vod.SeekDirection
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.mobile.MobileLoading
import app.playlistplayer.ui.mobile.vod.clockText
import app.playlistplayer.ui.tv.player.rememberTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

object MobileCatchupPlayerTags {
    const val ROOT = "mobile.catchupPlayer"
    const val CONTROLS = "mobile.catchupPlayer.controls"
}

/**
 * Fullscreen landscape catch-up player (U13 over the shared U8 [CatchupPlayerViewModel]) with the
 * programme-relative timeline (`0..duration`; a restarted airing programme can only seek within
 * what has aired):
 * - tap toggles the controls; double tap left / right seeks −10 s / +30 s (the TV D-pad step);
 *   double tap in the middle plays / pauses; horizontal drag scrubs and commits on release;
 * - seek bar, play / pause and step buttons (gesture-free equivalents);
 * - buffering / reconnecting / "Switched to" and the error panels via [PlaybackStatus][app.playlistplayer.feature.live.PlaybackStatus].
 *
 * Seeks become a native seek or a URL rebuild inside the ViewModel; this screen never sees URLs.
 */
@Composable
fun MobileCatchupPlayerScreen(
    viewModel: CatchupPlayerViewModel,
    player: ConfiguredPlayer,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FullscreenLandscapeEffect()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exit by rememberUpdatedState(onExit)
    /** Drag / slider target while held; committed on release. */
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        while (isActive) {
            delay(POLL_MS)
            viewModel.tick()
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(SEEK_FEEDBACK_MS)
            seekFeedback = null
        }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.stop() } }
    BackHandler { exit() }

    val started = !state.loading && state.error == null
    val blocking = state.error != null || state.status.error != null

    fun onGesture(command: GestureCommand) {
        when (command) {
            GestureCommand.ToggleControls -> viewModel.toggleControls()
            GestureCommand.PlayPause -> viewModel.playPause()
            is GestureCommand.Seek -> {
                viewModel.onSeekKey(command.direction, repeatCount = 0)
                viewModel.onSeekKeyUp()
                val step = PlayerGestures.stepMs(command.direction)
                seekFeedback = (if (step > 0) "+" else "−") + "${abs(step) / 1000} s"
            }
            GestureCommand.None -> Unit
        }
    }
    val gesture by rememberUpdatedState<(GestureCommand) -> Unit> { onGesture(it) }
    val latest by rememberUpdatedState(state)

    Box(modifier = modifier.fillMaxSize().background(Color.Black).testTag(MobileCatchupPlayerTags.ROOT)) {
        VideoSurface(player.exoPlayer, Modifier.fillMaxSize())

        // Gesture layer over the video, below the controls so buttons receive their own taps.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { gesture(PlayerGestures.onTap()) },
                        onDoubleTap = { offset -> gesture(PlayerGestures.onDoubleTap(offset.x, size.width.toFloat(), isLive = false)) },
                    )
                }
                .pointerInput(Unit) {
                    var start = 0L
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            val s = latest
                            if (s.loading || s.error != null) return@detectHorizontalDragGestures
                            start = s.positionMs
                            dragged = 0f
                            scrubMs = start
                            viewModel.showControls()
                        },
                        onDragEnd = {
                            scrubMs?.let(viewModel::seekTo)
                            scrubMs = null
                        },
                        onDragCancel = { scrubMs = null },
                        onHorizontalDrag = { change, amount ->
                            if (scrubMs == null) return@detectHorizontalDragGestures
                            change.consume()
                            dragged += amount
                            // Clamped to what can be played (all of it, or what has aired for a restart).
                            scrubMs = PlayerGestures.dragTargetMs(start, dragged, size.width.toFloat(), latest.availableMs)
                        },
                    )
                },
        )

        Column(
            modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlaybackStatusBanner(state.status)
            seekFeedback?.let { Text(it, color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp)) }
        }

        if (state.loading) MobileLoading()

        val showControls = state.controlsVisible || state.seek != null || state.isPaused || state.ended || scrubMs != null
        if (showControls && !blocking) {
            CatchupControls(
                state = state,
                started = started,
                scrubMs = scrubMs,
                onClose = { exit() },
                onPlayPause = { onGesture(GestureCommand.PlayPause) },
                onSeekBack = { onGesture(GestureCommand.Seek(SeekDirection.BACK)) },
                onSeekForward = { onGesture(GestureCommand.Seek(SeekDirection.FORWARD)) },
                onScrub = { ms ->
                    scrubMs = ms
                    viewModel.showControls()
                },
                onScrubDone = {
                    scrubMs?.let(viewModel::seekTo)
                    scrubMs = null
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (blocking) {
            // Keep a way out while a panel covers the controls.
            Row(modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp)) {
                PlayerIconButton("←", stringResource(R.string.mobile_close_player), { exit() })
            }
        }

        state.error?.let { error ->
            PlayerMessagePanel(
                title = state.title,
                message = stringResource(
                    when (error) {
                        CatchupError.NOT_ELIGIBLE -> R.string.catchup_not_eligible
                        CatchupError.NO_ACCOUNT -> R.string.catchup_no_account
                    },
                ),
                actionLabel = stringResource(R.string.catchup_back),
                onAction = { exit() },
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (state.error == null) {
            state.status.error?.let { PlaybackErrorPanelView(it, onRetry = viewModel::retry, modifier = Modifier.align(Alignment.Center)) }
        }
    }
}

@Composable
private fun CatchupControls(
    state: CatchupPlayerUiState,
    started: Boolean,
    scrubMs: Long?,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val time = rememberTimeFormatter()
    val shown = scrubMs ?: state.seek?.targetMs ?: state.positionMs
    Box(modifier = modifier.testTag(MobileCatchupPlayerTags.CONTROLS)) {
        // Top: close, badge + title, channel / times / version.
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Scrim).safeDrawingPadding().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton("←", stringResource(R.string.mobile_close_player), onClose)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(if (state.isRestart) R.string.catchup_restart_badge else R.string.catchup_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = UiTokens.OnAccent,
                        modifier = Modifier.background(UiTokens.Accent, MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Text(
                        text = state.title ?: stringResource(R.string.player_untitled),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val details = listOfNotNull(
                    state.channelName,
                    if (state.programmeStopMillis > state.programmeStartMillis) "${time(state.programmeStartMillis)} – ${time(state.programmeStopMillis)}" else null,
                    if (state.usesOtherVersion) state.versionLabel?.let { stringResource(R.string.catchup_via_version, it) } else null,
                ).joinToString("  ·  ")
                if (details.isNotEmpty()) {
                    Text(details, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Centre: step back, play / pause, step forward.
        if (started) {
            Row(modifier = Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerIconButton("⟲", stringResource(R.string.mobile_seek_back), onSeekBack)
                val paused = state.isPaused
                PlayerIconButton(if (paused) "▶" else "⏸", stringResource(if (paused) R.string.mobile_play else R.string.mobile_pause), onPlayPause)
                PlayerIconButton("⟳", stringResource(R.string.mobile_seek_forward), onSeekForward)
            }
        }
        // Bottom: state, position, programme seek bar, duration.
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Scrim).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.ended -> Text(stringResource(R.string.catchup_ended), color = Color.White)
                state.isPaused -> Text(stringResource(R.string.catchup_paused), color = Color.White)
            }
            Text(clockText(shown), color = Color.White)
            val duration = state.durationMs
            val available = state.availableMs.coerceIn(0, duration.coerceAtLeast(0))
            val label = stringResource(R.string.mobile_catchup_position)
            val positionState = "${clockText(shown)} / ${clockText(duration)}"
            Slider(
                value = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { f -> if (duration > 0) onScrub((f * duration).toLong().coerceIn(0, available)) },
                onValueChangeFinished = onScrubDone,
                enabled = started && duration > 0,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = label
                    stateDescription = positionState
                },
            )
            Text(clockText(duration), color = Color.White)
        }
    }
}

private const val POLL_MS = 500L
private const val SEEK_FEEDBACK_MS = 800L
