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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.core.player.ConfiguredPlayer
import app.playlistplayer.feature.live.CatchupError
import app.playlistplayer.feature.live.CatchupPlayerUiState
import app.playlistplayer.feature.live.CatchupPlayerViewModel
import app.playlistplayer.feature.vod.SeekDirection
import app.playlistplayer.ui.common.UiTokens
import app.playlistplayer.ui.tv.components.TvBadge
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import app.playlistplayer.ui.common.formatClock
import app.playlistplayer.ui.common.rememberTimeFormatter

object CatchupPlayerTags {
    const val ROOT = "catchupPlayer.root"
    const val CONTROLS = "catchupPlayer.controls"
}

/**
 * Catch-up playback (U8) with a programme-relative seek bar (`0..duration`). Left/right (and
 * rewind/fast-forward) seek with the VOD D-pad acceleration, committed on key up; OK / play-pause
 * toggles pause; Down toggles the controls; Back leaves.
 */
@Composable
fun CatchupPlayerScreen(
    viewModel: CatchupPlayerViewModel,
    player: ConfiguredPlayer,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rootFocus = remember { FocusRequester() }

    // Starts after the previous player screen's dispose (which stops the shared driver).
    LaunchedEffect(viewModel) { viewModel.start() }
    LaunchedEffect(viewModel) {
        while (isActive) {
            delay(POLL_MS)
            viewModel.tick()
        }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.stop() } }
    val blocking = state.error != null || state.status.error != null
    KeepScreenOn(ScreenWakePolicy.isActive(state.status, starting = state.loading, paused = state.isPaused, ended = state.ended, blocked = state.error != null))
    LaunchedEffect(blocking) { if (!blocking) runCatching { rootFocus.requestFocus() } }

    BackHandler(onBack = onExit)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(CatchupPlayerTags.ROOT)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (blocking) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                val down = event.type == KeyEventType.KeyDown
                val up = event.type == KeyEventType.KeyUp
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND,
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val back = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT || native.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_REWIND
                        val direction = if (back) SeekDirection.BACK else SeekDirection.FORWARD
                        if (down) viewModel.onSeekKey(direction, native.repeatCount)
                        if (up) viewModel.onSeekKeyUp()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (up) viewModel.playPause()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_INFO -> {
                        if (up) viewModel.toggleControls()
                        true
                    }
                    else -> false
                }
            },
    ) {
        PlayerVideoSurface(player.exoPlayer, Modifier.fillMaxSize())

        StatusBanner(state.status, Modifier.align(Alignment.TopCenter).padding(top = 32.dp))

        if ((state.controlsVisible || state.seek != null || state.isPaused || state.ended) && !blocking) {
            CatchupControls(state, Modifier.align(Alignment.BottomCenter))
        }

        state.error?.let { error ->
            MessagePanel(
                title = state.title,
                message = stringResource(
                    when (error) {
                        CatchupError.NOT_ELIGIBLE -> R.string.catchup_not_eligible
                        CatchupError.NO_ACCOUNT -> R.string.catchup_no_account
                    },
                ),
                actionLabel = stringResource(R.string.catchup_back),
                onAction = onExit,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (state.error == null) {
            state.status.error?.let { PlayerErrorPanel(it, onRetry = viewModel::retry, modifier = Modifier.align(Alignment.Center)) }
        }
    }
}

@Composable
private fun CatchupControls(state: CatchupPlayerUiState, modifier: Modifier) {
    val time = rememberTimeFormatter()
    val shown = state.seek?.targetMs ?: state.positionMs
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xC0000000))
            .padding(horizontal = UiTokens.OverscanHorizontal, vertical = UiTokens.SpaceL)
            .testTag(CatchupPlayerTags.CONTROLS),
        verticalArrangement = Arrangement.spacedBy(UiTokens.SpaceS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
            TvBadge(stringResource(if (state.isRestart) R.string.catchup_restart_badge else R.string.catchup_badge))
            Text(
                text = state.title ?: stringResource(R.string.player_untitled),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM)) {
            state.channelName?.let { Text(text = it, color = UiTokens.OnSurfaceMuted) }
            if (state.programmeStopMillis > state.programmeStartMillis) {
                Text(text = "${time(state.programmeStartMillis)} – ${time(state.programmeStopMillis)}", color = UiTokens.OnSurfaceMuted)
            }
            if (state.usesOtherVersion) state.versionLabel?.let { Text(text = stringResource(R.string.catchup_via_version, it), color = UiTokens.OnSurfaceMuted) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.ended -> Text(text = stringResource(R.string.catchup_ended), color = Color.White)
                state.isPaused -> Text(text = stringResource(R.string.catchup_paused), color = Color.White)
            }
            Text(text = formatClock(shown), color = Color.White)
            val duration = state.durationMs.coerceAtLeast(1)
            ProgressBar(
                fraction = shown.toFloat() / duration,
                available = state.availableMs.toFloat() / duration,
                modifier = Modifier.weight(1f),
            )
            Text(text = formatClock(state.durationMs), color = Color.White)
            state.seek?.let {
                Text(text = (if (it.stepMs > 0) "+" else "−") + "${kotlin.math.abs(it.stepMs) / 1000}s", color = Color.White)
            }
        }
    }
}

private const val POLL_MS = 500L
