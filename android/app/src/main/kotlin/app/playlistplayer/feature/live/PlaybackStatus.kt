package app.playlistplayer.feature.live

import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.player.PlaybackState
import app.playlistplayer.core.player.PlayerUiState

/**
 * The player overlay's status, mapped from [PlaybackState] through [PlayerUiState] (the U6
 * mapping) into structured values the UI localizes from `strings_live.xml`.
 */
data class PlaybackStatus(
    val indicator: Indicator? = null,
    /** Transient "Switched to <label>" after a failover, or null. */
    val switchedTo: String? = null,
    /** Persistent error panel with Retry, or null. Terminal states never navigate away. */
    val error: PlaybackErrorPanel? = null,
    val isPlaying: Boolean = false,
) {
    enum class Indicator { BUFFERING, RECONNECTING, WAITING_FOR_NETWORK }

    companion object {
        fun from(state: PlaybackState): PlaybackStatus {
            val ui = PlayerUiState.from(state)
            val error = ui.error?.let { panel ->
                when (panel.type) {
                    PlayerUiState.ErrorType.CONNECTION_LIMIT ->
                        PlaybackErrorPanel.ConnectionLimit(panel.activeConnections, panel.maxConnections)
                    PlayerUiState.ErrorType.ACCOUNT_EXPIRED -> PlaybackErrorPanel.AccountExpired
                    PlayerUiState.ErrorType.FAILED -> PlaybackErrorPanel.Failed(
                        label = state.source?.label.orEmpty(),
                        triedVersions = (state as? PlaybackState.Failed)?.triedVersions ?: 1,
                    )
                }
            }
            return PlaybackStatus(
                indicator = when (ui.indicator) {
                    PlayerUiState.Indicator.BUFFERING -> Indicator.BUFFERING
                    PlayerUiState.Indicator.RECONNECTING -> Indicator.RECONNECTING
                    PlayerUiState.Indicator.WAITING_FOR_NETWORK -> Indicator.WAITING_FOR_NETWORK
                    null -> null
                },
                switchedTo = (state as? PlaybackState.Playing)?.notice,
                error = error,
                isPlaying = ui.isPlaying,
            )
        }
    }
}

sealed interface PlaybackErrorPanel {
    val canRetry: Boolean get() = true

    /** "N of M connections in use" (R22); no failover was attempted. */
    data class ConnectionLimit(val activeConnections: Int?, val maxConnections: Int?) : PlaybackErrorPanel

    data object AccountExpired : PlaybackErrorPanel

    /** [label] and `triedVersions - 1` other versions could not be played. */
    data class Failed(val label: String, val triedVersions: Int) : PlaybackErrorPanel
}

