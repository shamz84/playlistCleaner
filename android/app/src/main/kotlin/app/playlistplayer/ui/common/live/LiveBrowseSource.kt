package app.playlistplayer.ui.common.live

import app.playlistplayer.ui.common.GroupKey
import app.playlistplayer.ui.common.PlaylistSection
import kotlinx.coroutines.flow.Flow

/**
 * Live browse data the wiring unit implements over Room (active generation only).
 *
 * - [sections]: every playlist with its live groups, playlists in registry order, groups in file
 *   order (R4, R25). Groups hidden by the parental PIN (R20) are already filtered out.
 * - [channels]: the entries of one group in file order, numbered with [numberChannels].
 *
 * Both flows re-emit when a sync activates a new generation. A flow that throws is shown as a
 * generic load error with Retry; exception messages are never displayed (they could carry
 * provider details).
 */
interface LiveBrowseSource {
    fun sections(): Flow<List<PlaylistSection>>

    fun channels(group: GroupKey): Flow<List<ChannelRow>>
}
