package app.playlistplayer.ui.mobile.player

import app.playlistplayer.ui.common.ScreenWakePolicy
import app.playlistplayer.ui.common.KeepScreenOn
import android.os.SystemClock
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import app.playlistplayer.R
import app.playlistplayer.core.player.ConfiguredPlayer
import app.playlistplayer.core.player.ExoPlaybackController
import app.playlistplayer.core.player.PlaybackState
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.vod.SkipKind
import app.playlistplayer.ui.common.LocalVideoScaling
import app.playlistplayer.ui.common.VideoScalingLabels
import app.playlistplayer.feature.vod.OverlayCommand
import app.playlistplayer.feature.vod.OverlayEvent
import app.playlistplayer.feature.vod.PlaybackProgressRepository
import app.playlistplayer.feature.vod.PlaybackRequest
import app.playlistplayer.feature.vod.SkipSpanProvider
import app.playlistplayer.feature.vod.InMemoryTrackPreferenceStore
import app.playlistplayer.feature.vod.SeekDirection
import app.playlistplayer.feature.vod.TrackPreferenceStore
import app.playlistplayer.feature.vod.currentOrDefault
import app.playlistplayer.feature.vod.saveQuietly
import app.playlistplayer.feature.vod.VodOverlayState
import app.playlistplayer.feature.vod.VodPlayerOverlayState
import app.playlistplayer.ui.mobile.vod.OptionsSheet
import app.playlistplayer.ui.mobile.vod.SheetOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import app.playlistplayer.ui.common.badge
import app.playlistplayer.ui.common.formatClock
import app.playlistplayer.ui.common.Media3Tracks

object MobileVodPlayerTags {
    const val ROOT = "mobile.vodPlayer"
    const val CONTROLS = "mobile.vodPlayer.controls"
}

/**
 * Fullscreen landscape VOD player for phones. Playback logic is the shared [VodPlayerOverlayState]
 * (Resume / Start over, play/pause, seek steps, next-episode countdown, track selection) and
 * [PlayerUiState][app.playlistplayer.core.player.PlayerUiState] (indicator, notice, error panel);
 * this screen only maps touch onto overlay events:
 * - tap toggles the controls; double tap left / right seeks −10 s / +30 s (the overlay's D-pad
 *   steps); double tap in the middle plays / pauses; horizontal drag scrubs;
 * - "Versions" sheet restarts at the current position on another version (others stay as failover)
 *   and reports the choice through [onVersionPicked] (persisted as the title's pick);
 * - audio / subtitle sheet.
 *
 * [controller] and [player] are long-lived (owned by the app container), so rotation does not
 * restart the stream; see [FullscreenLandscapeEffect] for the activity requirement.
 */
