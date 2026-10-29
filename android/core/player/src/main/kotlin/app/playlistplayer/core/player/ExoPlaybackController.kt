package app.playlistplayer.core.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Video format facts for display-mode matching and the debug overlay. */
data class VideoFormatInfo(
    val width: Int,
    val height: Int,
    /** Frames per second, or null when the stream does not declare it. */
    val frameRate: Float?,
    val sampleMimeType: String?,
    val codecs: String?,
    val pixelWidthHeightRatio: Float = 1f,
) {
    val aspectRatio: Float
        get() = if (height > 0) width * pixelWidthHeightRatio / height else 16f / 9f
}

/** Live diagnostics for the debug overlay. */
data class PlaybackDebugStats(
    val bufferedMs: Long = 0,
    val droppedFrames: Long = 0,
    val videoFormat: VideoFormatInfo? = null,
    val videoDecoderName: String? = null,
    val audioDecoderName: String? = null,
)

/**
 * Thin adapter between ExoPlayer and [PlaybackStateMachine]: it implements [PlaybackDriver]
 * over a [ConfiguredPlayer] and feeds player events (first frame, buffering, progress, end,
 * errors, pause) into the machine. All logic lives in the machine.
 *
 * Create and use on the main thread (the player's application looper). [close] detaches
 * listeners and stops polling; the [ConfiguredPlayer] itself is owned and released by the caller.
 */
@OptIn(UnstableApi::class)
class ExoPlaybackController(
    private val player: ConfiguredPlayer,
    accountStatusChecker: AccountStatusChecker = AccountStatusChecker.UNKNOWN,
    connectivity: ConnectivityMonitor = ConnectivityMonitor.ALWAYS_CONNECTED,
    policy: PlaybackPolicy = PlaybackPolicy(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    clock: () -> Long = SystemClock::elapsedRealtime,
    onTransition: (PlaybackState) -> Unit = {},
    private val progressPollMs: Long = 500,
) : PlaybackDriver {

    private val exo = player.exoPlayer

    val machine = PlaybackStateMachine(
        scope = scope,
        driver = this,
        accountStatusChecker = accountStatusChecker,
        connectivity = connectivity,
        policy = policy,
        clock = clock,
        onTransition = onTransition,
    )

    val state: StateFlow<PlaybackState> = machine.state

    val uiState: StateFlow<PlayerUiState> = machine.state
        .map(PlayerUiState::from)
        .stateIn(scope, SharingStarted.Eagerly, PlayerUiState.from(machine.state.value))

    private val mutableVideoFormat = MutableStateFlow<VideoFormatInfo?>(null)

    /** Format of the video track being decoded, for [DisplayModeSwitcher]. */
    val videoFormat: StateFlow<VideoFormatInfo?> = mutableVideoFormat.asStateFlow()

    private val mutableDebugStats = MutableStateFlow(PlaybackDebugStats())
    val debugStats: StateFlow<PlaybackDebugStats> = mutableDebugStats.asStateFlow()

    private var lastPositionMs = C.TIME_UNSET
    private var droppedFrames = 0L
    private var videoDecoderName: String? = null
    private var audioDecoderName: String? = null

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            machine.onFirstFrame()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Audio-only streams (radio) never render a video frame.
            if (isPlaying && !exo.currentTracks.containsType(C.TRACK_TYPE_VIDEO)) machine.onFirstFrame()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> machine.onBufferingChanged(true)
                Player.STATE_READY -> machine.onBufferingChanged(false)
                Player.STATE_ENDED -> machine.onEnded()
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            machine.onPausedChanged(!playWhenReady)
        }

        override fun onPlayerError(error: PlaybackException) {
            machine.onError(PlaybackErrorClassifier.classify(error))
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@ExoPlaybackController.droppedFrames += droppedFrames
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            videoDecoderName = decoderName
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            audioDecoderName = decoderName
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            mutableVideoFormat.value = format.toInfo()
        }
    }

    init {
        exo.addListener(listener)
        exo.addAnalyticsListener(analyticsListener)
        scope.launch {
            while (isActive) {
                delay(progressPollMs)
                poll()
            }
        }
    }

    /** Zap to [sources] (selected version first, then other versions in comparator order). */
    fun play(sources: List<PlayableSource>, startPositionMs: Long? = null) = machine.play(sources, startPositionMs)

    fun retry() = machine.retry()

    fun stop() = machine.stop()

    fun pause() {
        exo.playWhenReady = false
    }

    fun resume() {
        exo.playWhenReady = true
    }

    /** Stops, detaches from the player and cancels all jobs. Does not release the ExoPlayer. */
    fun close() {
        machine.stop()
        exo.removeListener(listener)
        exo.removeAnalyticsListener(analyticsListener)
        scope.cancel()
    }

    // ---- PlaybackDriver ------------------------------------------------------------------

    override fun prepare(source: PlayableSource, startPositionMs: Long?) {
        resetStats()
        player.prepare(source, startPositionMs)
    }

    override fun release() {
        player.stopAndClear()
    }

    // ---- Internals ----------------------------------------------------------------------

    private fun poll() {
        val position = exo.currentPosition
        if (exo.isPlaying && position != lastPositionMs) {
            lastPositionMs = position
            machine.onProgress(position)
        }
        mutableDebugStats.value = PlaybackDebugStats(
            bufferedMs = exo.totalBufferedDuration,
            droppedFrames = droppedFrames,
            videoFormat = mutableVideoFormat.value,
            videoDecoderName = videoDecoderName,
            audioDecoderName = audioDecoderName,
        )
    }

    private fun resetStats() {
        lastPositionMs = C.TIME_UNSET
        droppedFrames = 0
        videoDecoderName = null
        audioDecoderName = null
        mutableVideoFormat.value = null
    }

    private fun Format.toInfo(): VideoFormatInfo = VideoFormatInfo(
        width = width,
        height = height,
        frameRate = frameRate.takeIf { it != Format.NO_VALUE.toFloat() && it > 0f },
        sampleMimeType = sampleMimeType,
        codecs = codecs,
        pixelWidthHeightRatio = pixelWidthHeightRatio,
    )
}
