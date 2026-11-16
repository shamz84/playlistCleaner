package app.playlistplayer.ui.tv.vod

import app.playlistplayer.ui.common.ScreenWakePolicy
import app.playlistplayer.ui.common.KeepScreenOn
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.testTag
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
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.vod.SkipKind
import app.playlistplayer.ui.common.LocalVideoScaling
import app.playlistplayer.feature.settings.VideoScaling
import app.playlistplayer.ui.common.VideoScalingControl
import app.playlistplayer.ui.common.VideoScalingLabels
import app.playlistplayer.feature.vod.OverlayCommand
import app.playlistplayer.feature.vod.OverlayEvent
import app.playlistplayer.feature.vod.PlaybackProgressRepository
import app.playlistplayer.feature.vod.PlaybackRequest
import app.playlistplayer.feature.vod.SkipSpanProvider
import app.playlistplayer.feature.vod.SeekDirection
import app.playlistplayer.feature.vod.InMemoryTrackPreferenceStore
import app.playlistplayer.feature.vod.TrackOption
import app.playlistplayer.feature.vod.TrackPreferenceStore
import app.playlistplayer.feature.vod.currentOrDefault
import app.playlistplayer.feature.vod.saveQuietly
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
 * @param trackPreferences preferred audio/subtitle language: applied when each item starts; a
 *   choice in the track panel (Up / Menu) applies at once and becomes the new preference (R40).
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
    trackPreferences: TrackPreferenceStore = InMemoryTrackPreferenceStore(),
    skipSpans: SkipSpanProvider? = null,
) {
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
    val rootFocus = remember { FocusRequester() }
    var menuPage by remember { mutableStateOf<TrackMenuPage?>(null) }
    var menuFocus by remember { mutableStateOf(TrackMenuPage.PICTURE) }
    var lastInteraction by remember { mutableLongStateOf(0L) }

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
            is OverlayCommand.SaveTrackPreference -> {
                Media3Tracks.preferLanguages(exo, command.preference)
                saveScope.launch { trackPreferences.saveQuietly(command.preference) }
            }
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
        // The next episode keeps this session's preference (including a choice made in the panel).
        val preference = overlay.state.tracks.preference ?: trackPreferences.currentOrDefault()
        Media3Tracks.startWithPreference(exo, preference)
        dispatch(OverlayEvent.Open(current.resumeOfferMs, current.startPositionMs ?: 0L, current.nextEpisodeLabel, preference, isEpisode = current.kind == VodKind.SERIES))
        rootFocus.requestFocus()
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

    val latestDispatch by rememberUpdatedState(dispatch)
    val latestSave by rememberUpdatedState<() -> Unit> { saveNow() }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playRequested = playWhenReady
            }

            override fun onTracksChanged(tracks: Tracks) {
                latestDispatch(OverlayEvent.TracksChanged(Media3Tracks.options(tracks)))
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            latestSave()
            controller.stop()
            Media3Tracks.restore(exo, trackSnapshot)
        }
    }

    VodControlsAutoHide(
        enabled = (overlayState.controlsVisible || overlayState.trackPanelOpen) &&
            overlayState.seek == null && overlayState.resumePrompt == null &&
            overlayState.nextEpisode == null && uiState.error == null,
        interaction = lastInteraction,
    ) {
        dispatch(OverlayEvent.HideControls)
        menuPage = null
        rootFocus.requestFocus()
    }

    BackHandler {
        lastInteraction++
        when {
            overlayState.trackPanelOpen && menuPage != null -> {
                menuFocus = checkNotNull(menuPage)
                menuPage = null
            }
            overlayState.trackPanelOpen -> {
                dispatch(OverlayEvent.ToggleTrackPanel)
                rootFocus.requestFocus()
            }
            else -> exit()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) lastInteraction++
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
                        val isSelectKey = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == AndroidKeyEvent.KEYCODE_ENTER
                        // While a skip offer shows (and the controls are hidden) OK takes it; Down then OK pauses.
                        if (event.type == KeyEventType.KeyUp) dispatch(if (isSelectKey && s.skip != null && !s.controlsVisible) OverlayEvent.SkipNow else OverlayEvent.PlayPause)
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_MENU -> {
                        if (event.type == KeyEventType.KeyUp) {
                            menuPage = null
                            menuFocus = TrackMenuPage.PICTURE
                            dispatch(OverlayEvent.ToggleTrackPanel)
                        }
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
        app.playlistplayer.ui.common.VodDiagnosticsOverlay(controller, Modifier.align(Alignment.TopStart).padding(24.dp))

        uiState.indicator?.let { indicator ->
            Text(text = indicator.text, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp))
        }

        val s = overlayState
        if (s.controlsVisible || s.seek != null) {
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

        s.skip?.let { prompt ->
            Text(
                text = stringResource(
                    when (prompt.kind) {
                        SkipKind.INTRO -> R.string.vod_skip_intro_tv
                        SkipKind.RECAP -> R.string.vod_skip_recap_tv
                        SkipKind.CREDITS -> R.string.vod_skip_credits_tv
                    },
                ),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = if (s.controlsVisible || s.seek != null) 160.dp else 48.dp)
                    .background(Color(0xE0101010))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        if (s.trackPanelOpen) {
            TrackPanel(
                audio = s.tracks.audio,
                subtitles = s.tracks.subtitles,
                onAudio = { dispatch(OverlayEvent.SelectAudio(it)) },
                onSubtitle = { dispatch(OverlayEvent.SelectSubtitle(it)) },
                scaling = LocalVideoScaling.current,
                page = menuPage,
                menuFocus = menuFocus,
                onPage = { menuPage = it; menuFocus = it; lastInteraction++ },
                onInteraction = { lastInteraction++ },
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
    app.playlistplayer.ui.common.ScaledVideoSurface(exoPlayer, modifier)
}

@Composable
private fun ControlsBar(label: String, s: VodOverlayState, modifier: Modifier) {
    val shown = s.seek?.targetMs ?: s.positionMs
    Column(
        modifier = modifier.fillMaxWidth().background(Color(0xB0000000)).padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
            // The track panel opens with Up / Menu; show what is playing and how to change it.
            Text(
                text = stringResource(R.string.vod_options_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 1,
            )
        }
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

internal enum class TrackMenuPage(val titleRes: Int) {
    PICTURE(R.string.vod_picture_title),
    AUDIO(R.string.vod_audio),
    SUBTITLES(R.string.vod_subtitles),
}

@Composable
internal fun VodControlsAutoHide(enabled: Boolean, interaction: Long, onTimeout: () -> Unit) {
    val timeout by rememberUpdatedState(onTimeout)
    LaunchedEffect(enabled, interaction) {
        if (enabled) awaitVodControlsTimeout { timeout() }
    }
}

internal suspend fun awaitVodControlsTimeout(onTimeout: () -> Unit) {
    delay(VOD_CONTROLS_TIMEOUT_MS)
    onTimeout()
}

@Composable
internal fun TrackPanel(
    audio: List<TrackOption>,
    subtitles: List<TrackOption>,
    onAudio: (String) -> Unit,
    onSubtitle: (String?) -> Unit,
    scaling: VideoScalingControl,
    page: TrackMenuPage?,
    menuFocus: TrackMenuPage,
    onPage: (TrackMenuPage) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier,
) {
    val focus = remember(page) { FocusRequester() }
    val focusedAudioId = (audio.firstOrNull { it.selected } ?: audio.firstOrNull())?.id
    val focusedSubtitleId = subtitles.firstOrNull { it.selected }?.id
    val listState = rememberLazyListState()
    val focusIndex = when (page) {
        null -> TrackMenuPage.entries.indexOf(menuFocus)
        TrackMenuPage.PICTURE -> 1 + VideoScaling.entries.indexOf(scaling.scaling)
        TrackMenuPage.AUDIO -> if (focusedAudioId == null) 1 else 1 + audio.indexOfFirst { it.id == focusedAudioId }
        TrackMenuPage.SUBTITLES -> if (focusedSubtitleId == null) 1 else 2 + subtitles.indexOfFirst { it.id == focusedSubtitleId }
    }
    LazyColumn(
        modifier = modifier.width(360.dp).fillMaxSize().background(Color(0xE0101010)).padding(24.dp).testTag("vod.options"),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (page) {
            null -> items(TrackMenuPage.entries, key = { it.name }) { entry ->
                ListItem(
                    selected = false,
                    onClick = { onPage(entry) },
                    modifier = if (entry == menuFocus) Modifier.focusRequester(focus) else Modifier,
                    headlineContent = { Text(stringResource(entry.titleRes)) },
                )
            }
            TrackMenuPage.PICTURE -> {
                item { Text(stringResource(page.titleRes), style = MaterialTheme.typography.titleMedium, color = Color.White) }
                items(VideoScaling.entries, key = { "p:${it.name}" }) { option ->
                    ListItem(
                        selected = option == scaling.scaling,
                        onClick = { onInteraction(); scaling.select(option) },
                        modifier = if (option == scaling.scaling) Modifier.focusRequester(focus) else Modifier,
                        headlineContent = { Text(stringResource(VideoScalingLabels.label(option))) },
                    )
                }
            }
            TrackMenuPage.AUDIO -> {
                item { Text(stringResource(page.titleRes), style = MaterialTheme.typography.titleMedium, color = Color.White) }
                if (audio.isEmpty()) {
                    item { Text(stringResource(R.string.vod_audio_none), color = Color.White, modifier = Modifier.focusRequester(focus).focusable()) }
                }
                items(audio, key = { "a:${it.id}" }) { track ->
                    ListItem(
                        selected = track.selected,
                        onClick = { onInteraction(); onAudio(track.id) },
                        modifier = if (track.id == focusedAudioId) Modifier.focusRequester(focus) else Modifier,
                        headlineContent = { Text(track.label) },
                    )
                }
            }
            TrackMenuPage.SUBTITLES -> {
                item { Text(stringResource(page.titleRes), style = MaterialTheme.typography.titleMedium, color = Color.White) }
                item {
                    ListItem(
                        selected = focusedSubtitleId == null,
                        onClick = { onInteraction(); onSubtitle(null) },
                        modifier = if (focusedSubtitleId == null) Modifier.focusRequester(focus) else Modifier,
                        headlineContent = { Text(stringResource(R.string.vod_subtitles_off)) },
                    )
                }
                items(subtitles, key = { "t:${it.id}" }) { track ->
                    ListItem(
                        selected = track.selected,
                        onClick = { onInteraction(); onSubtitle(track.id) },
                        modifier = if (track.id == focusedSubtitleId) Modifier.focusRequester(focus) else Modifier,
                        headlineContent = { Text(track.label) },
                    )
                }
                if (subtitles.isEmpty()) {
                    item { Text(stringResource(R.string.vod_subtitles_none), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f)) }
                }
            }
        }
    }
    LaunchedEffect(page, focusedAudioId, focusedSubtitleId) {
        listState.scrollToItem(focusIndex)
        focus.requestFocus()
    }
}

private const val POLL_MS = 500L
internal const val VOD_CONTROLS_TIMEOUT_MS = 30_000L
