package app.playlistplayer.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.core.player.PlaybackState
import app.playlistplayer.feature.vod.SeekAccelerator
import app.playlistplayer.feature.vod.SeekDirection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CatchupError { NOT_ELIGIBLE, NO_ACCOUNT }

/** A pending D-pad seek: [targetMs] on the programme timeline, last [stepMs] for the hint. */
data class CatchupSeekPreview(val targetMs: Long, val stepMs: Long)

data class CatchupPlayerUiState(
    val loading: Boolean = true,
    val title: String? = null,
    val channelName: String? = null,
    /** Label of the version playing; differs from [channelName] when an other version has the archive. */
    val versionLabel: String? = null,
    val usesOtherVersion: Boolean = false,
    val isRestart: Boolean = false,
    val programmeStartMillis: Long = 0,
    val programmeStopMillis: Long = 0,
    /** Programme timeline, `0..durationMs`. */
    val durationMs: Long = 0,
    /** Seekable part of the timeline (what has aired, for a restart). */
    val availableMs: Long = 0,
    val positionMs: Long = 0,
    val seek: CatchupSeekPreview? = null,
    val isPaused: Boolean = false,
    val controlsVisible: Boolean = true,
    val ended: Boolean = false,
    val status: PlaybackStatus = PlaybackStatus(),
    val error: CatchupError? = null,
)

/**
 * Catch-up player (U8): starts [request] through [controller], shows the programme-relative
 * timeline and applies D-pad seeks ([SeekAccelerator] steps, committed on key up) as a native
 * seek or a URL rebuild. The screen calls [start] once composed, then [tick] about every 500 ms
 * to update the position.
 */
class CatchupPlayerViewModel(
    private val request: CatchupRequest,
    private val controller: CatchupPlaybackController,
    private val driver: PlayerDriver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val controlsTimeoutMs: Long = DEFAULT_CONTROLS_TIMEOUT_MS,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        CatchupPlayerUiState(
            title = request.title,
            channelName = request.channel.name,
            programmeStartMillis = request.programmeStartMillis,
            programmeStopMillis = request.programmeStopMillis,
        ),
    )
    val state: StateFlow<CatchupPlayerUiState> = mutableState.asStateFlow()

    private var playback: CatchupPlayback? = null
    private var controlsJob: Job? = null

    private var started = false

    init {
        viewModelScope.launch { driver.state.collect(::onPlaybackState) }
    }

    /**
     * Starts the catch-up session. Call from the screen once composed (not from `init`, so the
     * previous player screen's dispose, which stops the shared driver, runs first). Idempotent.
     */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch { begin() }
    }

    private suspend fun begin() {
        when (val result = controller.start(request)) {
            is CatchupStart.Started -> {
                val p = result.playback
                playback = p
                mutableState.update {
                    it.copy(
                        loading = false,
                        versionLabel = p.channel.name,
                        usesOtherVersion = p.usesOtherVersion,
                        isRestart = p.isRestart,
                        durationMs = p.durationMs,
                        availableMs = p.availableMs(),
                        positionMs = 0,
                    )
                }
                driver.play(listOf(p.currentSource()))
                showControls()
            }
            CatchupStart.NotEligible -> mutableState.update { it.copy(loading = false, error = CatchupError.NOT_ELIGIBLE) }
            CatchupStart.NoAccount -> mutableState.update { it.copy(loading = false, error = CatchupError.NO_ACCOUNT) }
        }
    }

    /** One D-pad seek key-down ([repeatCount] from the key event accelerates the step). */
    fun onSeekKey(direction: SeekDirection, repeatCount: Int) {
        val s = mutableState.value
        if (playback == null) return
        val step = SeekAccelerator.stepMs(direction, repeatCount)
        val from = s.seek?.targetMs ?: s.positionMs
        val target = (from + step).coerceIn(0, s.availableMs)
        controlsJob?.cancel()
        mutableState.update { it.copy(seek = CatchupSeekPreview(target, step), controlsVisible = true) }
    }

    /** Key up: commits the pending seek. */
    fun onSeekKeyUp() {
        val pending = mutableState.value.seek ?: return
        seekTo(pending.targetMs)
    }

    /** Seeks to [timelineMs] on the programme timeline. */
    fun seekTo(timelineMs: Long) {
        val p = playback ?: return
        val command = p.seekTo(timelineMs)
        when (command) {
            is CatchupSeekCommand.NativeSeek -> driver.seekTo(command.playerPositionMs)
            is CatchupSeekCommand.Reload -> driver.play(listOf(command.source), command.startPositionMs)
        }
        mutableState.update { it.copy(seek = null, positionMs = command.timelinePositionMs, ended = false) }
        showControls()
    }

    fun playPause() {
        val s = mutableState.value
        if (playback == null) return
        if (s.isPaused) driver.resume() else driver.pause()
        mutableState.update { it.copy(isPaused = !s.isPaused) }
        showControls()
    }

    /** Polls the player position onto the programme timeline. */
    fun tick() {
        val p = playback ?: return
        val s = mutableState.value
        if (s.seek != null) return
        mutableState.update {
            it.copy(positionMs = p.timelinePositionMs(driver.positionMs), availableMs = p.availableMs(clock()))
        }
    }

    fun showControls() {
        mutableState.update { it.copy(controlsVisible = true) }
        controlsJob?.cancel()
        controlsJob = viewModelScope.launch {
            delay(controlsTimeoutMs)
            if (!mutableState.value.isPaused && mutableState.value.seek == null) {
                mutableState.update { it.copy(controlsVisible = false) }
            }
        }
    }

    fun toggleControls() {
        if (mutableState.value.controlsVisible) {
            controlsJob?.cancel()
            mutableState.update { it.copy(controlsVisible = false) }
        } else {
            showControls()
        }
    }

    fun retry() {
        if (mutableState.value.error != null) {
            mutableState.update { it.copy(loading = true, error = null) }
            viewModelScope.launch { begin() }
        } else {
            driver.retry()
        }
    }

    fun stop() = driver.stop()

    private fun onPlaybackState(state: PlaybackState) {
        mutableState.update {
            it.copy(status = PlaybackStatus.from(state), ended = state is PlaybackState.Ended)
        }
        if (state is PlaybackState.Ended) showControls()
    }

    companion object {
        const val DEFAULT_CONTROLS_TIMEOUT_MS = 5_000L
    }
}
