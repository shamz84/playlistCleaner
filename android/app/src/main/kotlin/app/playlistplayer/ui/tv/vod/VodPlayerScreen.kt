package app.playlistplayer.ui.tv.vod

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import app.playlistplayer.R
import app.playlistplayer.core.player.ConfiguredPlayer
import app.playlistplayer.core.player.ExoPlaybackController
import app.playlistplayer.core.player.PlaybackState
import app.playlistplayer.feature.vod.OverlayCommand
import app.playlistplayer.feature.vod.OverlayEvent
import app.playlistplayer.feature.vod.PlaybackProgressRepository
import app.playlistplayer.feature.vod.PlaybackRequest
import app.playlistplayer.feature.vod.SeekDirection
import app.playlistplayer.feature.vod.TrackOption
import app.playlistplayer.feature.vod.VodOverlayState
import app.playlistplayer.feature.vod.VodPlayerOverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.playlistplayer.ui.common.formatClock
import app.playlistplayer.ui.common.Media3Tracks

/**
 * VOD playback with the [VodPlayerOverlayState] overlay, driving [controller]. [player] is the
 * controller's [ConfiguredPlayer], used for the video surface, seeking and track selection.
 *
 * @param onNextEpisode builds the request for the next episode (null at the end of a series).
 * @param saveScope outlives this screen, so the final progress save on exit completes.
 */
