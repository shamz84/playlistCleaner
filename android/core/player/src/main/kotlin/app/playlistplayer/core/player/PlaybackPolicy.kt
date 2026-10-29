package app.playlistplayer.core.player

/**
 * Retry, watchdog and failover budgets for [PlaybackStateMachine]. All times are milliseconds.
 *
 * Defaults are starting points from the provider spike (live TTFB 0.4–1.1 s, 2.6 s cold; connection
 * release immediate) and are expected to be tuned on device in U14.
 *
 * Before the first frame of a version, a version gets a bounded budget: at most
 * [preFirstFrameMaxAttempts] counted failures (load errors, end-of-stream and watchdog start
 * timeouts all count) and at most [preFirstFrameMaxElapsedMs] of connected time since the version
 * was first prepared. When either is exceeded the next failure fails over. Worst-case time on a
 * version that never starts is therefore about `preFirstFrameMaxElapsedMs + startTimeoutMs`.
 * Time spent offline does not count against the budget.
 *
 * After the first frame, network-class failures retry without limit while the device has
 * connectivity, with backoff from [reconnectBackoffInitialMs] doubling up to
 * [reconnectBackoffMaxMs]. The backoff resets after [stablePlaybackResetMs] of progress. While
 * offline, retries pause until connectivity returns.
 */
data class PlaybackPolicy(
    /** Counted failures allowed on a version before its first frame. */
    val preFirstFrameMaxAttempts: Int = 3,
    /** Connected time allowed on a version before its first frame. */
    val preFirstFrameMaxElapsedMs: Long = 12_000,
    /** Delay between pre-first-frame retries of the same version. */
    val preFirstFrameRetryDelayMs: Long = 500,
    /** Watchdog: no first frame within this time after prepare counts as a failure. */
    val startTimeoutMs: Long = 8_000,
    /** Watchdog: no playback progress for this long after the first frame is a stall. */
    val stallThresholdMs: Long = 6_000,
    /** First reconnect delay after the first frame. */
    val reconnectBackoffInitialMs: Long = 500,
    /** Reconnect delay cap after the first frame. */
    val reconnectBackoffMaxMs: Long = 8_000,
    /** Continuous progress after which the reconnect backoff starts again from the initial delay. */
    val stablePlaybackResetMs: Long = 15_000,
    /** Decoder/format errors tolerated after the first frame (re-prepare) before failing over. */
    val postFirstFrameMediaErrorRetries: Int = 2,
    /** Maximum number of other versions tried after the selected one. */
    val maxFailovers: Int = 5,
    /**
     * A connection-limit classification within this window after the app released a stream is
     * likely our own lingering connection, so it is retried before being shown.
     */
    val connectionLimitReleaseWindowMs: Long = 5_000,
    /** Bounded number of connection-limit retries in that case (spike: about 3 over ~3 s). */
    val connectionLimitMaxRetries: Int = 3,
    /** Delay between those connection-limit retries. */
    val connectionLimitRetryDelayMs: Long = 1_000,
    /** How long the "Switched to <label>" notice stays visible. */
    val failoverNoticeMs: Long = 4_000,
) {
    init {
        require(preFirstFrameMaxAttempts >= 1) { "preFirstFrameMaxAttempts must be >= 1" }
        require(maxFailovers >= 0) { "maxFailovers must be >= 0" }
        require(reconnectBackoffInitialMs >= 0 && reconnectBackoffMaxMs >= reconnectBackoffInitialMs) {
            "invalid reconnect backoff"
        }
    }

    /** Capped exponential reconnect delay for the zero-based [step]. */
    fun reconnectDelayMs(step: Int): Long {
        var delay = reconnectBackoffInitialMs
        repeat(step.coerceAtMost(30)) {
            delay = (delay * 2).coerceAtMost(reconnectBackoffMaxMs)
            if (delay == 0L) return 0
        }
        return delay.coerceAtMost(reconnectBackoffMaxMs)
    }
}
