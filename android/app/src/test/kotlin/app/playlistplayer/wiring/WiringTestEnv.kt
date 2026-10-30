package app.playlistplayer.wiring

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.playlistplayer.AppContainer
import app.playlistplayer.FormFactor
import app.playlistplayer.core.consolidation.DeviceCapabilities
import app.playlistplayer.core.database.AppDatabase
import app.playlistplayer.core.database.entity.VodCategoryEntity
import app.playlistplayer.core.database.entity.VodStreamEntity
import app.playlistplayer.core.network.drive.DriveClientConfig
import app.playlistplayer.core.sync.secure.AesGcmValueCipher
import app.playlistplayer.core.sync.secure.SecureStore
import app.playlistplayer.feature.onboarding.OnboardingResult
import app.playlistplayer.feature.onboarding.SourceInput
import app.playlistplayer.feature.playlists.FakeScheduler
import app.playlistplayer.feature.playlists.Playlists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.io.File
import javax.crypto.KeyGenerator

/**
 * The real [AppContainer] wiring over an in-memory Room database, a software-key SecureStore and
 * a MockWebServer standing in for the Drive API. Synthetic data only: the playlist's stream URLs
 * point at `xtream.example.invalid` with placeholder credentials. Background work is replaced by
 * a recording scheduler (no WorkManager).
 */
class WiringTestEnv(tempDir: File) {
    val context: Context = ApplicationProvider.getApplicationContext()

    /** Synthetic Xtream-shaped playlist: three groups ("🇬🇧 UK", "⚽ Sports", "🎬 Movies"), nine channels. */
    var playlistBody: String = Playlists.xtream(9, FAKE_HOST)

    val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    !path.startsWith("/drive/v3/files/") -> MockResponse.Builder().code(404).build()
                    request.url.queryParameter("alt") == "media" -> MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "audio/x-mpegurl")
                        .body(playlistBody)
                        .build()
                    else -> MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body("""{"md5Checksum":"md5-${playlistBody.hashCode()}","version":"1","size":"${playlistBody.length}","modifiedTime":"2026-10-01T10:00:00.000Z"}""")
                        .build()
                }
            }
        }
        start()
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val db: AppDatabase = AppDatabase.createInMemory(context)
    val secureStore = SecureStore(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PreferenceDataStoreFactory.create(scope = ioScope, produceFile = { File(tempDir, "secure.preferences_pb") })
        } else {
            // Robolectric at API 25: DataStore commits with File.renameTo below API 26, which replaces
            // the target on Linux/Android but fails on a Windows host once the file exists. Keep the
            // API 25 wiring tests host-independent with an in-memory store (same SecureStore code).
            InMemoryPreferencesDataStore()
        },
        AesGcmValueCipher { key },
    )
    val scheduler = FakeScheduler()
    var cancelledAllWork = 0

    val container = AppContainer(
        context,
        AppContainer.Overrides(
            database = { db },
            secureStore = { secureStore },
            vaultCipher = { AesGcmValueCipher { key } },
            driveConfig = DriveClientConfig(
                apiKey = "test-key",
                packageName = "app.playlistplayer",
                certSha1 = "",
                apiBaseUrl = server.url("/"),
                fallbackBaseUrl = server.url("/fallback/"),
            ),
            scheduler = scheduler,
            cancelAllWork = { cancelledAllWork++ },
            deviceCapabilities = DeviceCapabilities.UNRESTRICTED,
            formFactor = FormFactor.Tv,
        ),
    )

    /** Onboards the synthetic playlist through the real onboarder and Drive client. */
    suspend fun onboard(name: String = "Home", xtream: Boolean = false): Long {
        val result = container.playlistOnboarder.addPlaylist(name, SourceInput.DriveId("FAKE_FILE_ID"), xtreamEnabled = xtream)
        check(result is OnboardingResult.Success) { "onboarding failed: $result" }
        return result.playlistId
    }

    /** Writes and activates a catalog generation with one movie category ("1") and a locked-to-be one ("12"). */
    suspend fun syncCatalog(playlistId: Long) {
        val catalog = container.catalogStore
        val generation = catalog.beginGeneration(playlistId)
        val writer = catalog.writer(playlistId, generation)
        writer.insertVodCategories(
            listOf(
                VodCategoryEntity(playlistId, generation, "1", "EN - Movies", null, 0),
                VodCategoryEntity(playlistId, generation, "12", "Late night", null, 1),
            ),
        )
        writer.insertVodStreams(
            listOf(
                VodStreamEntity(playlistId, generation, MOVIE_STREAM_ID, "EN - Test Movie (2020)", null, null, null, "1", "mkv", "1001", false, 0),
                VodStreamEntity(playlistId, generation, 502, "EN - Other Film (2019)", null, null, null, "12", "mp4", "1002", false, 1),
            ),
        )
        catalog.activateGeneration(playlistId, generation) { it }
    }

    fun close() {
        container.applicationScope.cancel()
        server.close()
        db.close()
        ioScope.cancel()
    }

    companion object {
        const val FAKE_HOST = "xtream.example.invalid:8080"
        const val MOVIE_STREAM_ID = 501L
    }
}

/** In-memory [DataStore] for host runs where the file-backed one cannot commit (see [WiringTestEnv.secureStore]). */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
        transform(state.value).toPreferences().also { state.value = it }
    }
}
