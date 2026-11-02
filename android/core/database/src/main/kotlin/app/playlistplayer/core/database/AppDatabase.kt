package app.playlistplayer.core.database

import android.content.Context
import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.playlistplayer.core.database.dao.AccountStatusDao
import app.playlistplayer.core.database.dao.CatalogCacheDao
import app.playlistplayer.core.database.dao.CatalogDao
import app.playlistplayer.core.database.dao.CatalogSyncStateDao
import app.playlistplayer.core.database.dao.ChannelDao
import app.playlistplayer.core.database.dao.ChannelEpgMapDao
import app.playlistplayer.core.database.dao.EpgSourceStateDao
import app.playlistplayer.core.database.dao.PlaylistDao
import app.playlistplayer.core.database.dao.ProgrammeDao
import app.playlistplayer.core.database.dao.SyncStateDao
import app.playlistplayer.core.database.dao.TrendingDao
import app.playlistplayer.core.database.dao.UserDataDao
import app.playlistplayer.core.database.dao.VodTitleDao
import app.playlistplayer.core.database.entity.AccountStatusEntity
import app.playlistplayer.core.database.entity.CatalogSyncStateEntity
import app.playlistplayer.core.database.entity.ChannelEntity
import app.playlistplayer.core.database.entity.ChannelEpgMapEntity
import app.playlistplayer.core.database.entity.ChannelGroupEntity
import app.playlistplayer.core.database.entity.EpgSourceStateEntity
import app.playlistplayer.core.database.entity.EpisodeEntity
import app.playlistplayer.core.database.entity.LiveStreamInfoEntity
import app.playlistplayer.core.database.entity.PlaylistEntity
import app.playlistplayer.core.database.entity.ProgrammeEntity
import app.playlistplayer.core.database.entity.SeasonEntity
import app.playlistplayer.core.database.entity.SeriesCategoryEntity
import app.playlistplayer.core.database.entity.SeriesEntity
import app.playlistplayer.core.database.entity.SeriesInfoCacheEntity
import app.playlistplayer.core.database.entity.SyncStateEntity
import app.playlistplayer.core.database.entity.VodCategoryEntity
import app.playlistplayer.core.database.entity.VodInfoCacheEntity
import app.playlistplayer.core.database.entity.VodStreamEntity
import app.playlistplayer.core.database.entity.VodTitleEntity
import app.playlistplayer.core.database.entity.VodTitleItemEntity
import app.playlistplayer.core.database.entity.VodTitleStateEntity
import app.playlistplayer.core.database.entity.FavouriteEntity
import app.playlistplayer.core.database.entity.LastChannelEntity
import app.playlistplayer.core.database.entity.PlaybackProgressEntity
import app.playlistplayer.core.database.entity.RecentChannelEntity
import app.playlistplayer.core.database.entity.TitleOverrideEntity
import app.playlistplayer.core.database.entity.TitlePickEntity
import app.playlistplayer.core.database.entity.TrendingStateEntity
import app.playlistplayer.core.database.entity.TrendingTitleEntity
import app.playlistplayer.core.database.search.SearchDao
import app.playlistplayer.core.database.search.SearchFtsTriggers
import app.playlistplayer.core.database.search.SearchIndexStateEntity
import app.playlistplayer.core.database.search.SearchLiveEntity
import app.playlistplayer.core.database.search.SearchLiveFts
import app.playlistplayer.core.database.search.SearchVodEntity
import app.playlistplayer.core.database.search.SearchVodFts
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Single source of truth. Uses the bundled SQLite driver so FTS5 (trigram) behaves the same on
 * every device regardless of the platform SQLite version.
 *
 * Invariant: no table stores stream URLs or credentials; only stream ids and attributes.
 */
@Database(
    entities = [
        PlaylistEntity::class,
        ChannelGroupEntity::class,
        ChannelEntity::class,
        SyncStateEntity::class,
        // U4: Xtream catalog
        CatalogSyncStateEntity::class,
        AccountStatusEntity::class,
        LiveStreamInfoEntity::class,
        VodCategoryEntity::class,
        VodStreamEntity::class,
        SeriesCategoryEntity::class,
        SeriesEntity::class,
        SeriesInfoCacheEntity::class,
        SeasonEntity::class,
        EpisodeEntity::class,
        VodInfoCacheEntity::class,
        // U7: EPG
        ProgrammeEntity::class,
        ChannelEpgMapEntity::class,
        EpgSourceStateEntity::class,
        // U11: personal data
        FavouriteEntity::class,
        PlaybackProgressEntity::class,
        TitlePickEntity::class,
        LastChannelEntity::class,
        RecentChannelEntity::class,
        TitleOverrideEntity::class,
        // U11: search index (FTS5 trigram, external content)
        SearchLiveEntity::class,
        SearchLiveFts::class,
        SearchVodEntity::class,
        SearchVodFts::class,
        SearchIndexStateEntity::class,
        // U18: TMDB trending cache
        TrendingTitleEntity::class,
        TrendingStateEntity::class,
        // U20: precomputed merged VOD titles
        VodTitleStateEntity::class,
        VodTitleEntity::class,
        VodTitleItemEntity::class,
    ],
    version = 6,
    exportSchema = true,
    // Additive only: 1 -> 2 adds the catalog tables, 2 -> 3 the EPG tables, 3 -> 4 the personal
    // data and search index tables (the empty index is built by the app's SearchIndexer), 4 -> 5
    // the TMDB trending cache, 5 -> 6 the precomputed title index (empty; the app builds it in the
    // background from the active catalog).
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = AppDatabase.Migration3To4::class),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun catalogSyncStateDao(): CatalogSyncStateDao
    abstract fun accountStatusDao(): AccountStatusDao
    abstract fun catalogDao(): CatalogDao
    abstract fun catalogCacheDao(): CatalogCacheDao
    abstract fun programmeDao(): ProgrammeDao
    abstract fun channelEpgMapDao(): ChannelEpgMapDao
    abstract fun epgSourceStateDao(): EpgSourceStateDao
    abstract fun userDataDao(): UserDataDao
    abstract fun searchDao(): SearchDao
    abstract fun trendingDao(): TrendingDao
    abstract fun vodTitleDao(): VodTitleDao

    /** Adds the FTS content-sync triggers that AutoMigration leaves out (see [SearchFtsTriggers]). */
    class Migration3To4 : AutoMigrationSpec {
        override suspend fun onPostMigrate(connection: SQLiteConnection) {
            SearchFtsTriggers.ALL.forEach { connection.execSQL(it) }
        }
    }

    companion object {
        const val FILE_NAME: String = "playlist_player.db"

        fun create(
            context: Context,
            driver: SQLiteDriver = BundledSQLiteDriver(),
            queryContext: CoroutineContext = Dispatchers.IO,
        ): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, FILE_NAME)
                .setDriver(driver)
                .setQueryCoroutineContext(queryContext)
                .build()

        fun createInMemory(
            context: Context,
            driver: SQLiteDriver = BundledSQLiteDriver(),
            queryContext: CoroutineContext = Dispatchers.IO,
        ): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
                .setDriver(driver)
                .setQueryCoroutineContext(queryContext)
                .build()
    }
}
