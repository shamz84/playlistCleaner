package app.playlistplayer.feature.live

import app.playlistplayer.core.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A live channel by playlist and ordinal. */
data class ChannelRef(val playlistId: Long, val ordinal: Int)

/** A recently watched channel with the name to show for it. */
data class RecentChannel(val ref: ChannelRef, val name: String, val logoUrl: String? = null)

/**
 * The two most recent live channels of the session: [current] (last tuned) and [previous];
 * [recent] lists the last [ChannelHistory.MAX_RECENT] distinct channels, newest first.
 */
data class ChannelHistoryState(
    val current: ChannelRef? = null,
    val previous: ChannelRef? = null,
    val recent: List<RecentChannel> = emptyList(),
)

/**
 * Last-channel shortcut (U22, R39): the live channels tuned in this session (app process), shared
 * by every live player instance and the Live TV screen. Tuning the [ChannelHistoryState.previous]
 * channel swaps the two, so the shortcut toggles between the two most recent channels. Main thread.
 */
class ChannelHistory {
    private val mutableState = MutableStateFlow(ChannelHistoryState())
    val state: StateFlow<ChannelHistoryState> = mutableState.asStateFlow()

    /** [channel] is now playing; a different channel than the current one becomes the previous. */
    fun record(channel: ChannelRef, name: String = "", logoUrl: String? = null) {
        mutableState.update { s ->
            val recent = if (name.isBlank()) {
                s.recent
            } else {
                (listOf(RecentChannel(channel, name, logoUrl)) + s.recent.filterNot { it.ref == channel }).take(MAX_RECENT)
            }
            if (s.current == channel) s.copy(recent = recent) else s.copy(current = channel, previous = s.current, recent = recent)
        }
    }

    /** The channel the shortcut switches to, or null before a second channel was watched. */
    val lastChannel: ChannelRef? get() = mutableState.value.previous

    /** Forget stale or newly hidden channels while keeping the currently playing preview. */
    fun clearPrevious() {
        mutableState.update { state ->
            state.copy(previous = null, recent = state.recent.filter { channel -> channel.ref == state.current })
        }
    }

    companion object {
        const val MAX_RECENT = 5
    }
}

/** A stream is open or being opened (not idle, ended or stopped on an error panel). */
fun PlaybackState.isStreamActive(): Boolean = when (this) {
    is PlaybackState.Preparing, is PlaybackState.Playing, is PlaybackState.Stalled,
    is PlaybackState.Retrying, is PlaybackState.Failover -> true
    else -> false
}
