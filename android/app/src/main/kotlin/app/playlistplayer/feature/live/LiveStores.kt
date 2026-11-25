package app.playlistplayer.feature.live

import app.playlistplayer.core.database.entity.ChannelProgramme
import app.playlistplayer.core.database.entity.ProgrammeEntity
import app.playlistplayer.core.sync.epg.NowNext
import app.playlistplayer.feature.settings.PlaybackPreferences

// Interfaces live TV needs from the wiring unit. In-memory implementations below serve tests and
// can back the app until the persistent stores exist. Account access reuses
// `feature.vod.AccountProvider`, ranking inputs reuse `feature.vod.VersionPreferencesProvider`.

/**
 * One live playlist entry as live playback, the player overlay and the guide need it.
 *
 * @property ordinal playlist-wide ordinal (stable key within the active generation).
 * @property streamId Xtream stream id; null for non-Xtream entries (they play their vault URL).
 * @property archiveDays effective archive window (`ChannelLiveInfo.effectiveArchiveDays`); 0 = none.
 * @property providerOrder playlist position, the comparator's final tie-break (defaults to [ordinal]).
 */
data class LiveChannel(
    val playlistId: Long,
    val ordinal: Int,
    val groupOrdinal: Int,
    val name: String,
    val streamId: Long? = null,
    val tvgId: String? = null,
    val archiveDays: Int = 0,
    val isHeader: Boolean = false,
    val logoUrl: String? = null,
    val providerOrder: Int = ordinal,
) {
    val isPlayable: Boolean get() = !isHeader
}

/**
 * Live channel lookups over the active generation (Room `ChannelEntity` joined with
 * `ChannelLiveInfo`). Never returns entries of another playlist for a [playlistId].
 */
interface LiveChannelSource {
    suspend fun channel(playlistId: Long, ordinal: Int): LiveChannel?

    /** Entries of one group in playlist order, headers included. */
    suspend fun groupChannels(playlistId: Long, groupOrdinal: Int): List<LiveChannel>

    /** Every entry of the playlist (for the other-versions index). */
    suspend fun playlistChannels(playlistId: Long): List<LiveChannel>

    /**
     * The playable URL of a non-Xtream entry, read from the secure vault (Room stores no
     * credential-bearing URLs). Null when there is none. Never log the result unredacted.
     */
    suspend fun vaultUrl(playlistId: Long, ordinal: Int): String?
}

/** Account facts the player needs from `CatalogStore.accountStatus(playlistId)`. */
data class LiveAccountStatus(
    val allowedFormats: List<String> = emptyList(),
    /** `server_info.timezone`, e.g. `Europe/Amsterdam`; null when unknown. */
    val serverTimezone: String? = null,
)

fun interface LiveAccountStatusSource {
    suspend fun status(playlistId: Long): LiveAccountStatus?
}

/** Current playback settings (`AppPreferences.playback`). */
fun interface PlaybackPreferencesProvider {
    suspend fun current(): PlaybackPreferences
}

/** Now/next for one channel: `NowNextRepository::nowNext` in the app. */
fun interface NowNextSource {
    suspend fun nowNext(playlistId: Long, channelOrdinal: Int): NowNext
}

/** Guide rows for one group: `EpgStore::gridForGroup` in the app. */
fun interface EpgGridSource {
    suspend fun gridForGroup(playlistId: Long, groupOrdinal: Int, fromMillis: Long, toMillis: Long): List<ChannelProgramme>

    /** Emits when the playlist's committed guide changes. */
    fun observeChanges(playlistId: Long): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()

    /**
     * Guide rows of the group's channels with an ordinal in `[fromOrdinal, toOrdinal]` (rows loaded
     * lazily around the visible channels). The default filters [gridForGroup].
     */
    suspend fun gridForChannels(
        playlistId: Long,
        groupOrdinal: Int,
        fromOrdinal: Int,
        toOrdinal: Int,
        fromMillis: Long,
        toMillis: Long,
    ): List<ChannelProgramme> =
        gridForGroup(playlistId, groupOrdinal, fromMillis, toMillis).filter { it.channelOrdinal in fromOrdinal..toOrdinal }
}

/** One programme by Room id: `EpgStore::programme` in the app. */
fun interface ProgrammeLookup {
    suspend fun programme(id: Long): ProgrammeEntity?
}

/** The channel last played in a playlist (U9 "the last channel is remembered"). */
data class LastChannel(val ordinal: Int, val groupOrdinal: Int)

interface LastChannelStore {
    suspend fun lastChannel(playlistId: Long): LastChannel?
    suspend fun setLastChannel(playlistId: Long, channel: LastChannel)
}

class InMemoryLastChannelStore : LastChannelStore {
    private val entries = HashMap<Long, LastChannel>()
    override suspend fun lastChannel(playlistId: Long): LastChannel? = synchronized(entries) { entries[playlistId] }
    override suspend fun setLastChannel(playlistId: Long, channel: LastChannel) {
        synchronized(entries) { entries[playlistId] = channel }
    }
}
