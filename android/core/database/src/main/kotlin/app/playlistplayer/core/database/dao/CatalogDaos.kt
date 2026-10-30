package app.playlistplayer.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import app.playlistplayer.core.database.entity.AccountStatusEntity
import app.playlistplayer.core.database.entity.CatalogSyncStateEntity
import app.playlistplayer.core.database.entity.ChannelLiveInfo
import app.playlistplayer.core.database.entity.EpisodeEntity
import app.playlistplayer.core.database.entity.LiveStreamInfoEntity
import app.playlistplayer.core.database.entity.SeasonEntity
import app.playlistplayer.core.database.entity.SeriesCategoryEntity
import app.playlistplayer.core.database.entity.SeriesEntity
import app.playlistplayer.core.database.entity.SeriesInfoCacheEntity
import app.playlistplayer.core.database.entity.VodCategoryEntity
import app.playlistplayer.core.database.entity.VodInfoCacheEntity
import app.playlistplayer.core.database.entity.VodStreamEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogSyncStateDao {
    @Query("SELECT * FROM catalog_sync_state WHERE playlistId = :playlistId")
    suspend fun get(playlistId: Long): CatalogSyncStateEntity?

    @Query("SELECT * FROM catalog_sync_state WHERE playlistId = :playlistId")
    fun observe(playlistId: Long): Flow<CatalogSyncStateEntity?>

    @Upsert
    suspend fun upsert(state: CatalogSyncStateEntity)

    @Query("DELETE FROM catalog_sync_state WHERE playlistId = :playlistId")
    suspend fun delete(playlistId: Long)
}

@Dao
interface AccountStatusDao {
    @Query("SELECT * FROM account_status WHERE playlistId = :playlistId")
    suspend fun get(playlistId: Long): AccountStatusEntity?

    @Query("SELECT * FROM account_status WHERE playlistId = :playlistId")
    fun observe(playlistId: Long): Flow<AccountStatusEntity?>

    @Query("SELECT * FROM account_status")
    fun observeAll(): Flow<List<AccountStatusEntity>>

    @Upsert
    suspend fun upsert(status: AccountStatusEntity)

    @Query("DELETE FROM account_status WHERE playlistId = :playlistId")
    suspend fun delete(playlistId: Long)
}

/**
 * Generational catalog tables. Reads join `catalog_sync_state.activeGeneration`, so a generation
 * that is being written (or was abandoned) is never visible.
 */
@Dao
interface CatalogDao {
    // ---- writes ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLiveInfo(rows: List<LiveStreamInfoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVodCategories(rows: List<VodCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVodStreams(rows: List<VodStreamEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesCategories(rows: List<SeriesCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(rows: List<SeriesEntity>)

    // ---- generation cleanup ----

    @Query("DELETE FROM live_stream_info WHERE playlistId = :playlistId AND generation = :generation")
    suspend fun deleteLiveInfoGeneration(playlistId: Long, generation: Long)

    @Query("DELETE FROM vod_category WHERE playlistId = :playlistId AND generation = :generation")
    suspend fun deleteVodCategoryGeneration(playlistId: Long, generation: Long)

    @Query("DELETE FROM vod_stream WHERE playlistId = :playlistId AND generation = :generation")
    suspend fun deleteVodStreamGeneration(playlistId: Long, generation: Long)

    @Query("DELETE FROM series_category WHERE playlistId = :playlistId AND generation = :generation")
    suspend fun deleteSeriesCategoryGeneration(playlistId: Long, generation: Long)

    @Query("DELETE FROM series WHERE playlistId = :playlistId AND generation = :generation")
    suspend fun deleteSeriesGeneration(playlistId: Long, generation: Long)

    @Query("DELETE FROM live_stream_info WHERE playlistId = :playlistId AND (:keep IS NULL OR generation != :keep)")
    suspend fun deleteLiveInfoExcept(playlistId: Long, keep: Long?)

    @Query("DELETE FROM vod_category WHERE playlistId = :playlistId AND (:keep IS NULL OR generation != :keep)")
    suspend fun deleteVodCategoriesExcept(playlistId: Long, keep: Long?)

    @Query("DELETE FROM vod_stream WHERE playlistId = :playlistId AND (:keep IS NULL OR generation != :keep)")
    suspend fun deleteVodStreamsExcept(playlistId: Long, keep: Long?)