@Composable
fun MobileVodPlayerScreen(
    controller: ExoPlaybackController,
    player: ConfiguredPlayer,
    request: PlaybackRequest,
    progress: PlaybackProgressRepository,
    saveScope: CoroutineScope,
    onNextEpisode: (suspend () -> PlaybackRequest?)?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onVersionPicked: (versionId: String) -> Unit = {},
    trackPreferences: TrackPreferenceStore = InMemoryTrackPreferenceStore(),
    skipSpans: SkipSpanProvider? = null,
) {
    FullscreenLandscapeEffect()
    val videoScaling = LocalVideoScaling.current
    val exo = player.exoPlayer
    val scope = rememberCoroutineScope()
    val overlay = remember { VodPlayerOverlayState() }
    /** Live playback's track parameters, put back when this screen closes (shared player). */
    val trackSnapshot = remember(exo) { Media3Tracks.snapshot(exo) }
    var overlayState by remember { mutableStateOf(VodOverlayState()) }
    var current by remember { mutableStateOf(request) }
    val nextEpisode by rememberUpdatedState(onNextEpisode)
    val exit by rememberUpdatedState(onExit)
    val tracker = remember(current) {
        progress.tracker(current.progressKey, current.playlistId, current.kind, current.label, current.sources.first().id)
    }
    val playbackState by controller.state.collectAsStateWithLifecycle()
    /** `playWhenReady`: false while the user has paused (the controller's state stays Playing). */
    var playRequested by remember { mutableStateOf(exo.playWhenReady) }
    KeepScreenOn(ScreenWakePolicy.isActive(playbackState, paused = !playRequested))
    val uiState by controller.uiState.collectAsStateWithLifecycle()
    var versionsOpen by remember { mutableStateOf(false) }
    /** Scrub target while a horizontal drag or the slider is held; committed on release. */
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    /** Feedback label of the last double-tap seek ("−10 s" / "+30 s"). */
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    /** Bumped on every interaction; restarts the auto-hide timer. */
    var interactions by remember { mutableIntStateOf(0) }

    fun durationOf(): Long = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    // Effects and gesture detectors outlive a composition; they must reach the current episode's tracker.
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
            is OverlayCommand.SaveTrackPreference -> {
                Media3Tracks.preferLanguages(exo, command.preference)
                saveScope.launch { trackPreferences.saveQuietly(command.preference) }
            }
            OverlayCommand.PlayNext -> scope.launch {
                saveNow()
                val next = nextEpisode?.invoke()
                if (next == null) exit() else current = next
            }
        }
    }
    val dispatch: (OverlayEvent) -> Unit = { event ->
        val commands = overlay.handle(event)
        overlayState = overlay.state
        commands.forEach(::apply)
    }

    fun onGesture(command: GestureCommand) {
        interactions++
        when (command) {
            GestureCommand.ToggleControls -> dispatch(if (overlayState.controlsVisible) OverlayEvent.HideControls else OverlayEvent.ShowControls)
            GestureCommand.PlayPause -> dispatch(OverlayEvent.PlayPause)
            is GestureCommand.Seek -> {
                // One D-pad press worth of seek: −10 s / +30 s, committed immediately.
                dispatch(OverlayEvent.SeekKey(command.direction, repeatCount = 0))
                dispatch(OverlayEvent.SeekKeyUp)
                val step = PlayerGestures.stepMs(command.direction)
                seekFeedback = (if (step > 0) "+" else "−") + "${abs(step) / 1000} s"
            }
            GestureCommand.None -> Unit
        }
    }

    fun commitScrub() {
        val target = scrubMs ?: return
        scrubMs = null
        dispatch(OverlayEvent.SeekTo(target))
    }
    val gesture by rememberUpdatedState<(GestureCommand) -> Unit> { onGesture(it) }

    LaunchedEffect(current) {
        // The next episode keeps this session's preference (including a choice made in the sheet).
        val preference = overlay.state.tracks.preference ?: trackPreferences.currentOrDefault()
        Media3Tracks.startWithPreference(exo, preference)
        dispatch(OverlayEvent.Open(current.resumeOfferMs, current.startPositionMs ?: 0L, current.nextEpisodeLabel, preference, isEpisode = current.kind == VodKind.SERIES))
        skipSpans?.let { provider ->
            val spans = provider.spans(current)
            if (spans.isNotEmpty()) dispatch(OverlayEvent.Markers(spans))
        }
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

    // Auto-hide the controls while playing.
    LaunchedEffect(overlayState.controlsVisible, overlayState.isPlaying, interactions, versionsOpen, overlayState.trackPanelOpen) {
        if (overlayState.controlsVisible && overlayState.isPlaying && !versionsOpen && !overlayState.trackPanelOpen && scrubMs == null) {
            delay(PlayerGestures.CONTROLS_TIMEOUT_MS)
            dispatch(OverlayEvent.HideControls)
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(SEEK_FEEDBACK_MS)
            seekFeedback = null
        }
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playRequested = playWhenReady
            }

            override fun onTracksChanged(tracks: Tracks) {
                dispatch(OverlayEvent.TracksChanged(Media3Tracks.options(tracks)))
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            saveNow()
            controller.stop()
            Media3Tracks.restore(exo, trackSnapshot)
        }
    }

    BackHandler { exit() }

    val s = overlayState
    val isPlayingNow = s.isPlaying
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(MobileVodPlayerTags.ROOT),
    ) {
        VideoSurface(exo, Modifier.fillMaxSize(), videoScaling.scaling)

        // Gesture layer over the video (below the controls, so buttons receive their own taps).
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
                            if (!overlay.state.started) return@detectHorizontalDragGestures
                            start = exo.currentPosition
                            dragged = 0f
                            scrubMs = start
                            dispatch(OverlayEvent.ShowControls)
                        },
                        onDragEnd = { commitScrub() },
                        onDragCancel = { scrubMs = null },
                        onHorizontalDrag = { change, amount ->
                            if (scrubMs == null) return@detectHorizontalDragGestures
                            change.consume()
                            dragged += amount
                            scrubMs = PlayerGestures.dragTargetMs(start, dragged, size.width.toFloat(), durationOf())
                        },
                    )
                },
        )

        app.playlistplayer.ui.common.VodDiagnosticsOverlay(
            controller,
            Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp),
        )

        Column(modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PlayerStatus(uiState)
            seekFeedback?.let { Text(it, color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp)) }
        }

        if (s.controlsVisible || !isPlayingNow || scrubMs != null) {
            Controls(
                title = current.label,
                state = s,
                scrubMs = scrubMs,
                hasVersions = current.sources.size > 1,
                onClose = { exit() },
                onPlayPause = { onGesture(GestureCommand.PlayPause) },
                onSeekBack = { onGesture(GestureCommand.Seek(SeekDirection.BACK)) },
                onSeekForward = { onGesture(GestureCommand.Seek(SeekDirection.FORWARD)) },
                onScrub = { scrubMs = it; interactions++ },
                onScrubDone = ::commitScrub,
                onTracks = { dispatch(OverlayEvent.ToggleTrackPanel) },
                onVersions = { versionsOpen = true },
                onPicture = { videoScaling.cycle(); interactions++ },
                modifier = Modifier.fillMaxSize(),
            )
        }

        s.skip?.let { prompt ->
            Button(
                onClick = { dispatch(OverlayEvent.SkipNow) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(end = 24.dp, bottom = if (s.controlsVisible || !isPlayingNow) 72.dp else 24.dp)
                    .sizeIn(minHeight = 48.dp),
            ) { Text(
                stringResource(
                    when (prompt.kind) {
                        SkipKind.INTRO -> R.string.vod_skip_intro
                        SkipKind.RECAP -> R.string.vod_skip_recap
                        SkipKind.CREDITS -> R.string.vod_skip_credits
                    },
                ),
            ) }
        }

        s.resumePrompt?.let { prompt ->
            Column(
                modifier = Modifier.align(Alignment.Center).background(Color(0xE0101010), MaterialTheme.shapes.medium).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.vod_resume_prompt_title, formatClock(prompt.positionMs)), style = MaterialTheme.typography.titleLarge, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { dispatch(OverlayEvent.ChooseResume) }) { Text(stringResource(R.string.vod_resume)) }
                    OutlinedButton(onClick = { dispatch(OverlayEvent.ChooseStartOver) }) { Text(stringResource(R.string.vod_start_over)) }
                }
            }
        }

        s.nextEpisode?.let { card ->
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(24.dp)
                    .widthIn(max = 360.dp)
                    .background(Color(0xE0101010), MaterialTheme.shapes.medium)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.vod_next_episode, card.label), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.vod_next_episode_in, card.secondsRemaining), color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { dispatch(OverlayEvent.PlayNextNow) }) { Text(stringResource(R.string.vod_play_now)) }
                    OutlinedButton(onClick = { dispatch(OverlayEvent.CancelNext) }) { Text(stringResource(R.string.vod_cancel)) }
                }
            }
        }

        uiState.error?.let { error -> PlayerErrorPanel(error, onRetry = controller::retry, modifier = Modifier.align(Alignment.Center)) }
    }

    if (s.trackPanelOpen) {
        TracksSheet(
            audio = s.tracks.audio,
            subtitles = s.tracks.subtitles,
            onAudio = { dispatch(OverlayEvent.SelectAudio(it)) },
            onSubtitle = { dispatch(OverlayEvent.SelectSubtitle(it)) },
            onDismiss = { if (overlay.state.trackPanelOpen) dispatch(OverlayEvent.ToggleTrackPanel) },
        )
    }
    if (versionsOpen) {
        val playingId = playbackState.source?.id ?: current.sources.first().id
        OptionsSheet(
            title = stringResource(R.string.vod_versions),
            options = current.sources.map { source ->
                SheetOption(source.id, source.label, source.versionTier?.badge(), selected = source.id == playingId)
            },
            onPick = { id ->
                if (id != playingId) {
                    // Restart on the chosen version at the current position; the others stay as failover.
                    controller.play(withPickedFirst(current.sources, id), exo.currentPosition.takeIf { it > 0 })
                    // The choice becomes the title's pick, as on the detail screen.
                    current.versionIds[id]?.let(onVersionPicked)
                }
            },
            onDismiss = { versionsOpen = false },
        )
    }
}

