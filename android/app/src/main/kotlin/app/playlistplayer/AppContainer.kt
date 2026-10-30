package app.playlistplayer

import android.content.Context
import androidx.work.DelegatingWorkerFactory
import androidx.work.WorkManager
import app.playlistplayer.core.database.AppDatabase
import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.database.UserDataStore
import app.playlistplayer.core.database.search.SearchStore
import app.playlistplayer.feature.library.RecentChannelsStore
import app.playlistplayer.feature.library.RoomFavouritesStore
import app.playlistplayer.feature.library.RoomLastChannelStore
import app.playlistplayer.feature.library.RoomPlaybackProgressStore
import app.playlistplayer.feature.library.RoomRecentChannelsStore
import app.playlistplayer.feature.library.RoomTitleOverrideStore
import app.playlistplayer.feature.library.RoomTitlePickStore
import app.playlistplayer.feature.library.TitleOverrideStore
import app.playlistplayer.feature.live.FavouritesRepository
import app.playlistplayer.feature.live.LastChannelStore
import app.playlistplayer.feature.search.ContentVisibility
import app.playlistplayer.feature.search.SearchIndexer
import app.playlistplayer.feature.search.SearchPlaylist
import app.playlistplayer.feature.search.SearchRepository
import app.playlistplayer.feature.vod.CatalogStoreVodSource
import app.playlistplayer.feature.vod.FavouritesStore
import app.playlistplayer.feature.vod.PlaybackProgressRepository
import app.playlistplayer.feature.vod.PlaybackProgressStore
import app.playlistplayer.feature.vod.TitlePickStore
import app.playlistplayer.feature.vod.VodCatalogRepository
import app.playlistplayer.feature.vod.VodPlaylist
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.network.drive.DriveClient
import app.playlistplayer.core.network.drive.DriveClientConfig
import app.playlistplayer.core.network.epg.XmltvDownloader
import app.playlistplayer.core.network.source.DefaultPlaylistSourceFactory
import app.playlistplayer.core.network.source.PlaylistSourceFactory
import app.playlistplayer.core.network.xtream.AccountStatusChecker
import app.playlistplayer.core.network.xtream.XtreamAccountStatusChecker
import app.playlistplayer.core.network.xtream.XtreamClient
import app.playlistplayer.core.sync.PipelineSteps
import app.playlistplayer.core.sync.PipelineWorkerFactory
import app.playlistplayer.core.sync.PlaylistOrchestrator
import app.playlistplayer.core.sync.PlaylistRegistry
import app.playlistplayer.core.sync.PlaylistSyncScheduler
import app.playlistplayer.core.sync.PlaylistSyncer
import app.playlistplayer.core.sync.StreamUrlVault
import app.playlistplayer.core.sync.SyncWorkerFactory
import app.playlistplayer.core.sync.catalog.CatalogSyncChain
import app.playlistplayer.core.sync.catalog.CatalogSyncWorkerFactory
import app.playlistplayer.core.sync.catalog.CatalogSyncer
import app.playlistplayer.core.sync.catalog.SeriesInfoRepository
import app.playlistplayer.core.sync.catalog.VodInfoRepository
import app.playlistplayer.core.sync.catalog.toAccountStatusEntity
import app.playlistplayer.core.sync.epg.AndroidXmlPullSource
import app.playlistplayer.core.sync.epg.CompositePlaylistSyncFollowUp
import app.playlistplayer.core.sync.epg.EpgOverrideSettings
import app.playlistplayer.core.sync.epg.EpgSyncChain
import app.playlistplayer.core.sync.epg.EpgSyncWorker
import app.playlistplayer.core.sync.epg.EpgSyncWorkerFactory
import app.playlistplayer.core.sync.epg.EpgSyncer
import app.playlistplayer.core.sync.epg.NowNextRepository
import app.playlistplayer.core.sync.platform.AndroidLogSink
import app.playlistplayer.core.sync.platform.AppSignature
import app.playlistplayer.core.sync.secure.AndroidKeystoreKeys
import app.playlistplayer.core.sync.secure.SecureStore
import app.playlistplayer.feature.onboarding.PlaylistOnboarder
import app.playlistplayer.feature.playlists.DefaultPlaylistOnboarder
import app.playlistplayer.feature.playlists.PlaylistAuthorizer
import app.playlistplayer.feature.playlists.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container (no Hilt). One instance lives in [PlaylistPlayerApp] and is
 * shared by every Activity and ViewModel factory. Later units add repositories, the player
 * factory, sync scheduling and secure storage here as lazily created singletons.
 */
