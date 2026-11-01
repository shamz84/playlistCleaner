package app.playlistplayer.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import app.playlistplayer.core.database.entity.ChannelEpgKey
import app.playlistplayer.core.database.entity.ChannelEpgMapEntity
import app.playlistplayer.core.database.entity.ChannelProgramme
import app.playlistplayer.core.database.entity.EpgSourceStateEntity
import app.playlistplayer.core.database.entity.ProgrammeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpgSourceStateDao {
    @Query("SELECT * FROM epg_source_state WHERE playlistId = :playlistId")
    suspend fun get(playlistId: Long): EpgSourceStateEntity?

    @Query("SELECT * FROM epg_source_state WHERE playlistId = :playlistId")
    fun observe(playlistId: Long): Flow<EpgSourceStateEntity?>

    @Upsert
    suspend fun upsert(state: EpgSourceStateEntity)

    @Query("DELETE FROM epg_source_state WHERE playlistId = :playlistId")
    suspend fun delete(playlistId: Long)
}

@Dao
interface ChannelEpgMapDao {
    @Query("SELECT * FROM channel_epg_map WHERE playlistId = :playlistId")
    suspend fun all(playlistId: Long): List<ChannelEpgMapEntity>

    @Query("SELECT * FROM channel_epg_map WHERE playlistId = :playlistId AND entryKey = :entryKey")
    suspend fun get(playlistId: Long, entryKey: String): ChannelEpgMapEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<ChannelEpgMapEntity>)

    @Query("UPDATE channel_epg_map SET lastBackfillAt = :at WHERE playlistId = :playlistId AND entryKey = :entryKey")
    suspend fun markBackfilled(playlistId: Long, entryKey: String, at: Long)

    @Query("DELETE FROM channel_epg_map WHERE playlistId = :playlistId")
    suspend fun deleteAll(playlistId: Long)

    /** Removes the mappings that did not come from [origin] (e.g. keep only OVERRIDE ones). */
    @Query("DELETE FROM channel_epg_map WHERE playlistId = :playlistId AND origin != :origin")
    suspend fun deleteAllExceptOrigin(playlistId: Long, origin: String)

    /** Guide keys of the playlist's active channels that have a mapping. */
    @Query(
        """SELECT c.ordinal AS channelOrdinal, m.channelKey AS channelKey FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           JOIN channel_epg_map m ON m.playlistId = c.playlistId AND m.entryKey = COALESCE('s:' || c.streamId, 'o:' || c.ordinal)
           WHERE c.playlistId = :playlistId ORDER BY c.ordinal""",
    )
    suspend fun channelKeys(playlistId: Long): List<ChannelEpgKey>

    @Query(
        """SELECT c.ordinal AS channelOrdinal, m.channelKey AS channelKey FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           JOIN channel_epg_map m ON m.playlistId = c.playlistId AND m.entryKey = COALESCE('s:' || c.streamId, 'o:' || c.ordinal)
           WHERE c.playlistId = :playlistId AND c.groupOrdinal = :groupOrdinal ORDER BY c.ordinal""",
    )
    suspend fun channelKeysInGroup(playlistId: Long, groupOrdinal: Int): List<ChannelEpgKey>

    @Query(
        """SELECT m.channelKey FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           JOIN channel_epg_map m ON m.playlistId = c.playlistId AND m.entryKey = COALESCE('s:' || c.streamId, 'o:' || c.ordinal)
           WHERE c.playlistId = :playlistId AND c.ordinal = :ordinal""",
    )
    suspend fun channelKey(playlistId: Long, ordinal: Int): String?
}

/**
 * Programme reads ignore `pending` rows. Every time-range read bounds `startMillis` from below by
 * `from - maxDurationMillis` so it stays an index range scan on (playlistId, channelKey, startMillis).
 */
@Dao
interface ProgrammeDao {
    @Insert
    suspend fun insert(rows: List<ProgrammeEntity>)

