package app.playlistplayer.core.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the state machine asks the player to do. Implemented by [ExoPlaybackController]. */
interface PlaybackDriver {
    /**
     * Closes any open stream, then opens and starts [source]. A null [startPositionMs] means the
     * default position, which is the live edge for live streams.
     */
    fun prepare(source: PlayableSource, startPositionMs: Long?)

    /** Stops and closes the current stream (frees the provider connection); keeps the player. */
    fun release()
}

/**
 * Pure-Kotlin playback lifecycle: retry budgets, watchdog, account classification and failover.
 * It drives a [PlaybackDriver] and is fed player events, so it is testable without ExoPlayer.
 *
 * Rules (U6 plus the folded review fixes):
 * - Before the first frame a version has a bounded budget ([PlaybackPolicy]); watchdog start
 *   timeouts count. Exhausting it fails over.
 * - After the first frame, network errors, stalls and live end-of-stream retry at the live edge
 *   without limit while connected (capped backoff), and pause while offline.
 * - HTTP 401/403/509 query [AccountStatusChecker]: Expired -> [PlaybackState.AccountExpired];
 *   at the connection limit -> [PlaybackState.ConnectionLimit] (never fails over), except that
 *   shortly after the app released a stream it is retried a bounded number of times first;
 *   otherwise it is a stream error and fails over.
 * - 404/405/410 and other HTTP stream errors fail over at once.
 * - Failover releases the current stream before opening the next and tries at most
 *   [PlaybackPolicy.maxFailovers] other versions; the selected version is first again on the
 *   next [play].
 * - [play] (a zap) cancels pending retries, account checks and the watchdog.
 *
 * Not thread-safe: call every method on the [scope]'s thread (the player's application thread).
 */
