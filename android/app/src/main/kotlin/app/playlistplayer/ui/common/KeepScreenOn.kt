package app.playlistplayer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import app.playlistplayer.core.player.PlaybackState
import app.playlistplayer.feature.live.PlaybackStatus

/**
 * When a player screen keeps the display awake (no screensaver / screen timeout, e.g. the Fire TV
 * screensaver over a live channel). Playback that is running or trying to run keeps it on:
 * playing, buffering or stalled, retrying / reconnecting / waiting for the network, preparing, and
 * failover. Paused, ended, terminal errors (error panels) and idle release it.
 */
object ScreenWakePolicy {

    /** From the controller's state; [paused] is the user's pause (`playWhenReady == false`). */
    fun isActive(state: PlaybackState, paused: Boolean = false): Boolean = !paused && when (state) {
        is PlaybackState.Preparing,
        is PlaybackState.Playing,
        is PlaybackState.Stalled,
        is PlaybackState.Retrying,
        is PlaybackState.Failover,
        -> true
        PlaybackState.Idle,
        is PlaybackState.ConnectionLimit,
        is PlaybackState.AccountExpired,
        is PlaybackState.Failed,
        is PlaybackState.Ended,
        -> false
    }

    /**
     * From the overlay's [PlaybackStatus] (the live and catch-up players' view of the same state).
     * [starting] covers resolving the channel before the controller prepares it; [blocked] is a
     * screen-level error panel (e.g. channel not found); [paused] / [ended] come from the screen.
     */
    fun isActive(
        status: PlaybackStatus,
        starting: Boolean = false,
        paused: Boolean = false,
        ended: Boolean = false,
        blocked: Boolean = false,
    ): Boolean {
        if (paused || ended || blocked || status.error != null) return false
        return starting || status.isPlaying || status.indicator != null
    }
}

/**
 * Keeps the screen on while [active] by setting `View.keepScreenOn` on the host view; released
 * when [active] turns false or the caller leaves the composition. One player screen is shown at a
 * time, so there is no nesting to count.
 */
@Composable
fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        if (active) view.keepScreenOn = true
        onDispose { if (active) view.keepScreenOn = false }
    }
}