    @Query("SELECT * FROM programme WHERE id = :id AND pending = 0")
    suspend fun get(id: Long): ProgrammeEntity?

    @Query(
        """SELECT * FROM programme
           WHERE playlistId = :playlistId AND channelKey IN (:channelKeys) AND pending = 0
             AND startMillis < :toMillis AND startMillis > :fromMillis - :maxDurationMillis AND stopMillis > :fromMillis
           ORDER BY channelKey, startMillis""",
    )
    suspend fun forChannels(
        playlistId: Long,
        channelKeys: List<String>,
        fromMillis: Long,
        toMillis: Long,
        maxDurationMillis: Long,
    ): List<ProgrammeEntity>

    @Query(
        """SELECT c.ordinal AS channelOrdinal, p.* FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           JOIN channel_epg_map m ON m.playlistId = c.playlistId AND m.entryKey = COALESCE('s:' || c.streamId, 'o:' || c.ordinal)
           JOIN programme p ON p.playlistId = c.playlistId AND p.channelKey = m.channelKey AND p.pending = 0
                AND p.startMillis < :toMillis AND p.startMillis > :fromMillis - :maxDurationMillis AND p.stopMillis > :fromMillis
           WHERE c.playlistId = :playlistId AND c.groupOrdinal = :groupOrdinal
           ORDER BY c.ordinal, p.startMillis""",
    )
    suspend fun forGroup(
        playlistId: Long,
        groupOrdinal: Int,
        fromMillis: Long,
        toMillis: Long,
        maxDurationMillis: Long,
    ): List<ChannelProgramme>

    /** [forGroup] limited to the channels with an ordinal in `[fromOrdinal, toOrdinal]` (guide rows loaded lazily). */
    @Query(
        """SELECT c.ordinal AS channelOrdinal, p.* FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           JOIN channel_epg_map m ON m.playlistId = c.playlistId AND m.entryKey = COALESCE('s:' || c.streamId, 'o:' || c.ordinal)
           JOIN programme p ON p.playlistId = c.playlistId AND p.channelKey = m.channelKey AND p.pending = 0
                AND p.startMillis < :toMillis AND p.startMillis > :fromMillis - :maxDurationMillis AND p.stopMillis > :fromMillis
           WHERE c.playlistId = :playlistId AND c.groupOrdinal = :groupOrdinal AND c.ordinal BETWEEN :fromOrdinal AND :toOrdinal
           ORDER BY c.ordinal, p.startMillis""",
    )
    suspend fun forGroupRange(
        playlistId: Long,
        groupOrdinal: Int,
        fromOrdinal: Int,
        toOrdinal: Int,
        fromMillis: Long,
        toMillis: Long,
        maxDurationMillis: Long,
    ): List<ChannelProgramme>

    @Query(
        """SELECT * FROM programme
           WHERE playlistId = :playlistId AND channelKey = :channelKey AND pending = 0
             AND stopMillis > :nowMillis AND startMillis > :nowMillis - :maxDurationMillis
           ORDER BY startMillis LIMIT :limit""",
    )
    suspend fun upcoming(playlistId: Long, channelKey: String, nowMillis: Long, maxDurationMillis: Long, limit: Int): List<ProgrammeEntity>

    /** Ended programmes that started at or after [fromMillis], newest first (catch-up list). */
    @Query(
        """SELECT * FROM programme
           WHERE playlistId = :playlistId AND channelKey = :channelKey AND pending = 0
             AND startMillis >= :fromMillis AND stopMillis <= :nowMillis
           ORDER BY startMillis DESC""",
    )
    suspend fun past(playlistId: Long, channelKey: String, fromMillis: Long, nowMillis: Long): List<ProgrammeEntity>

    @Query("SELECT MIN(startMillis) FROM programme WHERE playlistId = :playlistId AND channelKey = :channelKey AND pending = 0")
    suspend fun earliestStart(playlistId: Long, channelKey: String): Long?

