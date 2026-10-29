package app.playlistplayer.core.player

/**
 * What the player screen shows over the video, derived purely from [PlaybackState].
 * Default English text is provided; UI layers may localize from the structured fields.
 */
data class PlayerUiState(
    val indicator: Indicator? = null,
    /** Transient notice, e.g. "Switched to BBC One HD". The machine clears it after a few seconds. */
    val notice: String? = null,
    /** Persistent error panel with a Retry action, or null. */
    val error: ErrorPanel? = null,
    val isPlaying: Boolean = false,
) {
    enum class Indicator(val text: String) {
        BUFFERING("Buffering"),
        RECONNECTING("Reconnecting"),
        WAITING_FOR_NETWORK("No network connection. Waiting to reconnect"),
    }

    enum class ErrorType { FAILED, CONNECTION_LIMIT, ACCOUNT_EXPIRED }

    data class ErrorPanel(
        val type: ErrorType,
        val title: String,
        val message: String,
        val activeConnections: Int? = null,
        val maxConnections: Int? = null,
        val canRetry: Boolean = true,
    )

    companion object {
        fun from(state: PlaybackState): PlayerUiState = when (state) {
            PlaybackState.Idle -> PlayerUiState()
            is PlaybackState.Preparing -> PlayerUiState(
                indicator = if (state.reconnecting) Indicator.RECONNECTING else Indicator.BUFFERING,
            )
            is PlaybackState.Playing -> PlayerUiState(
                indicator = if (state.buffering) Indicator.BUFFERING else null,
                notice = state.notice?.let { "Switched to $it" },
                isPlaying = true,
            )
            is PlaybackState.Stalled -> PlayerUiState(indicator = Indicator.RECONNECTING)
            is PlaybackState.Retrying -> PlayerUiState(
                indicator = if (state.waitingForNetwork) Indicator.WAITING_FOR_NETWORK else Indicator.RECONNECTING,
            )
            is PlaybackState.Failover -> PlayerUiState(indicator = Indicator.RECONNECTING)
            is PlaybackState.ConnectionLimit -> PlayerUiState(
                error = ErrorPanel(
                    type = ErrorType.CONNECTION_LIMIT,
                    title = "Connections in use",
                    message = connectionLimitMessage(state.activeConnections, state.maxConnections),
                    activeConnections = state.activeConnections,
                    maxConnections = state.maxConnections,
                ),
            )
            is PlaybackState.AccountExpired -> PlayerUiState(
                error = ErrorPanel(
                    type = ErrorType.ACCOUNT_EXPIRED,
                    title = "Account expired",
                    message = "This playlist's account has expired or is disabled. Renew it with your provider, then retry.",
                ),
            )
            is PlaybackState.Failed -> PlayerUiState(
                error = ErrorPanel(
                    type = ErrorType.FAILED,
                    title = "Can't play this channel",
                    message = if (state.triedVersions > 1) {
                        "${state.source.label} and ${state.triedVersions - 1} other version(s) could not be played."
                    } else {
                        "${state.source.label} could not be played."
                    },
                ),
            )
            is PlaybackState.Ended -> PlayerUiState()
        }

        private fun connectionLimitMessage(active: Int?, max: Int?): String =
            if (active != null && max != null) {
                "All $max connection(s) on this account are in use ($active active). Stop playback on another device, then retry."
            } else {
                "All connections on this account are in use. Stop playback on another device, then retry."
            }
    }
}