class PlaybackStateMachine(
    private val scope: CoroutineScope,
    private val driver: PlaybackDriver,
    private val accountStatusChecker: AccountStatusChecker = AccountStatusChecker.UNKNOWN,
    private val connectivity: ConnectivityMonitor = ConnectivityMonitor.ALWAYS_CONNECTED,
    private val policy: PlaybackPolicy = PlaybackPolicy(),
    private val clock: () -> Long,
    private val onTransition: (PlaybackState) -> Unit = {},
) {
    private val mutableState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private val watchdog = PlaybackWatchdog(scope, clock, ::onWatchdogTimeout)

    // Session (one zap).
    private var session = 0
    private var sessionSources: List<PlayableSource> = emptyList()
    private var failover: FailoverController? = null
    private var current: PlayableSource? = null
    private var sessionHadFailure = false
    private var pendingNotice: String? = null
    private var lastPositionMs: Long? = null
    private var paused = false

    // Current version.
    private var prepareCount = 0
    private var preFrameFailures = 0
    private var budgetStartedAt = 0L
    private var firstFrameSeen = false
    private var reconnectStep = 0
    private var mediaErrorRetries = 0
    private var connectionLimitRetries = 0
    private var playingSince = 0L

    // Stream connection bookkeeping.
    private var streamOpen = false
    private var lastAppReleaseAt: Long? = null

    private var pendingJob: Job? = null
    private var noticeJob: Job? = null

    /** The version currently selected (after any failover), or null when idle. */
    val currentSource: PlayableSource? get() = current

    /**
     * Zap: plays [sources] (selected version first, then other versions in comparator order).
     * [startPositionMs] is a VOD resume position; ignored for live.
     */
    fun play(sources: List<PlayableSource>, startPositionMs: Long? = null) {
        require(sources.isNotEmpty()) { "At least one source is required" }
        cancelPending()
        watchdog.disarm()
        releaseStream()
        session++
        sessionSources = sources
        val controller = FailoverController(sources, policy.maxFailovers)
        failover = controller
        sessionHadFailure = false
        lastPositionMs = startPositionMs
        paused = false
        beginVersion(controller.preferred)
        startAttempt()
    }

    /** User "Retry": restarts the session from the selected version with a fresh budget. */
    fun retry() {
        if (sessionSources.isEmpty()) return
        play(sessionSources, lastPositionMs)
    }

    /** Stops playback and closes the stream. */
    fun stop() {
        cancelPending()
        watchdog.disarm()
        releaseStream()
        session++
        current = null
        set(PlaybackState.Idle)
    }

    // ---- Player events -------------------------------------------------------------------

    /** The first video frame (or, for audio-only streams, playback start) after a prepare. */
    fun onFirstFrame() {
        val s = state.value as? PlaybackState.Preparing ?: return
        firstFrameSeen = true
        preFrameFailures = 0
        connectionLimitRetries = 0
        playingSince = clock()
        val notice = pendingNotice
        pendingNotice = null
        set(PlaybackState.Playing(s.source, buffering = false, notice = notice))
        if (notice != null) {
            noticeJob?.cancel()
            noticeJob = scope.launch {
                delay(policy.failoverNoticeMs)
                val now = state.value
                if (now is PlaybackState.Playing && now.notice == notice) set(now.copy(notice = null))
            }
        }
        if (!paused) watchdog.arm(policy.stallThresholdMs)
    }

    fun onBufferingChanged(buffering: Boolean) {
        val s = state.value as? PlaybackState.Playing ?: return
        if (s.buffering != buffering) {
            if (!buffering && !paused) watchdog.arm(policy.stallThresholdMs)
            set(s.copy(buffering = buffering))
        }
    }

    /** Playback position advanced to [positionMs]. */
    fun onProgress(positionMs: Long) {
        val s = state.value as? PlaybackState.Playing ?: return
        if (!s.source.isLive) lastPositionMs = positionMs
        watchdog.kick()
        if (clock() - playingSince >= policy.stablePlaybackResetMs) {
            reconnectStep = 0
            mediaErrorRetries = 0
        }
    }

    /** Preserve a user seek even if the player stalls before it can report further progress. */
    fun onSeek(positionMs: Long) {
        val source = current ?: return
        if (!source.isLive) lastPositionMs = positionMs.coerceAtLeast(0)
        if (state.value is PlaybackState.Playing && !paused) watchdog.arm(policy.stallThresholdMs)
    }

    /** The user paused or resumed; the watchdog does not run while paused. */
    fun onPausedChanged(isPaused: Boolean) {
        paused = isPaused
        if (state.value !is PlaybackState.Playing) return
        if (isPaused) watchdog.disarm() else watchdog.arm(policy.stallThresholdMs)
    }

    /** The player reached the end of the media. On live this is a stream drop. */
    fun onEnded() {
        val s = state.value
        if (s !is PlaybackState.Preparing && s !is PlaybackState.Playing) return
        val source = s.source ?: return
        watchdog.disarm()
        streamOpen = false
        if (source.isLive) {
            handleFailure(PlaybackError.STREAM_ENDED)
        } else {
            set(PlaybackState.Ended(source))
        }
    }

    fun onError(error: PlaybackError) {
        val s = state.value
        if (s !is PlaybackState.Preparing && s !is PlaybackState.Playing && s !is PlaybackState.Stalled) return
        watchdog.disarm()
        streamOpen = false
        handleFailure(error)
    }

    // ---- Internals ----------------------------------------------------------------------

    private fun onWatchdogTimeout() {
        when (val s = state.value) {
            is PlaybackState.Preparing -> {
                releaseStream()
                handleFailure(PlaybackError.STALL)
            }
            is PlaybackState.Playing -> {
                set(PlaybackState.Stalled(s.source))
                releaseStream()
                handleFailure(PlaybackError.STALL)
            }
            else -> Unit
        }
    }

    private fun beginVersion(source: PlayableSource) {
        current = source
        prepareCount = 0
        preFrameFailures = 0
        budgetStartedAt = clock()
        firstFrameSeen = false
        reconnectStep = 0
        mediaErrorRetries = 0
        connectionLimitRetries = 0
    }

    private fun startAttempt() {
        val source = current ?: return
        prepareCount++
        streamOpen = true
        set(PlaybackState.Preparing(source, prepareCount, reconnecting = prepareCount > 1 || sessionHadFailure))
        watchdog.arm(policy.startTimeoutMs)
        driver.prepare(source, if (source.isLive) null else lastPositionMs)
    }

    private fun handleFailure(error: PlaybackError) {
        sessionHadFailure = true
        when (error.kind) {
            PlaybackError.Kind.BEHIND_LIVE_WINDOW -> scheduleRetry(error, 0)
            PlaybackError.Kind.HTTP_AUTH -> checkAccount(error)
            PlaybackError.Kind.HTTP_NOT_FOUND,
            PlaybackError.Kind.HTTP_OTHER,
            -> failOver(error)
            PlaybackError.Kind.MEDIA ->
                if (!firstFrameSeen || mediaErrorRetries >= policy.postFirstFrameMediaErrorRetries) {
                    failOver(error)
                } else {
                    mediaErrorRetries++
                    scheduleRetry(error, policy.reconnectDelayMs(reconnectStep++))
                }
            PlaybackError.Kind.NETWORK,
            PlaybackError.Kind.STALL,
            PlaybackError.Kind.STREAM_ENDED,
            PlaybackError.Kind.UNKNOWN,
            -> retryOrFailOver(error)
        }
    }

    private fun retryOrFailOver(error: PlaybackError) {
        if (!connectivity.isConnected.value) {
            waitForNetwork(error)
            return
        }
        if (!firstFrameSeen) {
            preFrameFailures++
            val elapsed = clock() - budgetStartedAt
            if (preFrameFailures >= policy.preFirstFrameMaxAttempts || elapsed >= policy.preFirstFrameMaxElapsedMs) {
                failOver(error)
            } else {
                scheduleRetry(error, policy.preFirstFrameRetryDelayMs)
            }
        } else {
            scheduleRetry(error, policy.reconnectDelayMs(reconnectStep++))
        }
    }

    private fun scheduleRetry(error: PlaybackError, delayMs: Long) {
        val source = current ?: return
        val token = session
        set(PlaybackState.Retrying(source, error, delayMs))
        pendingJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (token == session) startAttempt()
        }
    }

    private fun waitForNetwork(error: PlaybackError) {
        val source = current ?: return
        val token = session
        val offlineSince = clock()
        set(PlaybackState.Retrying(source, error, delayMs = 0, waitingForNetwork = true))
        pendingJob = scope.launch {
            connectivity.isConnected.first { it }
            if (token != session) return@launch
            // Offline time does not count against the pre-first-frame budget.
            budgetStartedAt += clock() - offlineSince
            startAttempt()
        }
    }

    private fun checkAccount(error: PlaybackError) {
        val source = current ?: return
        val token = session
        set(PlaybackState.Retrying(source, error, delayMs = 0, checkingAccount = true))
        pendingJob = scope.launch {
            val status = try {
                accountStatusChecker.check(source)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AccountStatus.Unknown
            }
            if (token != session || current != source) return@launch
            when {
                status is AccountStatus.Expired -> terminal(PlaybackState.AccountExpired(source))
                status is AccountStatus.Active && status.atConnectionLimit -> onConnectionLimit(source, error, status)
                else -> failOver(error)
            }
        }
    }

    private fun onConnectionLimit(source: PlayableSource, error: PlaybackError, status: AccountStatus.Active) {
        val releasedAt = lastAppReleaseAt
        val withinReleaseWindow = releasedAt != null && clock() - releasedAt <= policy.connectionLimitReleaseWindowMs
        if (connectionLimitRetries < policy.connectionLimitMaxRetries &&
            (connectionLimitRetries > 0 || withinReleaseWindow)
        ) {
            connectionLimitRetries++
            scheduleRetry(error, policy.connectionLimitRetryDelayMs)
        } else {
            terminal(PlaybackState.ConnectionLimit(source, status.activeConnections, status.maxConnections))
        }
    }

    private fun failOver(error: PlaybackError) {
        val from = current ?: return
        val controller = failover ?: return
        watchdog.disarm()
        releaseStream()
        val next = controller.next()
        if (next == null) {
            terminal(PlaybackState.Failed(from, error, controller.triedCount))
            return
        }
        set(PlaybackState.Failover(from, next, controller.failoverCount, error))
        pendingNotice = next.label
        beginVersion(next)
        startAttempt()
    }

    private fun terminal(state: PlaybackState) {
        watchdog.disarm()
        noticeJob?.cancel()
        pendingNotice = null
        releaseStream()
        set(state)
    }

    /** Closes the stream. A release of an open stream is an app-initiated release. */
    private fun releaseStream() {
        if (streamOpen) {
            lastAppReleaseAt = clock()
            streamOpen = false
        }
        driver.release()
    }

    private fun cancelPending() {
        pendingJob?.cancel()
        pendingJob = null
        noticeJob?.cancel()
        noticeJob = null
        pendingNotice = null
    }

    private fun set(state: PlaybackState) {
        mutableState.value = state
        onTransition(state)
    }
}
