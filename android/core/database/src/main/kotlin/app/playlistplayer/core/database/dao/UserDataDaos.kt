package app.playlistplayer.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import app.playlistplayer.core.database.entity.ChannelWithGroup
import app.playlistplayer.core.database.entity.FavouriteEntity
import app.playlistplayer.core.database.entity.LastChannelEntity
import app.playlistplayer.core.database.entity.PlaybackProgressEntity
import app.playlistplayer.core.database.entity.RecentChannelEntity
import app.playlistplayer.core.database.entity.ResolvedChannel
import app.playlistplayer.core.database.entity.TitleOverrideEntity
import app.playlistplayer.core.database.entity.TitlePickEntity
import kotlinx.coroutines.flow.Flow

/**
 * Personal data (U11). Channel references are resolved against the active playlist generation
 * with a join, so an entry whose channel disappeared after a re-sync is simply absent from the
 * resolved rows (hidden, not deleted) and comes back when the channel returns. `s:` keys match
 * on stream id (the first entry in playlist order when several share it), `o:` keys on ordinal.
 */
@Dao
interface UserDataDao {
    // ---- favourites ----

    @Query("SELECT * FROM favourite ORDER BY addedAt DESC, playlistId, itemKey")
    fun observeFavourites(): Flow<List<FavouriteEntity>>

    @Query("SELECT * FROM favourite ORDER BY addedAt DESC, playlistId, itemKey")
    suspend fun favourites(): List<FavouriteEntity>

    @Query("SELECT COUNT(*) > 0 FROM favourite WHERE kind = :kind AND playlistId = :playlistId AND itemKey = :key")
    suspend fun isFavourite(kind: String, playlistId: Long, key: String): Boolean

    @Upsert
    suspend fun upsertFavourite(row: FavouriteEntity)

    @Query("DELETE FROM favourite WHERE kind = :kind AND playlistId = :playlistId AND itemKey = :key")
    suspend fun deleteFavourite(kind: String, playlistId: Long, key: String)

    @Query(
        """SELECT f.playlistId AS playlistId, f.itemKey AS itemKey, f.addedAt AS at, MIN(c.ordinal) AS ordinal,
                  c.groupOrdinal AS groupOrdinal, g.name AS groupTitle, c.name AS name, c.streamId AS streamId, c.logo AS logo
           FROM favourite f
           JOIN sync_state s ON s.playlistId = f.playlistId
           JOIN channel c ON c.playlistId = f.playlistId AND c.generation = s.activeGeneration
                AND c.streamId = CAST(substr(f.itemKey, 3) AS INTEGER)
           JOIN channel_group g ON g.playlistId = c.playlistId AND g.generation = c.generation AND g.ordinal = c.groupOrdinal
           WHERE f.kind = 'CHANNEL' AND substr(f.itemKey, 1, 2) = 's:' AND c.isHeader = 0
           GROUP BY f.playlistId, f.itemKey
           UNION ALL
           SELECT f.playlistId AS playlistId, f.itemKey AS itemKey, f.addedAt AS at, c.ordinal AS ordinal,
                  c.groupOrdinal AS groupOrdinal, g.name AS groupTitle, c.name AS name, c.streamId AS streamId, c.logo AS logo
           FROM favourite f
           JOIN sync_state s ON s.playlistId = f.playlistId
           JOIN channel c ON c.playlistId = f.playlistId AND c.generation = s.activeGeneration
                AND c.ordinal = CAST(substr(f.itemKey, 3) AS INTEGER)
           JOIN channel_group g ON g.playlistId = c.playlistId AND g.generation = c.generation AND g.ordinal = c.groupOrdinal
           WHERE f.kind = 'CHANNEL' AND substr(f.itemKey, 1, 2) = 'o:' AND c.isHeader = 0
           ORDER BY at DESC, playlistId, itemKey""",
    )
    fun observeFavouriteChannels(): Flow<List<ResolvedChannel>>

    // ---- recent channels ----

    @Upsert
    suspend fun upsertRecent(row: RecentChannelEntity)

    /** Keeps the newest [keep] rows. */
    @Query("DELETE FROM recent_channel WHERE rowid NOT IN (SELECT rowid FROM recent_channel ORDER BY watchedAt DESC, rowid DESC LIMIT :keep)")
    suspend fun trimRecent(keep: Int)

    @Query("SELECT * FROM recent_channel ORDER BY watchedAt DESC")
    suspend fun recentRows(): List<RecentChannelEntity>

    @Query("DELETE FROM recent_channel WHERE playlistId = :playlistId AND streamKey = :streamKey")
    suspend fun deleteRecent(playlistId: Long, streamKey: String)

