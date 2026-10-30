package app.playlistplayer.ui.common.live

import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import app.playlistplayer.ui.common.UiState

/**
 * One row of a live channel list, exactly as the playlist lists it (R11: no merging, no hiding).
 *
 * @property ordinal the entry's playlist-wide ordinal (stable key; see `core.model.Channel.ordinal`).
 * @property number 1-based position among the playable entries of the group, used for number
 *   entry and shown beside the name. Null for separator/placeholder headers (`### SPORTS ###`),
 *   which are listed but not playable.
 */
data class ChannelRow(
    val playlistId: Long,
    val ordinal: Int,
    val number: Int?,
    val name: String,
    val logoUrl: String? = null,
    val hasCatchup: Boolean = false,
    val isHeader: Boolean = false,
) {
    val isPlayable: Boolean get() = !isHeader
}

/**
 * Builds [ChannelRow]s for one group in playlist order, numbering playable entries 1..n and
 * leaving headers unnumbered. The data source can use this so numbering is identical everywhere.
 */
fun numberChannels(entries: List<ChannelRow>): List<ChannelRow> {
    var next = 1
    return entries.map { row -> if (row.isHeader) row.copy(number = null) else row.copy(number = next++) }
}

/** Index in [channels] of the playable row numbered [number], or null when there is none. */
fun indexOfNumber(channels: List<ChannelRow>, number: Int): Int? =
    channels.indexOfFirst { it.number == number }.takeIf { it >= 0 }

/** UI model of the live browse screen: group rail (sectioned by playlist) and the channel list. */
data class LiveBrowseState(
    val sections: UiState<List<PlaylistSection>> = UiState.Loading,
    val selectedGroup: GroupKey? = null,
    val channels: UiState<List<ChannelRow>> = UiState.Loading,
) {
    /** Number of playable (numbered) channels in the selected group. */
    val playableCount: Int
        get() = (channels as? UiState.Content)?.value?.count { it.number != null } ?: 0
}