    @Query("DELETE FROM series_category WHERE playlistId = :playlistId AND (:keep IS NULL OR generation != :keep)")
    suspend fun deleteSeriesCategoriesExcept(playlistId: Long, keep: Long?)

    @Query("DELETE FROM series WHERE playlistId = :playlistId AND (:keep IS NULL OR generation != :keep)")
    suspend fun deleteSeriesExcept(playlistId: Long, keep: Long?)

    /** Distinct generations with VOD rows (diagnostics/tests). */
    @Query("SELECT DISTINCT generation FROM vod_stream WHERE playlistId = :playlistId ORDER BY generation")
    suspend fun vodGenerationsPresent(playlistId: Long): List<Long>

    @Query(
        """SELECT DISTINCT generation FROM (
             SELECT generation FROM live_stream_info WHERE playlistId = :playlistId
             UNION SELECT generation FROM vod_category WHERE playlistId = :playlistId
             UNION SELECT generation FROM vod_stream WHERE playlistId = :playlistId
             UNION SELECT generation FROM series_category WHERE playlistId = :playlistId
             UNION SELECT generation FROM series WHERE playlistId = :playlistId
           ) ORDER BY generation""",
    )
    suspend fun generationsPresent(playlistId: Long): List<Long>

    // ---- live info ----

    /** Distinct stream ids of the playlist's active channel generation (the live-info join keys). */
    @Query(
        """SELECT DISTINCT c.streamId FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           WHERE c.playlistId = :playlistId AND c.streamId IS NOT NULL""",
    )
    suspend fun playlistStreamIds(playlistId: Long): List<Long>

    @Query(
        """SELECT i.* FROM live_stream_info i
           JOIN catalog_sync_state s ON s.playlistId = i.playlistId AND s.activeGeneration = i.generation
           WHERE i.playlistId = :playlistId AND i.streamId = :streamId""",
    )
    suspend fun liveInfo(playlistId: Long, streamId: Long): LiveStreamInfoEntity?

    @Query(
        """SELECT i.* FROM live_stream_info i
           JOIN catalog_sync_state s ON s.playlistId = i.playlistId AND s.activeGeneration = i.generation
           WHERE i.playlistId = :playlistId AND i.tvArchive = 1 ORDER BY i.streamId""",
    )
    suspend fun archiveStreams(playlistId: Long): List<LiveStreamInfoEntity>

    @Query(
        """SELECT c.playlistId, c.ordinal, c.name, c.streamId, c.tvgId, c.catchupDays, c.isHeader,
                  cs.activeGeneration AS catalogGeneration, i.tvArchive, i.archiveDays, i.epgChannelId
           FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           LEFT JOIN catalog_sync_state cs ON cs.playlistId = c.playlistId
           LEFT JOIN live_stream_info i ON i.playlistId = c.playlistId AND i.generation = cs.activeGeneration
                AND i.streamId = c.streamId
           WHERE c.playlistId = :playlistId
           ORDER BY c.ordinal""",
    )
    suspend fun channelsWithLiveInfo(playlistId: Long): List<ChannelLiveInfo>

    @Query(
        """SELECT c.playlistId, c.ordinal, c.name, c.streamId, c.tvgId, c.catchupDays, c.isHeader,
                  cs.activeGeneration AS catalogGeneration, i.tvArchive, i.archiveDays, i.epgChannelId
           FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           LEFT JOIN catalog_sync_state cs ON cs.playlistId = c.playlistId
           LEFT JOIN live_stream_info i ON i.playlistId = c.playlistId AND i.generation = cs.activeGeneration
                AND i.streamId = c.streamId
           WHERE c.playlistId = :playlistId AND c.ordinal = :ordinal""",
    )
    suspend fun channelWithLiveInfo(playlistId: Long, ordinal: Int): ChannelLiveInfo?

    @Query(
        """SELECT c.playlistId, c.ordinal, c.name, c.streamId, c.tvgId, c.catchupDays, c.isHeader,
                  cs.activeGeneration AS catalogGeneration, i.tvArchive, i.archiveDays, i.epgChannelId
           FROM channel c
           JOIN sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           LEFT JOIN catalog_sync_state cs ON cs.playlistId = c.playlistId
           LEFT JOIN live_stream_info i ON i.playlistId = c.playlistId AND i.generation = cs.activeGeneration
                AND i.streamId = c.streamId
           WHERE c.playlistId = :playlistId AND c.groupOrdinal = :groupOrdinal
           ORDER BY c.ordinal""",
    )
    suspend fun channelsWithLiveInfoInGroup(playlistId: Long, groupOrdinal: Int): List<ChannelLiveInfo>

