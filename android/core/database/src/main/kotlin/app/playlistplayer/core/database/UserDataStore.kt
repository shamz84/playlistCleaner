package app.playlistplayer.core.database

import androidx.room3.withWriteTransaction
import app.playlistplayer.core.database.entity.ChannelWithGroup
import app.playlistplayer.core.database.entity.FavouriteEntity
import app.playlistplayer.core.database.entity.LastChannelEntity
import app.playlistplayer.core.database.entity.PlaybackProgressEntity
import app.playlistplayer.core.database.entity.RecentChannelEntity
import app.playlistplayer.core.database.entity.ResolvedChannel
import app.playlistplayer.core.database.entity.StreamKeys
import app.playlistplayer.core.database.entity.TitleOverrideEntity
import app.playlistplayer.core.database.entity.TitlePickEntity
import kotlinx.coroutines.flow.Flow

/**
 * Personal data persistence (U11): favourites, recent and last channels, playback progress,
 * title picks and VOD grouping overrides. The app adapts it to the feature interfaces
 * (`FavouritesStore`, `PlaybackProgressStore`, `TitlePickStore`, `LastChannelStore`, ...).
 *
 * Rows are keyed by stable identities and are never rewritten by a sync; channel rows are
 * resolved against the active generation at read time ([observeFavouriteChannels],
 * [observeRecentChannels], [resolveLastChannel]).
 */
class UserDataStore(private val db: AppDatabase) {
    private val dao = db.userDataDao()
    private val channelDao = db.channelDao()

    // ---- favourites ----

    fun observeFavourites(): Flow<List<FavouriteEntity>> = dao.observeFavourites()

    suspend fun favourites(): List<FavouriteEntity> = dao.favourites()

    suspend fun isFavourite(kind: String, playlistId: Long, key: String): Boolean = dao.isFavourite(kind, playlistId, key)

    /** Adding keeps the original `addedAt` when the favourite already exists. */
    suspend fun setFavourite(kind: String, playlistId: Long, key: String, favourite: Boolean, now: Long) = db.withWriteTransaction {
        if (!favourite) {
            dao.deleteFavourite(kind, playlistId, key)
        } else if (!dao.isFavourite(kind, playlistId, key)) {
            dao.upsertFavourite(FavouriteEntity(kind, playlistId, key, now))
        }
    }

    /** Favourite channels present in the active generation, newest favourite first. */
    fun observeFavouriteChannels(): Flow<List<ResolvedChannel>> = dao.observeFavouriteChannels()

    // ---- recent channels ----

    /** Records a watch and keeps only the newest [RecentChannelEntity.RECENT_CHANNEL_LIMIT] rows. */
    suspend fun recordRecent(playlistId: Long, streamKey: String, now: Long, keep: Int = RecentChannelEntity.RECENT_CHANNEL_LIMIT) =
        db.withWriteTransaction {
            dao.upsertRecent(RecentChannelEntity(playlistId, streamKey, now))
            dao.trimRecent(keep)
        }

    suspend fun recentRows(): List<RecentChannelEntity> = dao.recentRows()

    suspend fun removeRecent(playlistId: Long, streamKey: String) = dao.deleteRecent(playlistId, streamKey)

    /** Recent channels present in the active generation, most recent first. */
    fun observeRecentChannels(): Flow<List<ResolvedChannel>> = dao.observeRecentChannels()

    // ---- last channel ----

    suspend fun lastChannelRow(playlistId: Long): LastChannelEntity? = dao.lastChannel(playlistId)

    suspend fun setLastChannel(playlistId: Long, ordinal: Int, groupOrdinal: Int, streamKey: String, now: Long) =
        dao.upsertLastChannel(LastChannelEntity(playlistId, ordinal, groupOrdinal, streamKey, now))

    /**
     * The last channel in the active generation: by stream id for `s:` keys (survives
     * renumbering), else by ordinal. Null when it no longer exists.
     */
    suspend fun resolveLastChannel(playlistId: Long): ChannelWithGroup? {
        val row = dao.lastChannel(playlistId) ?: return null
        return resolveChannel(playlistId, row.streamKey)
    }

    /** The active, non-header channel a [StreamKeys] key refers to, or null. */
    suspend fun resolveChannel(playlistId: Long, streamKey: String): ChannelWithGroup? {
        val channel = StreamKeys.streamId(streamKey)?.let { dao.activeChannelByStreamId(playlistId, it) }
            ?: StreamKeys.ordinal(streamKey)?.let { channelDao.channel(playlistId, it) }
        return channel?.takeUnless { it.isHeader }
    }

    /** The active channel at [ordinal] (to derive its stream key), or null. */
    suspend fun activeChannel(playlistId: Long, ordinal: Int): ChannelWithGroup? = channelDao.channel(playlistId, ordinal)

    // ---- playback progress ----

    suspend fun progress(titleKey: String, season: Int?, episode: Int?): PlaybackProgressEntity? =
        dao.progress(PlaybackProgressEntity.idOf(titleKey, season, episode))

    suspend fun putProgress(row: PlaybackProgressEntity) =
        dao.upsertProgress(row.copy(id = PlaybackProgressEntity.idOf(row.titleKey, row.season, row.episode)))

    suspend fun removeProgress(titleKey: String, season: Int?, episode: Int?) =
        dao.deleteProgress(PlaybackProgressEntity.idOf(titleKey, season, episode))

    suspend fun recentProgress(limit: Int): List<PlaybackProgressEntity> = dao.recentProgress(limit)

    fun observeRecentProgress(limit: Int): Flow<List<PlaybackProgressEntity>> = dao.observeRecentProgress(limit)

    // ---- title picks ----

    suspend fun pick(titleKey: String): TitlePickEntity? = dao.pick(titleKey)

    suspend fun setPick(row: TitlePickEntity) = dao.upsertPick(row)

    suspend fun clearPick(titleKey: String) = dao.deletePick(titleKey)

    // ---- title overrides ----

    suspend fun overrides(playlistId: Long): List<TitleOverrideEntity> = dao.overrides(playlistId)

    fun observeOverrides(playlistId: Long): Flow<List<TitleOverrideEntity>> = dao.observeOverrides(playlistId)

    suspend fun override(id: Long): TitleOverrideEntity? = dao.override(id)

    suspend fun addOverride(row: TitleOverrideEntity): Long = dao.insertOverride(row.copy(id = 0))

    suspend fun removeOverride(id: Long) = dao.deleteOverride(id)

    // ---- scoped delete ----

    /** Removes every personal row of [playlistId] (also part of [PlaylistStore.deletePlaylist]). */
    suspend fun clearPlaylist(playlistId: Long) = db.withWriteTransaction { deleteAllForPlaylist(playlistId) }

    suspend fun rowCount(playlistId: Long): Int = dao.countAllFor(playlistId)

    internal suspend fun deleteAllForPlaylist(playlistId: Long) {
        dao.deleteFavourites(playlistId)
        dao.deleteProgressFor(playlistId)
        dao.deletePicks(playlistId)
        dao.deleteLastChannel(playlistId)
        dao.deleteRecents(playlistId)
        dao.deleteOverrides(playlistId)
    }
}
