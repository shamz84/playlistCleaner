package app.playlistplayer.feature.live

import app.playlistplayer.core.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A live channel by playlist and ordinal. */
data class ChannelRef(val playlistId: Long, val ordinal: Int)

/** The two most recent live channels of the session: [current] (last tuned) and [previous]. */
data class ChannelHistoryState(val current: ChannelRef? = null, val previous: ChannelRef? = null)

/**
 * Last-channel shortcut (U22, R39): the live channels tuned in this session (app process), shared
 * by every live player instance and the Live TV screen. Tuning the [ChannelHistoryState.previous]
 * channel swaps the two, so the shortcut toggles between the two most recent channels. Main thread.
 */
class ChannelHistory {
    private val mutableState = MutableStateFlow(ChannelHistoryState())
    val state: StateFlow<ChannelHistoryState> = mutableState.asStateFlow()

    /** [channel] is now playing; a different channel than the current one becomes the previous. */
    fun record(channel: ChannelRef) {
        mutableState.update { s -> if (s.current == channel) s else ChannelHistoryState(current = channel, previous = s.current) }
    }

    /** The channel the shortcut switches to, or null before a second channel was watched. */
    val lastChannel: ChannelRef? get() = mutableState.value.previous
}

/** A stream is open or being opened (not idle, ended or stopped on an error panel). */
fun PlaybackState.isStreamActive(): Boolean = when (this) {
    is PlaybackState.Preparing, is PlaybackState.Playing, is PlaybackState.Stalled,
    is PlaybackState.Retrying, is PlaybackState.Failover -> true
    else -> false
}
