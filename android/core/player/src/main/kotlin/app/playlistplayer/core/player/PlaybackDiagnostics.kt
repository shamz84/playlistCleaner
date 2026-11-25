package app.playlistplayer.core.player

/** URL-free observations; positions and durations are relative to the current media item. */
data class PlaybackDiagnosticSnapshot(
    val positionMs: Long,
    val bufferedMs: Long,
    val seekable: Boolean?,
    val playing: Boolean,
    val droppedFrames: Long,
)

/**
 * Emits bounded playback diagnostics without source IDs, names, URLs, exception messages or
 * account information. Confined to the player thread, like the playback state machine.
 */
class PlaybackDiagnostics(
    private val clock: () -> Long,
    private val emit: (String) -> Unit,
    private val sampleIntervalMs: Long = 30_000,
) {
    private var kind = "unknown"
    private var bufferingSince: Long? = null
    private var lastSampleAt = 0L

    fun transition(state: PlaybackState, snapshot: PlaybackDiagnosticSnapshot) {
        state.source?.let {
            kind = when {
                it.id.startsWith("catchup:") -> "catchup"
                it.isLive -> "live"
                else -> "vod"
            }
        }
        val event = when (state) {
            is PlaybackState.Preparing -> {
                bufferingSince = null
                "preparing attempt=${state.attempt} reconnecting=${state.reconnecting}"
            }
            is PlaybackState.Playing -> if (state.buffering) return else "playing"
            is PlaybackState.Stalled -> "watchdog_stall"
            is PlaybackState.Retrying ->
                "retry reason=${state.reason.kind} http=${state.reason.httpStatus ?: "none"} delayMs=${state.delayMs} offline=${state.waitingForNetwork} checkingAccount=${state.checkingAccount}"
            is PlaybackState.Failover -> "failover reason=${state.reason.kind} http=${state.reason.httpStatus ?: "none"}"
            is PlaybackState.Failed -> "failed reason=${state.reason.kind} http=${state.reason.httpStatus ?: "none"}"
            is PlaybackState.ConnectionLimit -> "connection_limit"
            is PlaybackState.AccountExpired -> "account_expired"
            is PlaybackState.Ended -> "ended"
            PlaybackState.Idle -> "idle"
        }
        record(event, snapshot)
    }

    fun buffering(buffering: Boolean, snapshot: PlaybackDiagnosticSnapshot) {
        if (buffering) {
            if (bufferingSince != null) return
            bufferingSince = clock()
            record("buffering_start", snapshot)
        } else {
            val start = bufferingSince ?: return
            bufferingSince = null
            record("buffering_end durationMs=${(clock() - start).coerceAtLeast(0)}", snapshot)
        }
    }

    fun sample(snapshot: PlaybackDiagnosticSnapshot) {
        if (clock() - lastSampleAt >= sampleIntervalMs) record("sample", snapshot)
    }

    private fun record(event: String, snapshot: PlaybackDiagnosticSnapshot) {
        lastSampleAt = clock()
        val bufferingMs = bufferingSince?.let { (lastSampleAt - it).coerceAtLeast(0) }
        emit(
            "kind=$kind event=$event positionMs=${snapshot.positionMs} bufferedMs=${snapshot.bufferedMs} " +
                "seekable=${snapshot.seekable ?: "unknown"} playing=${snapshot.playing} " +
                "droppedFrames=${snapshot.droppedFrames} bufferingMs=${bufferingMs ?: "none"}",
        )
    }
}