    // ---- VOD ----

    @Query(
        """SELECT v.* FROM vod_category v
           JOIN catalog_sync_state s ON s.playlistId = v.playlistId AND s.activeGeneration = v.generation
           WHERE v.playlistId = :playlistId ORDER BY v.ordinal""",
    )
    fun observeVodCategories(playlistId: Long): Flow<List<VodCategoryEntity>>

    @Query(
        """SELECT v.* FROM vod_category v
           JOIN catalog_sync_state s ON s.playlistId = v.playlistId AND s.activeGeneration = v.generation
           WHERE v.playlistId = :playlistId ORDER BY v.ordinal""",
    )
    suspend fun vodCategories(playlistId: Long): List<VodCategoryEntity>

    @Query(
        """SELECT v.* FROM vod_stream v
           JOIN catalog_sync_state s ON s.playlistId = v.playlistId AND s.activeGeneration = v.generation
           WHERE v.playlistId = :playlistId AND v.categoryId = :categoryId
           ORDER BY v.providerOrder LIMIT :limit OFFSET :offset""",
    )
    suspend fun vodStreamsInCategory(playlistId: Long, categoryId: String, limit: Int, offset: Int): List<VodStreamEntity>

    @Query(
        """SELECT v.* FROM vod_stream v
           JOIN catalog_sync_state s ON s.playlistId = v.playlistId AND s.activeGeneration = v.generation
           WHERE v.playlistId = :playlistId AND v.streamId = :streamId""",
    )
    suspend fun vodStream(playlistId: Long, streamId: Long): VodStreamEntity?

    @Query(
        """SELECT v.* FROM vod_stream v
           JOIN catalog_sync_state s ON s.playlistId = v.playlistId AND s.activeGeneration = v.generation
           WHERE v.playlistId = :playlistId AND v.tmdb = :tmdb ORDER BY v.providerOrder""",
    )
    suspend fun vodStreamsByTmdb(playlistId: Long, tmdb: String): List<VodStreamEntity>

    /** Keyset page over the whole active VOD list in provider order (for consolidation). */
    @Query(
        """SELECT v.* FROM vod_stream v
           JOIN catalog_sync_state s ON s.playlistId = v.playlistId AND s.activeGeneration = v.generation
           WHERE v.playlistId = :playlistId AND v.providerOrder > :afterOrder
           ORDER BY v.providerOrder LIMIT :limit""",
    )
    suspend fun vodStreamsPage(playlistId: Long, afterOrder: Int, limit: Int): List<VodStreamEntity>

    @Query(
        """SELECT COUNT(*) FROM vod_stream v
           JOIN catalog_sync_state s ON s.playlistId = v.playlistId AND s.activeGeneration = v.generation
           WHERE v.playlistId = :playlistId""",
    )
    suspend fun countVodStreams(playlistId: Long): Int

    // ---- series ----

    @Query(
        """SELECT c.* FROM series_category c
           JOIN catalog_sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           WHERE c.playlistId = :playlistId ORDER BY c.ordinal""",
    )
    fun observeSeriesCategories(playlistId: Long): Flow<List<SeriesCategoryEntity>>

    @Query(
        """SELECT c.* FROM series_category c
           JOIN catalog_sync_state s ON s.playlistId = c.playlistId AND s.activeGeneration = c.generation
           WHERE c.playlistId = :playlistId ORDER BY c.ordinal""",
    )
    suspend fun seriesCategories(playlistId: Long): List<SeriesCategoryEntity>

    @Query(
        """SELECT r.* FROM series r
           JOIN catalog_sync_state s ON s.playlistId = r.playlistId AND s.activeGeneration = r.generation
           WHERE r.playlistId = :playlistId AND r.categoryId = :categoryId
           ORDER BY r.providerOrder LIMIT :limit OFFSET :offset""",
    )
    suspend fun seriesInCategory(playlistId: Long, categoryId: String, limit: Int, offset: Int): List<SeriesEntity>

