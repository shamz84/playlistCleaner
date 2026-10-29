package app.playlistplayer.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import app.playlistplayer.core.database.entity.ChannelEntity
import app.playlistplayer.core.database.entity.ChannelGroupEntity
import app.playlistplayer.core.database.entity.ChannelWithGroup
import app.playlistplayer.core.database.entity.PlaylistEntity
import app.playlistplayer.core.database.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlist ORDER BY createdAt, id")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist ORDER BY createdAt, id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE playlist SET xtreamEnabled = :enabled WHERE id = :id")
    suspend fun setXtreamEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * Channel and group access. Every read is restricted to the playlist's active generation, so a
 * generation that is still being written (or was abandoned) is never visible.
 */
@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroups(groups: List<ChannelGroupEntity>)

    @Query(
        """SELECT g.* FROM channel_group g
           JOIN sync_state s ON s.playlistId = g.playlistId AND s.activeGeneration = g.generation
           WHERE g.playlistId = :playlistId ORDER BY g.ordinal""",
    )
    fun observeGroups(playlistId: Long): Flow<List<ChannelGroupEntity>>

    @Query(
        """SELECT g.* FROM channel_group g
           JOIN sync_state s ON s.playlistId = g.playlistId AND s.activeGeneration = g.generation
           WHERE g.playlistId = :playlistId ORDER BY g.ordinal""",
    )
    suspend fun groups(playlistId: Long): List<ChannelGroupEntity>

    @Query(
        """SELECT c.playlistId, c.ordinal, c.groupOrdinal, g.name AS groupTitle, c.name, c.streamId,
                  c.containerExtension, c.tvgId, c.tvgName, c.logo, c.cuid, c.tvgChno, c.catchupType,
                  c.catchupDays, c.isHeader
           FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           JOIN channel_group g ON g.playlistId = c.playlistId AND g.generation = c.generation AND g.ordinal = c.groupOrdinal
           WHERE c.playlistId = :playlistId AND c.groupOrdinal = :groupOrdinal
           ORDER BY c.ordinal""",
    )
    fun observeChannelsInGroup(playlistId: Long, groupOrdinal: Int): Flow<List<ChannelWithGroup>>

    @Query(
        """SELECT c.playlistId, c.ordinal, c.groupOrdinal, g.name AS groupTitle, c.name, c.streamId,
                  c.containerExtension, c.tvgId, c.tvgName, c.logo, c.cuid, c.tvgChno, c.catchupType,
                  c.catchupDays, c.isHeader
           FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           JOIN channel_group g ON g.playlistId = c.playlistId AND g.generation = c.generation AND g.ordinal = c.groupOrdinal
           WHERE c.playlistId = :playlistId
           ORDER BY c.ordinal""",
    )
    suspend fun channels(playlistId: Long): List<ChannelWithGroup>

    @Query(
        """SELECT c.playlistId, c.ordinal, c.groupOrdinal, g.name AS groupTitle, c.name, c.streamId,
                  c.containerExtension, c.tvgId, c.tvgName, c.logo, c.cuid, c.tvgChno, c.catchupType,
                  c.catchupDays, c.isHeader
           FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           JOIN channel_group g ON g.playlistId = c.playlistId AND g.generation = c.generation AND g.ordinal = c.groupOrdinal
           WHERE c.playlistId = :playlistId AND c.ordinal = :ordinal""",
    )
    suspend fun channel(playlistId: Long, ordinal: Int): ChannelWithGroup?

    @Query(
        """SELECT COUNT(*) FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           WHERE c.playlistId = :playlistId""",
    )
    suspend fun countActiveChannels(playlistId: Long): Int

    /** Row count across all generations; for cleanup checks. */
    @Query("SELECT COUNT(*) FROM channel WHERE playlistId = :playlistId")
    suspend fun countAllChannelRows(playlistId: Long): Int

    @Query("SELECT DISTINCT generation FROM channel WHERE playlistId = :playlistId ORDER BY generation")
    suspend fun generationsPresent(playlistId: Long): List<Long>

    @Query("DELETE FROM channel WHERE playlistId = :playlistId AND generation = :generation")
    suspend fun deleteChannelGeneration(playlistId: Long, generation: Long)

    @Query("DELETE FROM channel_group WHERE playlistId = :playlistId AND generation = :generation")
    suspend fun deleteGroupGeneration(playlistId: Long, generation: Long)

    /** Removes every generation except [keep] (orphans from a crash between flip and cleanup). */
    @Query("DELETE FROM channel WHERE playlistId = :playlistId AND (:keep IS NULL OR generation != :keep)")
    suspend fun deleteChannelsExcept(playlistId: Long, keep: Long?)

    @Query("DELETE FROM channel_group WHERE playlistId = :playlistId AND (:keep IS NULL OR generation != :keep)")
    suspend fun deleteGroupsExcept(playlistId: Long, keep: Long?)

    @Query("DELETE FROM channel WHERE playlistId = :playlistId")
    suspend fun deleteAllChannels(playlistId: Long)

    @Query("DELETE FROM channel_group WHERE playlistId = :playlistId")
    suspend fun deleteAllGroups(playlistId: Long)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE playlistId = :playlistId")
    suspend fun get(playlistId: Long): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE playlistId = :playlistId")
    fun observe(playlistId: Long): Flow<SyncStateEntity?>

    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    @Query("UPDATE sync_state SET catalogStale = 0 WHERE playlistId = :playlistId")
    suspend fun clearCatalogStale(playlistId: Long)

    @Query("UPDATE sync_state SET epgStale = 0 WHERE playlistId = :playlistId")
    suspend fun clearEpgStale(playlistId: Long)

    @Query("DELETE FROM sync_state WHERE playlistId = :playlistId")
    suspend fun delete(playlistId: Long)
}
