package app.playlistplayer.ui.common

/** Identifies one group of one playlist. Group ordinals are only unique within a playlist (R26). */
data class GroupKey(val playlistId: Long, val groupOrdinal: Int)

/** One group row in a browse rail. [name] is the playlist's own UTF-8 group title, emoji included (R4). */
data class GroupItem(
    val playlistId: Long,
    val ordinal: Int,
    val name: String,
    val channelCount: Int,
) {
    val key: GroupKey get() = GroupKey(playlistId, ordinal)
}

/**
 * A playlist's groups, shown under the playlist's name (R25). Groups keep the playlist's own order
 * (R4); content is never merged across sections (R26).
 */
data class PlaylistSection(
    val playlistId: Long,
    val name: String,
    val groups: List<GroupItem>,
)