@Composable
fun VodPlayerScreen(
    controller: ExoPlaybackController,
    player: ConfiguredPlayer,
    request: PlaybackRequest,
    progress: PlaybackProgressRepository,
    saveScope: CoroutineScope,
    onNextEpisode: suspend () -> PlaybackRequest?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val exo = player.exoPlayer
    val scope = rememberCoroutineScope()
    val overlay = remember { VodPlayerOverlayState() }
    var overlayState by remember { mutableStateOf(VodOverlayState()) }
    var current by remember { mutableStateOf(request) }
    val nextEpisode by rememberUpdatedState(onNextEpisode)
    val exit by rememberUpdatedState(onExit)
    val tracker = remember(current) {
        progress.tracker(current.progressKey, current.playlistId, current.kind, current.label, current.sources.first().id)
    }
    val playbackState by controller.state.collectAsStateWithLifecycle()
    val uiState by controller.uiState.collectAsStateWithLifecycle()
    val rootFocus = remember { FocusRequester() }

    fun durationOf(): Long = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    // Effects and listeners outlive a composition: they must reach the current episode's tracker,
    // not the first composition's (the final save on exit went to the first episode).
    val latestTracker by rememberUpdatedState(tracker)

    fun saveNow() {
        val position = exo.currentPosition
        val duration = durationOf()
        val t = latestTracker
        saveScope.launch { t.onStop(position, duration) }
    }

    fun apply(command: OverlayCommand) {
        when (command) {
            is OverlayCommand.Start -> controller.play(current.sources, command.positionMs.takeIf { it > 0 })
            OverlayCommand.Pause -> controller.pause()
            OverlayCommand.Resume -> controller.resume()
            is OverlayCommand.SeekTo -> exo.seekTo(command.positionMs)
            is OverlayCommand.ApplyTracks -> Media3Tracks.apply(exo, command.audioId, command.subtitleId)
            OverlayCommand.PlayNext -> scope.launch {
                saveNow()
                val next = nextEpisode()
                if (next == null) exit() else current = next
            }
        }
    }
    val dispatch: (OverlayEvent) -> Unit = { event ->
        val commands = overlay.handle(event)
        overlayState = overlay.state
        commands.forEach(::apply)
    }

    LaunchedEffect(current) {
        dispatch(OverlayEvent.Open(current.resumeOfferMs, current.startPositionMs ?: 0L, current.nextEpisodeLabel))
        rootFocus.requestFocus()
    }

    // Position polling: overlay progress, countdown and the 10 s progress save.
    LaunchedEffect(current) {
        while (isActive) {
            delay(POLL_MS)
            val duration = durationOf()
            val position = exo.currentPosition
            dispatch(OverlayEvent.Progress(position, duration, exo.isPlaying, SystemClock.elapsedRealtime()))
            if (exo.isPlaying) tracker.onPosition(position, duration)
        }
    }

    LaunchedEffect(playbackState) {
        val s = playbackState
        s.source?.id?.let { latestTracker.versionId = it }
        if (s is PlaybackState.Ended) dispatch(OverlayEvent.Ended)
    }

    val latestDispatch by rememberUpdatedState(dispatch)
    val latestSave by rememberUpdatedState<() -> Unit> { saveNow() }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                latestDispatch(OverlayEvent.TracksChanged(Media3Tracks.options(tracks)))
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            latestSave()
            controller.stop()
        }
    }

    BackHandler {
        if (overlayState.trackPanelOpen) dispatch(OverlayEvent.ToggleTrackPanel) else exit()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val s = overlayState
                // Prompt, next-episode card and track panel own the D-pad while visible.
                if (s.resumePrompt != null || s.nextEpisode != null || s.trackPanelOpen || uiState.error != null) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND,
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val direction = if (native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT || native.keyCode == AndroidKeyEvent.KEYCODE_MEDIA_REWIND) {
                            SeekDirection.BACK
                        } else {
                            SeekDirection.FORWARD
                        }
                        if (event.type == KeyEventType.KeyDown) dispatch(OverlayEvent.SeekKey(direction, native.repeatCount))
                        if (event.type == KeyEventType.KeyUp) dispatch(OverlayEvent.SeekKeyUp)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        if (event.type == KeyEventType.KeyUp) dispatch(OverlayEvent.PlayPause)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_MENU -> {
                        if (event.type == KeyEventType.KeyUp && s.tracks.hasChoices) dispatch(OverlayEvent.ToggleTrackPanel)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.type == KeyEventType.KeyUp) dispatch(if (s.controlsVisible) OverlayEvent.HideControls else OverlayEvent.ShowControls)
                        true
                    }
                    else -> false
                }
            },
    ) {
        VideoSurface(exo, Modifier.fillMaxSize())

        uiState.indicator?.let { indicator ->
            Text(text = indicator.text, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp))
        }

        val s = overlayState
        if (s.controlsVisible || s.seek != null || !s.isPlaying) {
            ControlsBar(current.label, s, Modifier.align(Alignment.BottomCenter))
        }

        s.resumePrompt?.let { prompt ->
            ResumePromptCard(
                positionMs = prompt.positionMs,
                onResume = { dispatch(OverlayEvent.ChooseResume); rootFocus.requestFocus() },
                onStartOver = { dispatch(OverlayEvent.ChooseStartOver); rootFocus.requestFocus() },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        s.nextEpisode?.let { card ->
            NextEpisodeCardView(
                label = card.label,
                secondsRemaining = card.secondsRemaining,
                onPlayNow = { dispatch(OverlayEvent.PlayNextNow) },
                onCancel = { dispatch(OverlayEvent.CancelNext); rootFocus.requestFocus() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(48.dp),
            )
        }

        if (s.trackPanelOpen) {
            TrackPanel(
                audio = s.tracks.audio,
                subtitles = s.tracks.subtitles,
                onAudio = { dispatch(OverlayEvent.SelectAudio(it)) },
                onSubtitle = { dispatch(OverlayEvent.SelectSubtitle(it)) },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        uiState.error?.let { error ->
            Column(
                modifier = Modifier.align(Alignment.Center).background(Color(0xE0000000)).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = error.title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(text = error.message, color = Color.White)
                if (error.canRetry) Button(onClick = controller::retry) { Text(stringResource(R.string.vod_retry_playback)) }
            }
        }
    }
}

@Composable
private fun VideoSurface(exoPlayer: ExoPlayer, modifier: Modifier) {
    // SurfaceView (not TextureView): correct HDR output and frame timing, as in the U6 harness.
    val surface = remember { mutableStateOf<SurfaceView?>(null) }
    AndroidView(factory = { context -> SurfaceView(context).also { surface.value = it } }, modifier = modifier)
    val view = surface.value
    DisposableEffect(exoPlayer, view) {
        if (view != null) exoPlayer.setVideoSurfaceView(view)
        onDispose { if (view != null) exoPlayer.clearVideoSurfaceView(view) }
    }
}

@Composable
private fun ControlsBar(label: String, s: VodOverlayState, modifier: Modifier) {
    val shown = s.seek?.targetMs ?: s.positionMs
    Column(
        modifier = modifier.fillMaxWidth().background(Color(0xB0000000)).padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!s.isPlaying && s.started) Text(text = stringResource(R.string.vod_paused), color = Color.White)
            Text(text = formatClock(shown), color = Color.White)
            ProgressLine(fraction = if (s.durationMs > 0) shown.toFloat() / s.durationMs else 0f, modifier = Modifier.weight(1f))
            Text(text = formatClock(s.durationMs), color = Color.White)
            s.seek?.let { Text(text = (if (it.stepMs > 0) "+" else "−") + "${kotlin.math.abs(it.stepMs) / 1000}s", color = Color.White) }
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float, modifier: Modifier) {
    Box(modifier = modifier.height(4.dp).background(Color(0x60FFFFFF))) {
        Box(modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(Color.White))
    }
}

@Composable
private fun ResumePromptCard(positionMs: Long, onResume: () -> Unit, onStartOver: () -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    Column(
        modifier = modifier.background(Color(0xE0101010)).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.vod_resume_prompt_title, formatClock(positionMs)), style = MaterialTheme.typography.titleLarge, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onResume, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.vod_resume)) }
            OutlinedButton(onClick = onStartOver) { Text(stringResource(R.string.vod_start_over)) }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun NextEpisodeCardView(label: String, secondsRemaining: Int, onPlayNow: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    Column(
        modifier = modifier.width(420.dp).background(Color(0xE0101010)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.vod_next_episode, label), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2)
        Text(text = stringResource(R.string.vod_next_episode_in, secondsRemaining), color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlayNow, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.vod_play_now)) }
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.vod_cancel)) }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun TrackPanel(
    audio: List<TrackOption>,
    subtitles: List<TrackOption>,
    onAudio: (String) -> Unit,
    onSubtitle: (String?) -> Unit,
    modifier: Modifier,
) {
    val focus = remember { FocusRequester() }
    LazyColumn(
        modifier = modifier.width(360.dp).fillMaxSize().background(Color(0xE0101010)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text(text = stringResource(R.string.vod_audio), style = MaterialTheme.typography.titleMedium, color = Color.White) }
        items(audio, key = { "a:${it.id}" }) { track ->
            val first = track === audio.firstOrNull()
            ListItem(
                selected = track.selected,
                onClick = { onAudio(track.id) },
                modifier = if (first) Modifier.focusRequester(focus) else Modifier,
                headlineContent = { Text(track.label) },
            )
        }
        item { Text(text = stringResource(R.string.vod_subtitles), style = MaterialTheme.typography.titleMedium, color = Color.White) }
        item {
            ListItem(
                selected = subtitles.none { it.selected },
                onClick = { onSubtitle(null) },
                modifier = if (audio.isEmpty()) Modifier.focusRequester(focus) else Modifier,
                headlineContent = { Text(stringResource(R.string.vod_subtitles_off)) },
            )
        }
        items(subtitles, key = { "t:${it.id}" }) { track ->
            ListItem(selected = track.selected, onClick = { onSubtitle(track.id) }, headlineContent = { Text(track.label) })
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

private const val POLL_MS = 500L