@Composable
private fun Controls(
    title: String,
    state: VodOverlayState,
    scrubMs: Long?,
    hasVersions: Boolean,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubDone: () -> Unit,
    onTracks: () -> Unit,
    onVersions: () -> Unit,
    onPicture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = scrubMs ?: state.seek?.targetMs ?: state.positionMs
    Box(modifier = modifier.testTag(MobileVodPlayerTags.CONTROLS)) {
        // Top bar: close, title, versions, tracks, picture format.
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Scrim).safeDrawingPadding().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton("←", stringResource(R.string.mobile_close_player), onClose)
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (hasVersions) PlayerIconButton("⧉", stringResource(R.string.vod_versions), onVersions)
            if (state.tracks.hasChoices) PlayerIconButton("💬", stringResource(R.string.vod_tracks), onTracks)
            PlayerIconButton("⛶", stringResource(R.string.vod_picture_format, stringResource(VideoScalingLabels.label(LocalVideoScaling.current.scaling))), onPicture)
        }
        // Centre: seek back, play / pause, seek forward (gesture-free equivalents for accessibility).
        if (state.started) {
            Row(modifier = Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerIconButton("⟲", stringResource(R.string.mobile_seek_back), onSeekBack)
                val playPause = stringResource(if (state.isPlaying) R.string.mobile_pause else R.string.mobile_play)
                PlayerIconButton(if (state.isPlaying) "⏸" else "▶", playPause, onPlayPause)
                PlayerIconButton("⟳", stringResource(R.string.mobile_seek_forward), onSeekForward)
            }
        }
        // Bottom: position, seek bar, duration.
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Scrim).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.isPlaying && state.started) Text(stringResource(R.string.vod_paused), color = Color.White)
            Text(formatClock(shown), color = Color.White)
            val duration = state.durationMs
            val positionLabel = stringResource(R.string.mobile_seek_position)
            val positionState = "${formatClock(shown)} / ${formatClock(duration)}"
            Slider(
                value = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { f -> if (duration > 0) onScrub((f * duration).toLong()) },
                onValueChangeFinished = onScrubDone,
                enabled = state.started && duration > 0,
                modifier = Modifier.weight(1f).semantics {
                    contentDescription = positionLabel
                    stateDescription = positionState
                },
            )
            Text(formatClock(duration), color = Color.White)
        }
    }
}

private const val POLL_MS = 500L
private const val SEEK_FEEDBACK_MS = 800L