    @Query(
        """SELECT r.* FROM series r
           JOIN catalog_sync_state s ON s.playlistId = r.playlistId AND s.activeGeneration = r.generation
           WHERE r.playlistId = :playlistId AND r.seriesId = :seriesId""",
    )
    suspend fun series(playlistId: Long, seriesId: Long): SeriesEntity?

    @Query(
        """SELECT r.* FROM series r
           JOIN catalog_sync_state s ON s.playlistId = r.playlistId AND s.activeGeneration = r.generation
           WHERE r.playlistId = :playlistId AND r.providerOrder > :afterOrder
           ORDER BY r.providerOrder LIMIT :limit""",
    )
    suspend fun seriesPage(playlistId: Long, afterOrder: Int, limit: Int): List<SeriesEntity>

    @Query(
        """SELECT COUNT(*) FROM series r
           JOIN catalog_sync_state s ON s.playlistId = r.playlistId AND s.activeGeneration = r.generation
           WHERE r.playlistId = :playlistId""",
    )
    suspend fun countSeries(playlistId: Long): Int

    // ---- scoped delete ----

    @Query("DELETE FROM live_stream_info WHERE playlistId = :playlistId")
    suspend fun deleteAllLiveInfo(playlistId: Long)

    @Query("DELETE FROM vod_category WHERE playlistId = :playlistId")
    suspend fun deleteAllVodCategories(playlistId: Long)

    @Query("DELETE FROM vod_stream WHERE playlistId = :playlistId")
    suspend fun deleteAllVodStreams(playlistId: Long)

    @Query("DELETE FROM series_category WHERE playlistId = :playlistId")
    suspend fun deleteAllSeriesCategories(playlistId: Long)

    @Query("DELETE FROM series WHERE playlistId = :playlistId")
    suspend fun deleteAllSeries(playlistId: Long)
}

/** On-demand caches: series info (+ seasons, episodes) and VOD info. */
@Dao
interface CatalogCacheDao {
    @Query("SELECT * FROM series_info_cache WHERE playlistId = :playlistId AND seriesId = :seriesId")
    suspend fun seriesInfo(playlistId: Long, seriesId: Long): SeriesInfoCacheEntity?

    @Query("SELECT * FROM season WHERE playlistId = :playlistId AND seriesId = :seriesId ORDER BY seasonNumber")
    suspend fun seasons(playlistId: Long, seriesId: Long): List<SeasonEntity>

    @Query("SELECT * FROM episode WHERE playlistId = :playlistId AND seriesId = :seriesId ORDER BY sortOrder")
    suspend fun episodes(playlistId: Long, seriesId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episode WHERE playlistId = :playlistId AND seriesId = :seriesId ORDER BY sortOrder")
    fun observeEpisodes(playlistId: Long, seriesId: Long): Flow<List<EpisodeEntity>>

    @Upsert
    suspend fun upsertSeriesInfo(info: SeriesInfoCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeasons(rows: List<SeasonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(rows: List<EpisodeEntity>)

    @Query("DELETE FROM season WHERE playlistId = :playlistId AND seriesId = :seriesId")
    suspend fun deleteSeasons(playlistId: Long, seriesId: Long)

    @Query("DELETE FROM episode WHERE playlistId = :playlistId AND seriesId = :seriesId")
    suspend fun deleteEpisodes(playlistId: Long, seriesId: Long)

    @Query("SELECT * FROM vod_info_cache WHERE playlistId = :playlistId AND streamId = :streamId")
    suspend fun vodInfo(playlistId: Long, streamId: Long): VodInfoCacheEntity?

    @Upsert
    suspend fun upsertVodInfo(info: VodInfoCacheEntity)

    @Query("DELETE FROM series_info_cache WHERE playlistId = :playlistId")
    suspend fun deleteAllSeriesInfo(playlistId: Long)

    @Query("DELETE FROM season WHERE playlistId = :playlistId")
    suspend fun deleteAllSeasons(playlistId: Long)

    @Query("DELETE FROM episode WHERE playlistId = :playlistId")
    suspend fun deleteAllEpisodes(playlistId: Long)

    @Query("DELETE FROM vod_info_cache WHERE playlistId = :playlistId")
    suspend fun deleteAllVodInfo(playlistId: Long)
}
