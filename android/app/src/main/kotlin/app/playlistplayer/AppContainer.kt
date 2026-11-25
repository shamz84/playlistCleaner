package app.playlistplayer

import app.playlistplayer.core.database.TrendingStore
import app.playlistplayer.core.network.tmdb.TmdbClient
import app.playlistplayer.core.network.tmdb.TmdbConfig
import app.playlistplayer.feature.vod.RoomTrendingCache
import app.playlistplayer.feature.vod.SharedPreferencesVodSortStore
import app.playlistplayer.feature.vod.TrendingRepository
import app.playlistplayer.feature.vod.RoomVodTitleIndex
import app.playlistplayer.feature.vod.VodCatalogSource
import app.playlistplayer.feature.vod.VodTitleIndex
import app.playlistplayer.feature.vod.VodTitleIndexer
import app.playlistplayer.core.database.VodTitleStore
import app.playlistplayer.core.sync.BackgroundWorkGate
import android.content.Context
import androidx.work.DelegatingWorkerFactory
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import app.playlistplayer.core.consolidation.DeviceCapabilities
import app.playlistplayer.core.database.AppDatabase
import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.database.UserDataStore
import app.playlistplayer.core.database.search.SearchStore
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
import app.playlistplayer.core.parsing.m3u.AccountExtractor
import app.playlistplayer.core.player.AndroidConnectivityMonitor
import app.playlistplayer.core.player.ConnectivityMonitor
import app.playlistplayer.core.player.DeviceCapabilitiesProbe
import app.playlistplayer.core.player.ExoPlaybackController
import app.playlistplayer.core.player.PlayerFactory
import app.playlistplayer.core.player.PlayerSettings
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
import app.playlistplayer.core.sync.epg.GuideFreshness
import app.playlistplayer.core.sync.epg.NowNextRepository
import app.playlistplayer.core.sync.platform.AndroidLogSink
import app.playlistplayer.core.sync.platform.AppSignature
import app.playlistplayer.core.sync.secure.AndroidKeystoreKeys
import app.playlistplayer.core.sync.secure.SecureStore
import app.playlistplayer.core.sync.secure.ValueCipher
import app.playlistplayer.feature.home.HomeViewModel
import app.playlistplayer.feature.library.RecentChannelsStore
import app.playlistplayer.feature.library.RoomFavouritesStore
import app.playlistplayer.feature.library.RoomLastChannelStore
import app.playlistplayer.feature.library.RoomPlaybackProgressStore
import app.playlistplayer.feature.library.RoomRecentChannelsStore
import app.playlistplayer.feature.library.RoomTitleOverrideStore
import app.playlistplayer.feature.library.RoomTitlePickStore
import app.playlistplayer.feature.library.TitleOverrideStore
import app.playlistplayer.feature.live.CatchupPlaybackController
import app.playlistplayer.feature.live.FavouritesRepository
import app.playlistplayer.feature.live.LastChannelStore
import app.playlistplayer.feature.live.LiveAccountStatusSource
import app.playlistplayer.feature.live.LiveChannelSource
import app.playlistplayer.feature.live.LivePlaybackCoordinator
import app.playlistplayer.feature.live.NowNextSource
import app.playlistplayer.feature.live.PlaybackPreferencesProvider
import app.playlistplayer.feature.live.ProgrammeLookup
import app.playlistplayer.feature.onboarding.PlaylistOnboarder
import app.playlistplayer.feature.playlists.DefaultPlaylistOnboarder
import app.playlistplayer.feature.playlists.PlaylistAuthorizer
import app.playlistplayer.feature.playlists.PlaylistRepository
import app.playlistplayer.feature.search.ContentVisibility
import app.playlistplayer.feature.search.SearchIndexer
import app.playlistplayer.feature.search.SearchPlaylist
import app.playlistplayer.feature.search.SearchRepository
import app.playlistplayer.feature.search.SearchViewModel
import app.playlistplayer.feature.settings.AllowedFormatsSource
import app.playlistplayer.feature.settings.EpgGuideSettingsStore
import app.playlistplayer.feature.settings.ParentalContentFilter
import app.playlistplayer.feature.settings.ParentalControl
import app.playlistplayer.feature.settings.ParentalPlaylistAuthorizer
import app.playlistplayer.feature.settings.PinPromptBroker
import app.playlistplayer.feature.settings.PreferencesRepository
import app.playlistplayer.feature.settings.SecretSlotPreferencesStorage
import app.playlistplayer.feature.settings.SecretSlotStore
import app.playlistplayer.feature.settings.SecureStoreSecretSlots
import app.playlistplayer.feature.settings.SettingsDependencies
import app.playlistplayer.feature.settings.SettingsViewModel
import app.playlistplayer.feature.settings.StoreContentGroupSource
import app.playlistplayer.feature.settings.XmltvOverrideTester
import app.playlistplayer.feature.vod.AccountProvider
import app.playlistplayer.feature.vod.CatalogStoreVodSource
import app.playlistplayer.feature.vod.FavouritesStore
import app.playlistplayer.feature.vod.MovieInfoLoader
import app.playlistplayer.feature.vod.PlaybackProgressRepository
import app.playlistplayer.feature.vod.PlaybackProgressStore
import app.playlistplayer.feature.vod.SeriesInfoLoader
import app.playlistplayer.feature.vod.TitlePickStore
import app.playlistplayer.feature.vod.PreferencesTrackPreferenceStore
import app.playlistplayer.feature.vod.TrackPreferenceStore
import app.playlistplayer.feature.vod.SkipSpanProvider
import app.playlistplayer.feature.vod.SkipSpanRepository
import app.playlistplayer.core.network.skip.IntroDbClient
import app.playlistplayer.feature.vod.VersionPreferencesProvider
import app.playlistplayer.feature.vod.VodCatalogRepository
import app.playlistplayer.feature.vod.VodPlaylist
import app.playlistplayer.wiring.CatalogLiveAccountStatusSource
import app.playlistplayer.wiring.HideAllVisibility
import app.playlistplayer.wiring.ParentalContentVisibility
import app.playlistplayer.wiring.PlaybackHolder
import app.playlistplayer.wiring.PlaylistAccountStatusChecker
import app.playlistplayer.wiring.RoomLiveBrowseSource
import app.playlistplayer.wiring.RoomLiveChannelSource
import app.playlistplayer.wiring.UnreadableSlots
import app.playlistplayer.wiring.gridSource
import app.playlistplayer.wiring.toStreamAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container (no Hilt). One instance lives in [PlaylistPlayerApp] and is shared
 * by every Activity and ViewModel factory; everything is a lazily created singleton.
 *
 * Start-up: [initialize] loads the parental state (U12) before any UI is shown (`MainActivity`
 * shows a loading state until [ready]); until then every content path hides everything (fail
 * closed). [startBackgroundWork] starts search indexing and the periodic refresh schedules.
 *
 * [overrides] replace platform pieces in tests (in-memory Room, software-key SecureStore, fake
 * Drive host, scheduler); production passes none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(context: Context, private val overrides: Overrides = Overrides()) {
    private val appContext: Context = context.applicationContext

    /**
     * Test seams. Every value defaults to the production implementation.
     *
     * @property scheduler replaces the WorkManager-backed [PlaylistOrchestrator] for onboarding,
     *   Settings and "Reset app" (startup scheduling then goes through it as well).
     * @property cancelAllWork replaces `WorkManager.cancelAllWork()` for "Reset app".
     */
    class Overrides(
        val database: (() -> AppDatabase)? = null,
        val secureStore: (() -> SecureStore)? = null,
        val vaultCipher: (() -> ValueCipher)? = null,
        val driveConfig: DriveClientConfig? = null,
        val scheduler: PlaylistSyncScheduler? = null,
        val cancelAllWork: (() -> Unit)? = null,
        val deviceCapabilities: DeviceCapabilities? = null,
        val formFactor: FormFactor? = null,
        val clock: () -> Long = System::currentTimeMillis,
    )

    /** The device's form factor never changes while the process is alive. */
    val formFactor: FormFactor by lazy { overrides.formFactor ?: FormFactor.detect(appContext) }

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

    val database: AppDatabase by lazy { overrides.database?.invoke() ?: AppDatabase.create(appContext) }

    val playlistStore: PlaylistStore by lazy { PlaylistStore(database) }

    /** Single instance per process (DataStore requirement). */
    val secureStore: SecureStore by lazy { overrides.secureStore?.invoke() ?: SecureStore.create(appContext) }

    val driveClient: DriveClient by lazy {
        DriveClient(
            okHttpClient,
            overrides.driveConfig ?: DriveClientConfig(
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
            cipher = overrides.vaultCipher?.invoke() ?: AndroidKeystoreKeys.cipher(),
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

    /**
     * Serialises the heavy background jobs (catalog sync, EPG import, title and search index
     * builds) on one background-priority thread, so they never overlap (U20, R34).
     */
    val backgroundWorkGate: BackgroundWorkGate by lazy { BackgroundWorkGate(BackgroundWorkGate.lowPriorityDispatcher()) }

    val catalogSyncer: CatalogSyncer by lazy {
        CatalogSyncer(playlistStore, catalogStore, xtreamAccounts, xtreamClient, logger = logger.withTag("CatalogSync"), gate = backgroundWorkGate)
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
            gate = backgroundWorkGate,
        )
    }

    /** Settings: set/clear the XMLTV override (re-imports immediately). */
    val epgOverrideSettings: EpgOverrideSettings by lazy { EpgOverrideSettings(secureStore, epgStore, { guideRefreshHours() }) { workManager } }

    /** Now/next per channel: Room first, then get_short_epg on demand (U9 channel list/player). */
    val nowNextRepository: NowNextRepository by lazy {
        NowNextRepository(playlistStore, catalogStore, epgStore, xtreamAccounts, xtreamClient, logger = logger.withTag("NowNext"))
    }

    /** Queues the first EPG import after the catalog sync (or playlist sync for override-only playlists). */
    val epgSyncChain: EpgSyncChain by lazy {
        EpgSyncChain(playlistStore, epgStore, { id -> secureStore.epgOverrideUrl(id) != null }, logger.withTag("EpgSync"), { guideRefreshHours() }) { workManager }
    }

    // ---- Multiple playlists (U15) ----

    /** Serial per-playlist pipeline (playlist → catalog → guide), one playlist step at a time. */
    val playlistOrchestrator: PlaylistOrchestrator by lazy { PlaylistOrchestrator(playlistStore, logger.withTag("Pipeline")) { workManager } }

    val playlistSyncScheduler: PlaylistSyncScheduler get() = overrides.scheduler ?: playlistOrchestrator

    private val pipelineSteps: PipelineSteps by lazy {
        PipelineSteps(
            playlistStore = playlistStore,
            playlistSyncer = { playlistSyncer },
            catalogSyncer = { catalogSyncer },
            epgSyncer = { epgSyncer },
            needsEpgImport = { id -> epgSyncChain.needsImport(id) },
            hasEpgOverride = { id -> secureStore.epgOverrideUrl(id) != null },
            scheduleDailyGuide = { id -> EpgSyncWorker.schedulePeriodic(workManager, id, guideRefreshHours()) },
            logger = logger.withTag("Pipeline"),
        )
    }

    /**
     * Gate for playlist edits/removal: the parental PIN prompt ([ParentalPlaylistAuthorizer] over
     * [pinPromptBroker]) once [initialize] ran; denies everything before that (fail closed).
     */
    @Volatile
    var playlistAuthorizer: PlaylistAuthorizer = { false }

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
            rescheduleGuides = { hours -> rescheduleGuides(hours) },
        )
    }

    /** The guide refresh interval chosen in Settings (default 24 h). */
    private suspend fun guideRefreshHours(): Long = preferencesRepository.current().sync.guideRefreshHours.toLong()

    /** Playlists with a guide source (provider guide or override URL) and channels to match. */
    private suspend fun guidePlaylistIds(): List<Long> = playlistStore.playlists()
        .filter { it.xtreamEnabled || secureStore.epgOverrideUrl(it.id) != null }
        .filter { playlistStore.syncState(it.id)?.activeGeneration != null }
        .map { it.id }

    private suspend fun rescheduleGuides(hours: Long) {
        if (overrides.scheduler != null) return
        guidePlaylistIds().forEach { id ->
            EpgSyncWorker.schedulePeriodic(workManager, id, hours, ExistingPeriodicWorkPolicy.UPDATE)
        }
    }

    private val guideFreshness: GuideFreshness by lazy {
        GuideFreshness(
            epgStore = epgStore,
            playlistIds = { guidePlaylistIds() },
            intervalHours = { guideRefreshHours() },
            isImportActive = { id -> EpgSyncWorker.isImportActive(workManager, id) || playlistOrchestrator.isGuideStepActive(id) },
            enqueueImport = { id -> EpgSyncWorker.enqueueImport(workManager, id) },
            logger = logger.withTag("EpgSync"),
        )
    }

    /**
     * While the app is on screen (call from `repeatOnLifecycle(STARTED)`): refreshes any guide that
     * is older than the Settings interval, now, every [GuideFreshness.CHECK_INTERVAL_MS] and when
     * the interval changes. Guides already importing are left alone.
     */
    suspend fun keepGuideFresh() {
        if (overrides.scheduler != null) return
        preferencesRepository.preferences.map { it.sync.guideRefreshHours }.distinctUntilChanged().collectLatest {
            while (true) {
                try {
                    guideFreshness.refreshOverdue()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.withTag("EpgSync").w("Guide freshness check failed", e)
                }
                delay(GuideFreshness.CHECK_INTERVAL_MS)
            }
        }
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
            saveEpgOverride = { id, url -> secureStore.putEpgOverrideUrl(id, url) },
        )
    }

    /** Emits whether at least one playlist exists (false shows first-run onboarding). */
    fun hasPlaylists(): Flow<Boolean> = playlistStore.observePlaylists().map { it.isNotEmpty() }.distinctUntilChanged()

    /** `{ AccountExtractor.fromUrl(it) != null }`: whether a pasted M3U URL is Xtream-shaped. */
    val isXtreamShaped: (String) -> Boolean = { url -> AccountExtractor.fromUrl(url) != null }

    // ---- Settings, preferences and parental control (U12) ----

    /** Encrypted generic slots (PIN hash, attempts, locked groups, preferences record). */
    val parentalSlots: SecretSlotStore by lazy { SecureStoreSecretSlots(secureStore) }

    /** App-scoped PIN prompt hand-off; the roots render its dialog globally (TV and phone). */
    val pinPromptBroker: PinPromptBroker = PinPromptBroker()

    val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(SecretSlotPreferencesStorage(parentalSlots)) }

    private val parentalDeferred = CompletableDeferred<ParentalControl>()
    private val mutableReady = MutableStateFlow(false)

    /** True once [initialize] loaded the parental state; the UI waits for it. */
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    /** The parental state; only valid once [ready] (see [awaitParentalControl]). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val parentalControl: ParentalControl
        get() = check(parentalDeferred.isCompleted) { "AppContainer.initialize() has not completed" }
            .let { parentalDeferred.getCompleted() }

    suspend fun awaitParentalControl(): ParentalControl = parentalDeferred.await()

    /** The single parental predicate every content path uses (R20). Valid once [ready]. */
    val parentalFilter: ParentalContentFilter by lazy { ParentalContentFilter(parentalControl) }

    val guideSettingsStore: EpgGuideSettingsStore by lazy { EpgGuideSettingsStore(epgOverrideSettings, epgStore) }

    val overrideTester: XmltvOverrideTester by lazy {
        XmltvOverrideTester(xmltvDownloader, AndroidXmlPullSource, XmltvOverrideTester.channelsFrom(catalogStore))
    }

    /** Settings for both form factors; build one per screen (it owns a `PlaylistsViewModel`). */
    fun settingsDependencies(): SettingsDependencies = SettingsDependencies(
        playlists = playlistRepository,
        preferences = preferencesRepository,
        parental = parentalControl,
        pinPrompts = pinPromptBroker,
        guide = guideSettingsStore,
        overrideTester = overrideTester,
        groups = StoreContentGroupSource(playlistStore, catalogStore),
        allowedFormats = AllowedFormatsSource { id -> catalogStore.accountStatus(id)?.allowedFormatList.orEmpty() },
        resetApp = { resetApp() },
    )

    fun settingsViewModel(): SettingsViewModel = SettingsViewModel(settingsDependencies())

    // ---- Search, favourites, recent and continue watching (U11) ----

    /** Favourites, progress, picks, last/recent channels and VOD grouping overrides (Room). */
    val userDataStore: UserDataStore by lazy { UserDataStore(database) }

    // ---- U18: TMDB trending and per-section sort ----

    /** Build-time TMDB key (`tmdb.apiKey`); empty hides Trending. Never logged. */
    private val tmdbApiKey: String = BuildConfig.TMDB_API_KEY

    val trendingRepository: TrendingRepository by lazy {
        val client = tmdbApiKey.takeIf { it.isNotBlank() }?.let { TmdbClient(okHttpClient, TmdbConfig(it)) }
        TrendingRepository(
            source = client,
            cache = RoomTrendingCache(TrendingStore(database)),
            logger = logger.withTag("Trending"),
            secrets = listOfNotNull(tmdbApiKey.takeIf { it.isNotBlank() }),
        )
    }

    val vodSortStore: SharedPreferencesVodSortStore by lazy { SharedPreferencesVodSortStore(appContext) }

    /** FTS5 trigram index over live entries and merged VOD titles. */
    val searchStore: SearchStore by lazy { SearchStore(database) }

    /**
     * Parental visibility for live browse, search, home rows, VOD categories and the guide. Hides
     * everything until [initialize] binds it to [parentalFilter]. Read on every use.
     */
    @Volatile
    var contentVisibility: ContentVisibility = HideAllVisibility

    /** Re-emits [ContentVisibility.changes] of whatever [contentVisibility] currently is. */
    private val visibilityBinding = MutableStateFlow<ContentVisibility>(HideAllVisibility)

    /**
     * Lock/unlock and locked-group edits (and the switch from "hide all" to the parental rules).
     * The rules' current state on collection is not a change, so it is skipped.
     */
    fun visibilityChanges(): Flow<Unit> = visibilityBinding.flatMapLatest { v -> v.changes }.drop(1)

    private val visibilityForFlows: ContentVisibility = object : ContentVisibility {
        override fun isLiveGroupHidden(playlistId: Long, groupOrdinal: Int, groupName: String) =
            contentVisibility.isLiveGroupHidden(playlistId, groupOrdinal, groupName)

        override fun isVodCategoryHidden(playlistId: Long, kind: app.playlistplayer.core.consolidation.VodKind, categoryId: String) =
            contentVisibility.isVodCategoryHidden(playlistId, kind, categoryId)

        override fun isTitleHidden(playlistId: Long, kind: app.playlistplayer.core.consolidation.VodKind, categoryIds: Collection<String>) =
            contentVisibility.isTitleHidden(playlistId, kind, categoryIds)

        override val changes: Flow<Unit> get() = visibilityChanges()
    }

    /** Visibility as the features take it: always the current rules, changes included. */
    val visibility: () -> ContentVisibility = { visibilityForFlows }

    val favouritesStore: FavouritesStore by lazy { RoomFavouritesStore(userDataStore) }

    val playbackProgressStore: PlaybackProgressStore by lazy { RoomPlaybackProgressStore(userDataStore) }

    val playbackProgressRepository: PlaybackProgressRepository by lazy { PlaybackProgressRepository(playbackProgressStore) }

    val titlePickStore: TitlePickStore by lazy { RoomTitlePickStore(userDataStore) }

    val recentChannelsStore: RecentChannelsStore by lazy { RoomRecentChannelsStore(userDataStore) }

    /** Setting the last channel (on every tune) also records it as a recent channel. */
    val lastChannelStore: LastChannelStore by lazy { RoomLastChannelStore(userDataStore, recentChannelsStore) }

    /** U5 split/merge overrides; an edit changes the stamp, so index maintenance regroups the playlist. */
    val titleOverrideStore: TitleOverrideStore by lazy { RoomTitleOverrideStore(userDataStore) }

    private val vodCatalogSource: VodCatalogSource by lazy {
        CatalogStoreVodSource(catalogStore) {
            playlistStore.playlists().filter { it.xtreamEnabled }.map { VodPlaylist(it.id, it.name) }
        }
    }

    /** Precomputed merged titles (U20), rebuilt in the background after each catalog sync. */
    val vodTitleIndex: VodTitleIndex by lazy { RoomVodTitleIndex(VodTitleStore(database)) }

    val vodTitleIndexer: VodTitleIndexer by lazy {
        VodTitleIndexer(
            source = vodCatalogSource,
            index = vodTitleIndex,
            overrides = { id -> titleOverrideStore.overrides(id) },
            overrideStamp = { id -> titleOverrideStore.stamp(id) },
            gate = backgroundWorkGate,
            logger = logger.withTag("TitleIndex"),
        )
    }

    /** Merged VOD titles (U10) of Xtream-enabled playlists from [vodTitleIndex], parental category filtering. */
    val vodCatalogRepository: VodCatalogRepository by lazy {
        VodCatalogRepository(
            source = vodCatalogSource,
            index = vodTitleIndex,
            isCategoryHidden = { id, kind, category -> contentVisibility.isVodCategoryHidden(id, kind, category) },
        )
    }

    /** Rebuilds the title and search indexes after generation flips; [startSearchIndexing] runs it. */
    val searchIndexer: SearchIndexer by lazy {
        SearchIndexer(
            playlistStore = playlistStore,
            searchStore = searchStore,
            titles = vodTitleIndex,
            titleIndexer = vodTitleIndexer,
            gate = backgroundWorkGate,
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
        FavouritesRepository(userDataStore, searchStore, visibility = visibility)
    }

    /** Re-runs Home's Continue watching when progress is saved or the VOD index is rebuilt. */
    fun homeChanges(): Flow<Unit> = merge(
        userDataStore.observeRecentProgress(1).map { },
        searchStore.observeVodIndex().map { },
    )

    fun homeViewModel(): HomeViewModel = HomeViewModel(
        continueWatching = { limit -> playbackProgressRepository.continueWatching(limit) },
        titles = { keys -> searchStore.vodTitles(keys) },
        favourites = favouritesRepository,
        recents = recentChannelsStore,
        liveBrowse = liveBrowseSource,
        visibility = visibility,
        changes = homeChanges(),
    )

    fun searchViewModel(): SearchViewModel = SearchViewModel(searchRepository, visibilityChanges())

    /** Starts index maintenance for the process lifetime; call once from `PlaylistPlayerApp.onCreate`. */
    fun startSearchIndexing(): Job = searchIndexer.start(applicationScope)

    // ---- Live, guide and catch-up (U8/U9) ----

    /** Decoder/display limits, probed once (version ranking caps). */
    val deviceCapabilities: DeviceCapabilities by lazy { overrides.deviceCapabilities ?: DeviceCapabilitiesProbe.probe(appContext) }

    val accountProvider: AccountProvider = AccountProvider { id -> secureStore.account(id)?.toStreamAccount() }

    val playbackPreferences: PlaybackPreferencesProvider = PlaybackPreferencesProvider { preferencesRepository.current().playback }

    val versionPreferences: VersionPreferencesProvider =
        VersionPreferencesProvider { preferencesRepository.current().content.versionPreferences(deviceCapabilities) }

    /** Audio/subtitle language for movies and episodes (Settings → Playback; in-player choices update it). */
    val trackPreferenceStore: TrackPreferenceStore by lazy { PreferencesTrackPreferenceStore(preferencesRepository) }

    /** Real intro/recap/credits spans from TheIntroDB, looked up by TMDB id (U-skip). */
    val skipSpanRepository: SkipSpanProvider by lazy { SkipSpanRepository(IntroDbClient(okHttpClient)) }

    val liveChannelSource: LiveChannelSource by lazy { RoomLiveChannelSource(playlistStore, catalogStore, streamUrlVault, visibility) }

    val liveAccountStatusSource: LiveAccountStatusSource by lazy { CatalogLiveAccountStatusSource(catalogStore) }

    val nowNextSource: NowNextSource = NowNextSource { id, ordinal -> nowNextRepository.nowNext(id, ordinal) }

    val epgGridSource: app.playlistplayer.feature.live.EpgGridSource by lazy { epgStore.gridSource() }

    val programmeLookup: ProgrammeLookup = ProgrammeLookup { id -> epgStore.programme(id) }

    /** Live browse (playlists → groups → channels), parental-hidden groups removed. */
    val liveBrowseSource: RoomLiveBrowseSource by lazy {
        RoomLiveBrowseSource(playlistStore, catalogStore, visibility, preferencesRepository.preferences.map { it.playlistOrder }.distinctUntilChanged())
    }

    val liveCoordinator: LivePlaybackCoordinator by lazy {
        LivePlaybackCoordinator(liveChannelSource, accountProvider, liveAccountStatusSource, playbackPreferences, versionPreferences, lastChannelStore, applicationScope)
    }

    val catchupController: CatchupPlaybackController by lazy {
        CatchupPlaybackController(
            accountProvider,
            liveAccountStatusSource,
            playbackPreferences,
            clock = overrides.clock,
            rebuildRestartSeeks = true,
        )
    }

    val movieInfoLoader: MovieInfoLoader by lazy { MovieInfoLoader.from(vodInfoRepository) }

    val seriesInfoLoader: SeriesInfoLoader by lazy { SeriesInfoLoader.from(seriesInfoRepository) }

    // ---- Player (U6) ----

    val connectivityMonitor: ConnectivityMonitor by lazy { AndroidConnectivityMonitor(appContext) }

    /** The player's 401/403/509 classification: `PlayableSource.accountKey` is the playlist id. */
    val playerAccountStatusChecker: app.playlistplayer.core.player.AccountStatusChecker by lazy {
        PlaylistAccountStatusChecker(accountStatusChecker)
    }

    /** Live channels tuned in this session (last-channel shortcut, U22). */
    val channelHistory = app.playlistplayer.feature.live.ChannelHistory()

    /** The single app-scoped player and controller (see [PlaybackHolder] for the lifecycle). */
    val playback: PlaybackHolder = PlaybackHolder(
        createPlayer = {
            val prefs = preferencesRepository.snapshot.value?.playback
            PlayerFactory(appContext, PlayerSettings(tunnelingEnabled = prefs?.tunneling == true), baseClient = okHttpClient).create()
        },
        createController = { player ->
            ExoPlaybackController(
                player,
                accountStatusChecker = playerAccountStatusChecker,
                connectivity = connectivityMonitor,
                diagnosticsSink = { logger.withTag("PlaybackDiag").i(it) },
            )
        },
    )

    // ---- Start-up ----

    /**
     * Loads the parental state (fails closed when its storage throws), binds the parental gate,
     * visibility and removal hook, and warms the preferences. Idempotent; `MainActivity` waits for
     * [ready] before building the UI.
     */
    suspend fun initialize() {
        if (parentalDeferred.isCompleted) return
        val parental = try {
            ParentalControl.load(parentalSlots)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.withTag("Startup").e("Parental state unreadable; failing closed", e)
            ParentalControl.load(UnreadableSlots(parentalSlots))
        }
        try {
            preferencesRepository.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.withTag("Startup").w("Preferences unreadable; using defaults", e)
        }
        val authorizer = ParentalPlaylistAuthorizer(parental, pinPromptBroker)
        playlistAuthorizer = { action -> authorizer(action) }
        playlistRemovedHook = { id -> parental.forgetPlaylist(id) }
        if (parentalDeferred.complete(parental)) {
            val rules = ParentalContentVisibility(parentalFilter)
            contentVisibility = rules
            visibilityBinding.value = rules
        }
        mutableReady.value = true
    }

    /** Runs [initialize] in the background (from `PlaylistPlayerApp.onCreate`). */
    fun initializeAsync(): Job = applicationScope.launch { initialize() }

    /**
     * Process-lifetime background work: search index maintenance, the periodic "refresh all
     * playlists" at the user's interval (re-scheduled by Settings when it changes), each
     * playlist's daily guide refresh, and dropping the live other-versions cache on generation
     * flips and visibility changes. Call once from `PlaylistPlayerApp.onCreate`.
     */
    fun startBackgroundWork() {
        startSearchIndexing()
        applicationScope.launch {
            try {
                val hours = preferencesRepository.current().sync.refreshIntervalHours
                playlistSyncScheduler.schedulePeriodic(hours.toLong())
                if (overrides.scheduler == null) {
                    val guideHours = guideRefreshHours()
                    playlistStore.playlists()
                        .filter { it.xtreamEnabled || secureStore.epgOverrideUrl(it.id) != null }
                        .forEach { EpgSyncWorker.schedulePeriodic(workManager, it.id, guideHours) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.withTag("Startup").w("Scheduling periodic refresh failed", e)
            }
        }
        applicationScope.launch {
            merge(generationChanges(), visibilityChanges()).collect {
                channelHistory.clearPrevious()
                liveCoordinator.invalidate()
            }
        }
        // Tunneling applies to the existing player without re-creating it.
        applicationScope.launch {
            preferencesRepository.preferences.map { it.playback.tunneling }.distinctUntilChanged().drop(1).collect { enabled ->
                withContext(Dispatchers.Main) { playback.playerOrNull?.setTunnelingEnabled(enabled) }
            }
        }
    }

    /** Emits when any playlist's active playlist or catalog generation changes. */
    private fun generationChanges(): Flow<Unit> =
        playlistStore.observePlaylists().flatMapLatest { playlists ->
            if (playlists.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    playlists.map { p ->
                        combine(playlistStore.observeSyncState(p.id), catalogStore.observeState(p.id)) { s, c ->
                            Triple(p.id, s?.activeGeneration, c?.activeGeneration)
                        }
                    },
                ) { it.toList() }
            }
        }.distinctUntilChanged().drop(1).map { }

    // ---- Removal and reset ----

    /** Extra removal cleanup: [initialize] binds it to `ParentalControl.forgetPlaylist`. */
    @Volatile
    var playlistRemovedHook: suspend (playlistId: Long) -> Unit = {}

    /**
     * Runs after a playlist's rows are deleted (`PlaylistStore.deletePlaylist` already removed its
     * personal rows and search index; clearing again is idempotent) and drops in-memory caches:
     * favourites, progress, picks, last/recent channels and overrides go with [UserDataStore.clearPlaylist].
     */
    private suspend fun onPlaylistRemoved(playlistId: Long) {
        userDataStore.clearPlaylist(playlistId)
        searchStore.clearPlaylist(playlistId)
        liveCoordinator.invalidate(playlistId)
        vodSortStore.forgetPlaylist(playlistId)
        playlistRemovedHook(playlistId)
    }

    /**
     * "Reset app" data wipe (the forgotten-PIN path, so no PIN is asked): cancels all work,
     * removes every playlist (rows, vault, workers, personal rows), clears caches and every
     * secret. The caller resets [ParentalControl] and [PreferencesRepository] afterwards (see
     * `SettingsViewModel`, or [resetEverything]).
     */
    suspend fun resetApp() {
        val scheduler = playlistSyncScheduler
        playlistStore.playlists().forEach { playlist ->
            playlistRepository.remove(playlist.id, authorizer = { true })
        }
        (overrides.cancelAllWork ?: { workManager.cancelAllWork() }).invoke()
        liveCoordinator.invalidate()
        vodSortStore.clear()
        secureStore.clear()
        try {
            scheduler.schedulePeriodic(app.playlistplayer.feature.settings.SyncPreferences().refreshIntervalHours.toLong())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.withTag("Reset").w("Re-scheduling the periodic refresh failed", e)
        }
    }

    /** [resetApp] plus parental and preferences reset: the "Reset required" screen's action. */
    suspend fun resetEverything() {
        resetApp()
        awaitParentalControl().resetAll()
        preferencesRepository.reset()
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