    @Query("SELECT COUNT(*) FROM programme WHERE playlistId = :playlistId AND pending = 0")
    suspend fun count(playlistId: Long): Int

    @Query("SELECT COUNT(*) FROM programme WHERE playlistId = :playlistId AND pending = 1")
    suspend fun countPending(playlistId: Long): Int

    @Query("SELECT MIN(startMillis) FROM programme WHERE playlistId = :playlistId AND pending = 0 AND source = 'XMLTV'")
    suspend fun xmltvCoverageFrom(playlistId: Long): Long?

    @Query("SELECT MAX(stopMillis) FROM programme WHERE playlistId = :playlistId AND pending = 0 AND source = 'XMLTV'")
    suspend fun xmltvCoverageTo(playlistId: Long): Long?

    // ---- import lifecycle ----

    @Query("DELETE FROM programme WHERE playlistId = :playlistId AND pending = 1")
    suspend fun deletePending(playlistId: Long)

    @Query("DELETE FROM programme WHERE importId = :importId AND pending = 1")
    suspend fun deleteImport(importId: Long)

    /** Commit step 1: the import replaces the committed guide from [importStart] onward. */
    @Query(
        """DELETE FROM programme WHERE playlistId = :playlistId AND pending = 0 AND source = :source
           AND startMillis >= :importStart""",
    )
    suspend fun deleteCommittedFrom(playlistId: Long, source: String, importStart: Long)

    /** Commit step 2: keep the committed past; drop staged past rows overlapping a committed XMLTV slot. */
    @Query(
        """DELETE FROM programme WHERE importId = :importId AND pending = 1 AND startMillis < :importStart
           AND EXISTS (SELECT 1 FROM programme o WHERE o.playlistId = programme.playlistId AND o.pending = 0
                AND o.source = 'XMLTV' AND o.channelKey = programme.channelKey
                AND o.startMillis < programme.stopMillis AND o.startMillis > programme.startMillis - :maxDurationMillis
                AND o.stopMillis > programme.startMillis)""",
    )
    suspend fun dropStagedPastOverlaps(importId: Long, importStart: Long, maxDurationMillis: Long)

    /** Commit step 3: XMLTV wins over archive backfill for the same slot. */
    @Query(
        """DELETE FROM programme WHERE playlistId = :playlistId AND pending = 0 AND source = 'XTREAM_ARCHIVE'
           AND EXISTS (SELECT 1 FROM programme n WHERE n.importId = :importId AND n.pending = 1
                AND n.channelKey = programme.channelKey
                AND n.startMillis < programme.stopMillis AND n.startMillis > programme.startMillis - :maxDurationMillis
                AND n.stopMillis > programme.startMillis)""",
    )
    suspend fun dropArchiveOverlapping(playlistId: Long, importId: Long, maxDurationMillis: Long)

    /** Commit step 4. */
    @Query("UPDATE programme SET pending = 0 WHERE importId = :importId AND pending = 1")
    suspend fun publish(importId: Long): Int

    /** Prune: rows that ended before [beforeMillis]. */
    @Query("DELETE FROM programme WHERE playlistId = :playlistId AND pending = 0 AND stopMillis < :beforeMillis")
    suspend fun deleteEndedBefore(playlistId: Long, beforeMillis: Long): Int

    @Query("DELETE FROM programme WHERE playlistId = :playlistId")
    suspend fun deleteAll(playlistId: Long)

    @Query("DELETE FROM programme WHERE playlistId = :playlistId AND source = :source")
    suspend fun deleteSource(playlistId: Long, source: String)

    /** XMLTV rows whose guide channel no mapping of [origin] references (provider-only channels). */
    @Query(
        """DELETE FROM programme WHERE playlistId = :playlistId AND source = 'XMLTV'
           AND channelKey NOT IN (SELECT channelKey FROM channel_epg_map WHERE playlistId = :playlistId AND origin = :origin)""",
    )
    suspend fun deleteXmltvNotMappedBy(playlistId: Long, origin: String)
}
