package app.playlistplayer.core.model

/** A playlist group in file order. [ordinal] is the 0-based order of first appearance in the file. */
data class ChannelGroup(
    val playlistId: Long,
    val ordinal: Int,
    val name: String,
    val channelCount: Int,
)

/**
 * One playlist entry, exactly as the playlist lists it.
 *
 * The stream URL is deliberately absent: it embeds the account credentials. Playback URLs are
 * rebuilt at play time from [streamId], [containerExtension] and the [XtreamAccount] held in
 * SecureStore.
 *
 * @property ordinal 0-based position of the entry in the file (across all groups).
 * @property groupOrdinal ordinal of the entry's group (see [ChannelGroup.ordinal]).
 * @property catchupDays archive window in days; 0 when the entry has no catch-up.
 * @property isHeader true for separator/placeholder entries such as `### SPORTS ###`; they are
 *   listed but not playable.
 */
data class Channel(
    val playlistId: Long,
    val ordinal: Int,
    val groupOrdinal: Int,
    val groupTitle: String,
    val name: String,
    val streamId: Long?,
    val containerExtension: String?,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val cuid: String?,
    val tvgChno: String?,
    val catchupType: String?,
    val catchupDays: Int,
    val isHeader: Boolean,
) {
    val hasCatchup: Boolean get() = catchupDays > 0 && !isHeader
}
