package app.playlistplayer

import android.content.Context
import androidx.work.DelegatingWorkerFactory
import androidx.work.WorkManager
import app.playlistplayer.core.database.AppDatabase
import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.network.drive.DriveClient
import app.playlistplayer.core.network.drive.DriveClientConfig
import app.playlistplayer.core.network.source.DefaultPlaylistSourceFactory
import app.playlistplayer.core.network.source.PlaylistSourceFactory
import app.playlistplayer.core.network.xtream.AccountStatusChecker
import app.playlistplayer.core.network.xtream.XtreamAccountStatusChecker
import app.playlistplayer.core.network.xtream.XtreamClient
import app.playlistplayer.core.sync.PlaylistRegistry
import app.playlistplayer.core.sync.PlaylistSyncer
import app.playlistplayer.core.sync.SyncWorkerFactory
import app.playlistplayer.core.sync.catalog.CatalogSyncChain
import app.playlistplayer.core.sync.catalog.CatalogSyncWorkerFactory
import app.playlistplayer.core.sync.catalog.CatalogSyncer
import app.playlistplayer.core.sync.catalog.SeriesInfoRepository
import app.playlistplayer.core.sync.catalog.VodInfoRepository
import app.playlistplayer.core.sync.catalog.toAccountStatusEntity
import app.playlistplayer.core.sync.platform.AndroidLogSink
import app.playlistplayer.core.sync.platform.AppSignature
import app.playlistplayer.core.sync.secure.SecureStore
import okhttp3.OkHttpClient
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

    val playlistSourceFactory: PlaylistSourceFactory by lazy { DefaultPlaylistSourceFactory(driveClient) }

    val playlistRegistry: PlaylistRegistry by lazy { PlaylistRegistry(playlistStore, secureStore) }

    val playlistSyncer: PlaylistSyncer by lazy {
        PlaylistSyncer(playlistStore, secureStore, playlistSourceFactory, logger = logger.withTag("PlaylistSync"))
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

    /** WorkManager worker factory; later units add their factories to this delegate. */
    val workerFactory: DelegatingWorkerFactory by lazy {
        DelegatingWorkerFactory().apply {
            // A successful playlist sync chains the catalog sync for Xtream-enabled playlists.
            addFactory(SyncWorkerFactory(CatalogSyncChain(playlistStore, logger.withTag("CatalogSync")) { workManager }) { playlistSyncer })
            addFactory(CatalogSyncWorkerFactory { catalogSyncer })
        }
    }

    val workManager: WorkManager get() = WorkManager.getInstance(appContext)
}
