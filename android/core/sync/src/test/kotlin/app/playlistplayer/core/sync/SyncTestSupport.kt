package app.playlistplayer.core.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.playlistplayer.core.database.AppDatabase
import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.network.source.PlaylistSource
import app.playlistplayer.core.network.source.PlaylistSourceFactory
import app.playlistplayer.core.network.source.SourceError
import app.playlistplayer.core.network.source.SourceFetchResult
import app.playlistplayer.core.network.source.SourceVersion
import app.playlistplayer.core.sync.secure.AesGcmValueCipher
import app.playlistplayer.core.sync.secure.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.crypto.KeyGenerator

/** Synthetic playlists. Fake hosts only (`*.example.invalid`); never real provider data. */
object TestPlaylists {
    fun fixture(): String =
        checkNotNull(TestPlaylists::class.java.getResourceAsStream("/playlist_sample.m3u")).use { it.readBytes().toString(Charsets.UTF_8) }

    fun build(
        count: Int,
        user: String = "user",
        pass: String = "pass",
        host: String = "example.invalid:8080",
        groups: List<String> = listOf("🇬🇧 UK", "⚽ Sports", "🎬 Movies"),
    ): String = buildString {
        append("#EXTM3U\n")
        for (i in 0 until count) {
            val group = groups[i * groups.size / count]
            append("#EXTINF:0 CUID=\"$i\" tvg-name=\"Channel $i\" tvg-id=\"ch$i.uk\" group-title=\"$group\",Channel $i\n")
            append("http://$host/live/$user/$pass/${10_000 + i}.ts\n")
        }
    }
}

/** In-memory Room + a real SecureStore (software AES key) in a temp DataStore file. */
class SyncHarness(private val tempDir: File) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = AppDatabase.createInMemory(context)
    val store = PlaylistStore(db)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val dataStoreFile = File(tempDir, "secure_test.preferences_pb")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val secureStore = SecureStore(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { dataStoreFile }),
        AesGcmValueCipher { key },
    )
    val registry = PlaylistRegistry(store, secureStore, clock = { now })
    var now: Long = 1_000_000L
    val vaultDir = File(tempDir, "vault")
    val vault = StreamUrlVault(vaultDir, AesGcmValueCipher { key }, { store.syncState(it)?.activeGeneration }, chunkSize = 4)

    fun syncer(factory: PlaylistSourceFactory, batchSize: Int = 4) =
        PlaylistSyncer(store, secureStore, factory, clock = { now }, batchSize = batchSize, urlVault = vault)

    fun close() {
        db.close()
        scope.cancel()
    }
}

/** Scriptable in-memory source. */
class FakeSource : PlaylistSource {
    var body: String = TestPlaylists.fixture()
    var version: SourceVersion = SourceVersion(md5 = "md5-1", version = "1", modifiedTime = 500L)
    var error: SourceError? = null

    /** When set, the stream throws after this many bytes (connection drop mid-download). */
    var failAfterBytes: Int? = null
    var downloads: Int = 0
        private set

    override suspend fun <T> fetch(previous: SourceVersion?, consume: suspend (InputStream) -> T): SourceFetchResult<T> {
        error?.let { return SourceFetchResult.Failed(it) }
        if (previous?.md5 != null && previous.md5 == version.md5) return SourceFetchResult.Unchanged(version)
        downloads++
        val bytes = body.toByteArray(Charsets.UTF_8)
        val limit = failAfterBytes
        val stream: InputStream = if (limit == null) ByteArrayInputStream(bytes) else DroppingInputStream(bytes, limit)
        return try {
            SourceFetchResult.Fetched(consume(stream), version)
        } catch (e: IOException) {
            SourceFetchResult.Failed(SourceError.Network("Network error", e))
        }
    }
}

private class DroppingInputStream(private val bytes: ByteArray, private val limit: Int) : InputStream() {
    private var position = 0

    override fun read(): Int {
        if (position >= limit) throw IOException("Connection reset")
        if (position >= bytes.size) return -1
        return bytes[position++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position >= limit) throw IOException("Connection reset")
        if (position >= bytes.size) return -1
        val n = minOf(len, limit - position, bytes.size - position)
        System.arraycopy(bytes, position, b, off, n)
        position += n
        return n
    }
}
