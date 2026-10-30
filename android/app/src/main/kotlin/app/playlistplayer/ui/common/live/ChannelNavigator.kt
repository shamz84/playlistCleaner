package app.playlistplayer.ui.common.live

/**
 * Channel up/down within the current group (U9).
 *
 * - Channel up moves to the next playable entry (higher number, index + 1 direction); channel
 *   down moves to the previous one.
 * - Separator/placeholder headers are skipped; they are listed but never played.
 * - **Stops at the ends** (no wrap-around): channel up on the last playable entry, or down on the
 *   first, returns null and the caller stays on the current channel. Wrapping was rejected
 *   because on long groups (hundreds of entries) an accidental extra press would jump to the far
 *   end of the list, and the remote's number entry already gives a fast way to the top.
 * - Never crosses into another group or playlist (R26).
 */
class ChannelNavigator<T>(
    private val channels: List<T>,
    private val isPlayable: (T) -> Boolean,
) {
    /** Index of the next playable entry after [fromIndex], or null at the end of the group. */
    fun channelUp(fromIndex: Int): Int? {
        var i = fromIndex + 1
        while (i < channels.size) {
            if (i >= 0 && isPlayable(channels[i])) return i
            i++
        }
        return null
    }

    /** Index of the previous playable entry before [fromIndex], or null at the start of the group. */
    fun channelDown(fromIndex: Int): Int? {
        var i = minOf(fromIndex - 1, channels.size - 1)
        while (i >= 0) {
            if (isPlayable(channels[i])) return i
            i--
        }
        return null
    }

    companion object {
        fun forRows(rows: List<ChannelRow>): ChannelNavigator<ChannelRow> = ChannelNavigator(rows) { it.isPlayable }
    }
}