    @Query(
        """SELECT r.playlistId AS playlistId, r.streamKey AS itemKey, r.watchedAt AS at, MIN(c.ordinal) AS ordinal,
                  c.groupOrdinal AS groupOrdinal, g.name AS groupTitle, c.name AS name, c.streamId AS streamId, c.logo AS logo
           FROM recent_channel r
           JOIN sync_state s ON s.playlistId = r.playlistId
           JOIN channel c ON c.playlistId = r.playlistId AND c.generation = s.activeGeneration
                AND c.streamId = CAST(substr(r.streamKey, 3) AS INTEGER)
           JOIN channel_group g ON g.playlistId = c.playlistId AND g.generation = c.generation AND g.ordinal = c.groupOrdinal
           WHERE substr(r.streamKey, 1, 2) = 's:' AND c.isHeader = 0
           GROUP BY r.playlistId, r.streamKey
           UNION ALL
           SELECT r.playlistId AS playlistId, r.streamKey AS itemKey, r.watchedAt AS at, c.ordinal AS ordinal,
                  c.groupOrdinal AS groupOrdinal, g.name AS groupTitle, c.name AS name, c.streamId AS streamId, c.logo AS logo
           FROM recent_channel r
           JOIN sync_state s ON s.playlistId = r.playlistId
           JOIN channel c ON c.playlistId = r.playlistId AND c.generation = s.activeGeneration
                AND c.ordinal = CAST(substr(r.streamKey, 3) AS INTEGER)
           JOIN channel_group g ON g.playlistId = c.playlistId AND g.generation = c.generation AND g.ordinal = c.groupOrdinal
           WHERE substr(r.streamKey, 1, 2) = 'o:' AND c.isHeader = 0
           ORDER BY at DESC, playlistId, itemKey""",
    )
    fun observeRecentChannels(): Flow<List<ResolvedChannel>>

    // ---- last channel ----

    @Query("SELECT * FROM last_channel WHERE playlistId = :playlistId")
    suspend fun lastChannel(playlistId: Long): LastChannelEntity?

    @Upsert
    suspend fun upsertLastChannel(row: LastChannelEntity)

    /** Active channel with [streamId] (first in playlist order), or null. */
    @Query(
        """SELECT c.playlistId, c.ordinal, c.groupOrdinal, g.name AS groupTitle, c.name, c.streamId,
                  c.containerExtension, c.tvgId, c.tvgName, c.logo, c.cuid, c.tvgChno, c.catchupType,
                  c.catchupDays, c.isHeader
           FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           JOIN channel_group g ON g.playlistId = c.playlistId AND g.generation = c.generation AND g.ordinal = c.groupOrdinal
           WHERE c.playlistId = :playlistId AND c.streamId = :streamId
           ORDER BY c.ordinal LIMIT 1""",
    )
    suspend fun activeChannelByStreamId(playlistId: Long, streamId: Long): ChannelWithGroup?

    // ---- playback progress ----

    @Query("SELECT * FROM playback_progress WHERE id = :id")
    suspend fun progress(id: String): PlaybackProgressEntity?

    @Upsert
    suspend fun upsertProgress(row: PlaybackProgressEntity)

    @Query("DELETE FROM playback_progress WHERE id = :id")
    suspend fun deleteProgress(id: String)

    @Query("SELECT * FROM playback_progress ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recentProgress(limit: Int): List<PlaybackProgressEntity>

    @Query("SELECT * FROM playback_progress ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecentProgress(limit: Int): Flow<List<PlaybackProgressEntity>>

    // ---- title picks ----

    @Query("SELECT * FROM title_pick WHERE titleKey = :titleKey")
    suspend fun pick(titleKey: String): TitlePickEntity?

    @Upsert
    suspend fun upsertPick(row: TitlePickEntity)

    @Query("DELETE FROM title_pick WHERE titleKey = :titleKey")
    suspend fun deletePick(titleKey: String)

    // ---- title overrides ----

    @Query("SELECT * FROM title_override WHERE playlistId = :playlistId ORDER BY id")
    suspend fun overrides(playlistId: Long): List<TitleOverrideEntity>

    @Query("SELECT * FROM title_override WHERE playlistId = :playlistId ORDER BY id")
    fun observeOverrides(playlistId: Long): Flow<List<TitleOverrideEntity>>

    @Query("SELECT * FROM title_override WHERE id = :id")
    suspend fun override(id: Long): TitleOverrideEntity?

    @Insert
    suspend fun insertOverride(row: TitleOverrideEntity): Long

    @Query("DELETE FROM title_override WHERE id = :id")
    suspend fun deleteOverride(id: Long)

    // ---- scoped delete ----

    @Query("DELETE FROM favourite WHERE playlistId = :playlistId")
    suspend fun deleteFavourites(playlistId: Long)

    @Query("DELETE FROM playback_progress WHERE playlistId = :playlistId")
    suspend fun deleteProgressFor(playlistId: Long)

    @Query("DELETE FROM title_pick WHERE playlistId = :playlistId")
    suspend fun deletePicks(playlistId: Long)

    @Query("DELETE FROM last_channel WHERE playlistId = :playlistId")
    suspend fun deleteLastChannel(playlistId: Long)

    @Query("DELETE FROM recent_channel WHERE playlistId = :playlistId")
    suspend fun deleteRecents(playlistId: Long)

    @Query("DELETE FROM title_override WHERE playlistId = :playlistId")
    suspend fun deleteOverrides(playlistId: Long)

    /** Rows of every personal table for [playlistId] (removal tests). */
    @Query(
        """SELECT (SELECT COUNT(*) FROM favourite WHERE playlistId = :playlistId)
                + (SELECT COUNT(*) FROM playback_progress WHERE playlistId = :playlistId)
                + (SELECT COUNT(*) FROM title_pick WHERE playlistId = :playlistId)
                + (SELECT COUNT(*) FROM last_channel WHERE playlistId = :playlistId)
                + (SELECT COUNT(*) FROM recent_channel WHERE playlistId = :playlistId)
                + (SELECT COUNT(*) FROM title_override WHERE playlistId = :playlistId)""",
    )
    suspend fun countAllFor(playlistId: Long): Int
}
