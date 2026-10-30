package app.playlistplayer.feature.playlists

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.playlistplayer.core.database.AppDatabase
import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.EpgStore
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.network.source.M3uUrlSource
import app.playlistplayer.core.network.source.PlaylistSource
import app.playlistplayer.core.network.source.PlaylistSourceFactory
import app.playlistplayer.core.network.source.SourceError
import app.playlistplayer.core.network.source.SourceFetchResult
import app.playlistplayer.core.network.source.SourceVersion
import app.playlistplayer.core.sync.PipelineHandle
import app.playlistplayer.core.sync.PipelineStep
import app.playlistplayer.core.sync.PlaylistRegistry
import app.playlistplayer.core.sync.PlaylistSyncScheduler
import app.playlistplayer.core.sync.PlaylistSyncer
import app.playlistplayer.core.sync.StepState
import app.playlistplayer.core.sync.StreamUrlVault
import app.playlistplayer.core.sync.secure.AesGcmValueCipher
import app.playlistplayer.core.sync.secure.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/** Synthetic playlists only: fake hosts (`*.example.invalid` or the local MockWebServer) and placeholder credentials. */
object Playlists {
    /** [count] Xtream-shaped live entries in three groups. */
    fun xtream(count: Int, host: String, user: String = "user", pass: String = "pass"): String = buildString {
        append("#EXTM3U\n")
        val groups = listOf("🇬🇧 UK", "⚽ Sports", "🎬 Movies")
        for (i in 0 until count) {
            append("#EXTINF:-1 tvg-id=\"ch$i.uk\" group-title=\"${groups[i * groups.size / count]}\",Channel $i\n")
            append("http://$host/live/$user/$pass/${10_000 + i}.ts\n")
        }
    }

    /** Generic (non-Xtream) entries. */
    val generic: String = buildString {
        append("#EXTM3U\n")
        append("#EXTINF:-1 tvg-id=\"news1\" group-title=\"News\",News One\nhttps://cdn.example.invalid/hls/news1.m3u8?token=tok-1\n")
        append("#EXTINF:-1 tvg-id=\"news2\" group-title=\"News\",News Two\nhttps://cdn.example.invalid/hls/news2.m3u8?token=tok-2\n")
        append("#EXTINF:-1 group-title=\"Radio\",Radio One\nhttps://radio.example.invalid/one/playlist.m3u8\n")
    }
}

/** Scriptable Drive stand-in. [hold] suspends after the body was consumed (cancellation tests). */
class FakeDriveSource(var body: String) : PlaylistSource {
    var error: SourceError? = null
    var md5: String = "md5-1"
    var hold: (suspend () -> Unit)? = null

    override suspend fun <T> fetch(previous: SourceVersion?, consume: suspend (InputStream) -> T): SourceFetchResult<T> {
        error?.let { return SourceFetchResult.Failed(it) }
        val version = SourceVersion(md5 = md5)
        if (previous?.md5 == md5) return SourceFetchResult.Unchanged(version)
        val value = consume(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
        hold?.invoke()
        return SourceFetchResult.Fetched(value, version)
    }
}

/** Records scheduling calls; each handle's steps are driven by the test through [states]. */
class FakeScheduler : PlaylistSyncScheduler {
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    val states = HashMap<UUID, MutableStateFlow<StepState>>()
    val handles = ArrayList<PipelineHandle>()

    private fun handle(playlistId: Long, steps: List<PipelineStep>): PipelineHandle {
        val h = PipelineHandle(playlistId, steps.associateWith { UUID.randomUUID() })
        h.steps.values.forEach { states[it] = MutableStateFlow(StepState.Pending) }
        handles += h
        return h
    }

    fun set(handle: PipelineHandle, step: PipelineStep, state: StepState) {
        states.getValue(handle.steps.getValue(step)).value = state
    }

    override suspend fun refreshNow(playlistId: Long): PipelineHandle {
        calls += "refresh:$playlistId"
        return handle(playlistId, PipelineStep.entries)
    }

    override suspend fun refreshAll(): List<PipelineHandle> {
        calls += "refreshAll"
        return emptyList()
    }

    override suspend fun syncCatalogAndGuide(playlistId: Long, forceCatalog: Boolean, forceGuide: Boolean): PipelineHandle {
        calls += "catalogAndGuide:$playlistId:$forceCatalog:$forceGuide"
        return handle(playlistId, listOf(PipelineStep.CATALOG, PipelineStep.GUIDE))
    }

    override suspend fun cancelCatalogAndGuide(playlistId: Long) {
        calls += "cancelCatalogAndGuide:$playlistId"
    }

    override suspend fun cancelAll(playlistId: Long) {
        calls += "cancelAll:$playlistId"
    }

    override suspend fun schedulePeriodic(intervalHours: Long) {
        calls += "periodic:$intervalHours"
    }

    override fun observe(handle: PipelineHandle): Flow<Map<PipelineStep, StepState>> {
        val flows = handle.steps.map { (step, id) -> states.getValue(id).map { step to it } }
        return combine(flows) { it.toMap() }
    }
}

/** Real Room (in memory), SecureStore (software key), vault and syncer; fake Drive and scheduler. */
class PlaylistTestEnv(tempDir: File) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = AppDatabase.createInMemory(context)
    val store = PlaylistStore(db)
    val catalog = CatalogStore(db)
    val epg = EpgStore(db)
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val secureStore = SecureStore(
        PreferenceDataStoreFactory.create(scope = ioScope, produceFile = { File(tempDir, "secure.preferences_pb") }),
        AesGcmValueCipher { key },
    )
    val vaultDir = File(tempDir, "vault")
    val vault = StreamUrlVault(vaultDir, AesGcmValueCipher { key }, { store.syncState(it)?.activeGeneration })
    var now = 1_000_000L
    val registry = PlaylistRegistry(store, secureStore, clock = { now })
    val server = MockWebServer().apply { start() }
    val http: OkHttpClient = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    val drives = HashMap<String, FakeDriveSource>()
    val syncer = PlaylistSyncer(
        store,
        secureStore,
        PlaylistSourceFactory { reference ->
            when (reference) {
                is SourceReference.Drive -> drives.getOrPut(reference.fileId) { FakeDriveSource(Playlists.xtream(9, "xtream.example.invalid:8080")) }
                is SourceReference.M3uUrl -> M3uUrlSource(http, reference, Dispatchers.IO)
            }
        },
        clock = { now },
        urlVault = vault,
    )
    val scheduler = FakeScheduler()
    val removedHook = ArrayList<Long>()

    fun repository(authorize: PlaylistAuthorizer = { true }) = PlaylistRepository(
        store, catalog, epg, registry, secureStore, vault, scheduler, authorize, onRemoved = { removedHook += it },
    )

    fun onboarder() = DefaultPlaylistOnboarder(registry, syncer, store, vault, scheduler, scope)

    fun url(path: String): String = server.url(path).toString()

    val serverHost: String get() = "${server.hostName}:${server.port}"

    fun m3u(body: String, code: Int = 200, contentType: String = "audio/x-mpegurl") =
        MockResponse.Builder().code(code).setHeader("Content-Type", contentType).body(body).build()

    fun close() {
        server.close()
        db.close()
        scope.cancel()
        ioScope.cancel()
    }
}