class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    /** The device's form factor never changes while the process is alive. */
    val formFactor: FormFactor by lazy { FormFactor.detect(appContext) }

    /** Restricted Drive API key injected at build time from local.properties/env; may be empty. */
    val driveApiKey: String = BuildConfig.DRIVE_API_KEY

    /** The only logger: every line and Throwable is redacted before reaching logcat. */
    val logger: RedactingLogger by lazy { RedactingLogger(AndroidLogSink) }

    /** Shared HTTP client. No logging interceptor: redirect chains carry credential/token paths. */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }

    val playlistStore: PlaylistStore by lazy { PlaylistStore(database) }

    /** Single instance per process (DataStore requirement). */
    val secureStore: SecureStore by lazy { SecureStore.create(appContext) }

    val driveClient: DriveClient by lazy {
        DriveClient(
            okHttpClient,
            DriveClientConfig(
                apiKey = driveApiKey,
                packageName = appContext.packageName,
                certSha1 = AppSignature.certSha1(appContext),
            ),
        )
    }

    /** Process-lifetime scope for work that outlives a screen (e.g. onboarding catalog/guide progress). */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Drive file ids via [driveClient]; M3U URLs (U15) via a conditional GET on the shared client. */
    val playlistSourceFactory: PlaylistSourceFactory by lazy { DefaultPlaylistSourceFactory(driveClient, okHttpClient) }

    /**
     * Encrypted stream URLs of non-Xtream playlist entries (Room never stores URLs). App-private,
     * excluded from backup; data keys are wrapped by the Keystore key.
     */
    val streamUrlVault: StreamUrlVault by lazy {
        StreamUrlVault(
            directory = File(appContext.noBackupFilesDir, StreamUrlVault.DIRECTORY_NAME),
            cipher = AndroidKeystoreKeys.cipher(),
            activeGeneration = { id -> playlistStore.syncState(id)?.activeGeneration },
        )
    }

    val playlistRegistry: PlaylistRegistry by lazy { PlaylistRegistry(playlistStore, secureStore) }

    val playlistSyncer: PlaylistSyncer by lazy {
        PlaylistSyncer(
            playlistStore,
            secureStore,
            playlistSourceFactory,
            logger = logger.withTag("PlaylistSync"),
            urlVault = streamUrlVault,
        )
    }

    // ---- Xtream (U4) ----

    /** get_series can take ~7 s to first byte on a cold server cache: allow a longer read timeout. */
    val xtreamClient: XtreamClient by lazy {
        XtreamClient(okHttpClient.newBuilder().readTimeout(60, TimeUnit.SECONDS).build())
    }

    val catalogStore: CatalogStore by lazy { CatalogStore(database) }

    private val xtreamAccounts: suspend (Long) -> XtreamAccount? = { id -> secureStore.account(id) }

    val catalogSyncer: CatalogSyncer by lazy {
        CatalogSyncer(playlistStore, catalogStore, xtreamAccounts, xtreamClient, logger = logger.withTag("CatalogSync"))
    }

    /** On-demand get_series_info (cached in Room) for the series screen (U10). */
    val seriesInfoRepository: SeriesInfoRepository by lazy {
        SeriesInfoRepository(catalogStore, xtreamAccounts, xtreamClient, logger = logger.withTag("SeriesInfo"))
    }

    /** On-demand get_vod_info details (cached in Room) for the movie detail screen (U10). */
    val vodInfoRepository: VodInfoRepository by lazy {
        VodInfoRepository(catalogStore, xtreamAccounts, xtreamClient, logger = logger.withTag("VodInfo"))
    }

    /** Live account check for the player's 401/403/509 classification (U6); persists the fresh status. */
    val accountStatusChecker: AccountStatusChecker by lazy {
        XtreamAccountStatusChecker(
            xtreamClient,
            accountLookup = { id -> if (playlistStore.playlist(id)?.xtreamEnabled == true) secureStore.account(id) else null },
            onInfo = { id, info -> catalogStore.putAccountStatus(info.toAccountStatusEntity(id, System.currentTimeMillis())) },
        )
    }

    // ---- EPG (U7) ----

    val epgStore: EpgStore by lazy { EpgStore(database) }

    /** Streams xmltv.php (16.6 MB gzip / 86 MB XML) or an override URL; generous read timeout. */
    val xmltvDownloader: XmltvDownloader by lazy {
        XmltvDownloader(okHttpClient.newBuilder().readTimeout(120, TimeUnit.SECONDS).build())
    }

    /** XMLTV import (provider or override + provider fallback) and archive backfill. */
    val epgSyncer: EpgSyncer by lazy {
        EpgSyncer(
            playlistStore = playlistStore,
            catalogStore = catalogStore,
            epgStore = epgStore,
            accounts = xtreamAccounts,
            overrideUrls = { id -> secureStore.epgOverrideUrl(id) },
            downloader = xmltvDownloader,
            xtreamClient = xtreamClient,
            pullFactory = AndroidXmlPullSource,
            logger = logger.withTag("EpgSync"),
        )
    }

    /** Settings: set/clear the XMLTV override (re-imports immediately). */
    val epgOverrideSettings: EpgOverrideSettings by lazy { EpgOverrideSettings(secureStore, epgStore) { workManager } }

    /** Now/next per channel: Room first, then get_short_epg on demand (U9 channel list/player). */
    val nowNextRepository: NowNextRepository by lazy {
        NowNextRepository(playlistStore, catalogStore, epgStore, xtreamAccounts, xtreamClient, logger = logger.withTag("NowNext"))
    }

    /** Queues the first EPG import after the catalog sync (or playlist sync for override-only playlists). */
    val epgSyncChain: EpgSyncChain by lazy {
        EpgSyncChain(playlistStore, epgStore, { id -> secureStore.epgOverrideUrl(id) != null }, logger.withTag("EpgSync")) { workManager }
    }

    // ---- Multiple playlists (U15) ----

    /** Serial per-playlist pipeline (playlist → catalog → guide), one playlist step at a time. */
    val playlistOrchestrator: PlaylistOrchestrator by lazy { PlaylistOrchestrator(playlistStore, logger.withTag("Pipeline")) { workManager } }

    val playlistSyncScheduler: PlaylistSyncScheduler get() = playlistOrchestrator

    private val pipelineSteps: PipelineSteps by lazy {
        PipelineSteps(
            playlistStore = playlistStore,
            playlistSyncer = { playlistSyncer },
            catalogSyncer = { catalogSyncer },
            epgSyncer = { epgSyncer },
            needsEpgImport = { id -> epgSyncChain.needsImport(id) },
            hasEpgOverride = { id -> secureStore.epgOverrideUrl(id) != null },
            scheduleDailyGuide = { id -> EpgSyncWorker.schedulePeriodic(workManager, id) },
            logger = logger.withTag("Pipeline"),
        )
    }

    /**
     * Gate for playlist edits/removal; the settings unit replaces it with the parental-PIN prompt
     * (`ParentalControl.authorize`). Allows everything until then.
     */
    @Volatile
    var playlistAuthorizer: PlaylistAuthorizer = { true }

    /** Settings → Playlists. [playlistAuthorizer] is read on every action. */
    val playlistRepository: PlaylistRepository by lazy {
        PlaylistRepository(
            playlistStore = playlistStore,
            catalogStore = catalogStore,
            epgStore = epgStore,
            registry = playlistRegistry,
            secureStore = secureStore,
            vault = streamUrlVault,
            scheduler = playlistSyncScheduler,
            authorize = { action -> playlistAuthorizer(action) },
            onRemoved = { id -> onPlaylistRemoved(id) },
            logger = logger.withTag("Playlists"),
        )
    }

    /** First run and "Add playlist" (U9 onboarding screen). */
    val playlistOnboarder: PlaylistOnboarder by lazy {
        DefaultPlaylistOnboarder(
            registry = playlistRegistry,
            syncer = playlistSyncer,
            playlistStore = playlistStore,
            vault = streamUrlVault,
            scheduler = playlistSyncScheduler,
            scope = applicationScope,
            logger = logger.withTag("Onboarding"),
        )
    }

    // ---- Search, favourites, recent and continue watching (U11) ----

    /** Favourites, progress, picks, last/recent channels and VOD grouping overrides (Room). */
    val userDataStore: UserDataStore by lazy { UserDataStore(database) }

    /** FTS5 trigram index over live entries and merged VOD titles. */
    val searchStore: SearchStore by lazy { SearchStore(database) }

    /**
     * Parental visibility for search, home rows and VOD categories; the settings unit replaces it
     * with a `ParentalControl`-backed implementation. Read on every use.
     */
    @Volatile
    var contentVisibility: ContentVisibility = ContentVisibility.ALL_VISIBLE

    val favouritesStore: FavouritesStore by lazy { RoomFavouritesStore(userDataStore) }

    val playbackProgressStore: PlaybackProgressStore by lazy { RoomPlaybackProgressStore(userDataStore) }

    val playbackProgressRepository: PlaybackProgressRepository by lazy { PlaybackProgressRepository(playbackProgressStore) }

    val titlePickStore: TitlePickStore by lazy { RoomTitlePickStore(userDataStore) }

    val recentChannelsStore: RecentChannelsStore by lazy { RoomRecentChannelsStore(userDataStore) }

    /** Setting the last channel (on every tune) also records it as a recent channel. */
    val lastChannelStore: LastChannelStore by lazy { RoomLastChannelStore(userDataStore, recentChannelsStore) }

    /** U5 split/merge overrides; an edit drops the playlist's cached groupings. */
    val titleOverrideStore: TitleOverrideStore by lazy {
        RoomTitleOverrideStore(userDataStore, onChanged = { id -> vodCatalogRepository.invalidate(id) })
    }

    /** Merged VOD titles (U10) with persisted overrides and parental category filtering. */
    val vodCatalogRepository: VodCatalogRepository by lazy {
        VodCatalogRepository(
            source = CatalogStoreVodSource(catalogStore) {
                playlistStore.playlists().filter { it.xtreamEnabled }.map { VodPlaylist(it.id, it.name) }
            },
            overrides = { id -> titleOverrideStore.overrides(id) },
            isCategoryHidden = { id, kind, category -> contentVisibility.isVodCategoryHidden(id, kind, category) },
        )
    }

    /** Rebuilds the search index after generation flips; [startSearchIndexing] runs it. */
    val searchIndexer: SearchIndexer by lazy {
        SearchIndexer(
            playlistStore = playlistStore,
            catalogStore = catalogStore,
            searchStore = searchStore,
            vodRepository = vodCatalogRepository,
            overrideStamp = { id -> titleOverrideStore.stamp(id) },
            logger = logger.withTag("Search"),
        )
    }

    val searchRepository: SearchRepository by lazy {
        SearchRepository(
            store = searchStore,
            playlists = { playlistStore.playlists().map { SearchPlaylist(it.id, it.name) } },
            visibility = { contentVisibility },
            indexer = searchIndexer,
        )
    }

    val favouritesRepository: FavouritesRepository by lazy {
        FavouritesRepository(userDataStore, searchStore, visibility = { contentVisibility })
    }

    /** Re-runs Home's Continue watching when progress is saved or the VOD index is rebuilt. */
    fun homeChanges(): Flow<Unit> = merge(
        userDataStore.observeRecentProgress(1).map { },
        searchStore.observeVodIndex().map { },
    )

    /** Starts index maintenance for the process lifetime; call once from `PlaylistPlayerApp.onCreate`. */
    fun startSearchIndexing(): Job = searchIndexer.start(applicationScope)

    /** Extra removal cleanup set by the settings unit (e.g. `ParentalControl.forgetPlaylist`). */
    @Volatile
    var playlistRemovedHook: suspend (playlistId: Long) -> Unit = {}

    /**
     * Runs after a playlist's rows are deleted (`PlaylistStore.deletePlaylist` already removed its
     * personal rows and search index; clearing again is idempotent) and drops in-memory caches.
     */
    private suspend fun onPlaylistRemoved(playlistId: Long) {
        userDataStore.clearPlaylist(playlistId)
        searchStore.clearPlaylist(playlistId)
        vodCatalogRepository.invalidate(playlistId)
        playlistRemovedHook(playlistId)
    }

    /** WorkManager worker factory; later units add their factories to this delegate. */
    val workerFactory: DelegatingWorkerFactory by lazy {
        DelegatingWorkerFactory().apply {
            // A successful playlist sync chains the catalog sync for Xtream-enabled playlists, and
            // the EPG import for override-only playlists.
            val catalogChain = CatalogSyncChain(playlistStore, logger.withTag("CatalogSync")) { workManager }
            addFactory(SyncWorkerFactory(CompositePlaylistSyncFollowUp(catalogChain, epgSyncChain)) { playlistSyncer })
            // A successful catalog sync chains the EPG import when it is stale or never ran.
            addFactory(CatalogSyncWorkerFactory(epgSyncChain) { catalogSyncer })
            addFactory(EpgSyncWorkerFactory { epgSyncer })
            // U15 serial pipeline steps and the periodic "refresh all playlists" worker.
            addFactory(PipelineWorkerFactory({ pipelineSteps }, { playlistOrchestrator }))
        }
    }

    val workManager: WorkManager get() = WorkManager.getInstance(appContext)
}
