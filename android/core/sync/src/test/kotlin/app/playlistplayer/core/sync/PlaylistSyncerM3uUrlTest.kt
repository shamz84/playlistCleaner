package app.playlistplayer.core.sync

import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.model.XtreamAccount
import app.playlistplayer.core.network.source.M3uUrlSource
import app.playlistplayer.core.network.source.PlaylistSourceFactory
import app.playlistplayer.core.network.source.SourceError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/** U15: M3U URL playlists next to a Drive playlist. Fake hosts and credentials only. */
@RunWith(RobolectricTestRunner::class)
class PlaylistSyncerM3uUrlTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var h: SyncHarness
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val drive = FakeSource()
    private lateinit var syncer: PlaylistSyncer

    private val genericPlaylist = buildString {
        append("#EXTM3U\n")
        append("#EXTINF:-1 group-title=\"News\",### NEWS ###\nhttp://cdn.example.invalid/hls/none.m3u8\n")
        append("#EXTINF:-1 tvg-id=\"news1\" group-title=\"News\",News One\nhttps://cdn.example.invalid/hls/news1.m3u8?token=tok-111\n")
        append("#EXTINF:-1 tvg-id=\"news2\" group-title=\"News\",News Two\nhttps://cdn.example.invalid/hls/news2.m3u8?token=tok-222\n")
        append("#EXTINF:-1 group-title=\"Music\",Music One\nhttps://radio.example.invalid/music1/playlist.m3u8\n")
    }

    @Before
    fun setUp() {
        h = SyncHarness(temp.root)
        server = MockWebServer().apply { start() }
        val factory = PlaylistSourceFactory { reference ->
            when (reference) {
                is SourceReference.Drive -> drive
                is SourceReference.M3uUrl -> M3uUrlSource(http, reference, Dispatchers.IO)
            }
        }
        syncer = h.syncer(factory)
    }

    @After
    fun tearDown() {
        server.close()
        h.close()
    }

    private fun ok(body: String, etag: String? = "\"e1\"") = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "audio/x-mpegurl")
        .apply { etag?.let { setHeader("ETag", it) } }
        .body(body)
        .build()

    private fun url(path: String) = server.url(path).toString()

    @Test
    fun secondPlaylistByUrlHasItsOwnGroupsAndLeavesTheFirstUnchanged() = runBlocking<Unit> {
        val first = h.registry.add("Drive list", SourceReference.Drive("FILE_ID"))
        assertThat(syncer.sync(first)).isInstanceOf(SyncOutcome.Updated::class.java)
        val firstGroups = h.store.groups(first)
        val firstChannels = h.store.channels(first)
        val firstState = h.store.syncState(first)!!

        server.enqueue(ok(genericPlaylist))
        val second = h.registry.add("Web list", SourceReference.M3uUrl(url("/lists/generic.m3u")), xtreamEnabled = false)
        assertThat(syncer.sync(second)).isInstanceOf(SyncOutcome.Updated::class.java)

        assertThat(h.store.groups(second).map { it.name }).containsExactly("News", "Music").inOrder()
        assertThat(h.store.groups(second).all { it.playlistId == second }).isTrue()
        assertThat(h.store.groups(first)).isEqualTo(firstGroups)
        assertThat(h.store.channels(first)).isEqualTo(firstChannels)
        assertThat(h.store.syncState(first)).isEqualTo(firstState)
        assertThat(h.store.playlists().map { it.name }).containsExactly("Drive list", "Web list").inOrder()
    }

    @Test
    fun notModifiedSkipsParsing() = runBlocking<Unit> {
        server.enqueue(ok(genericPlaylist))
        val id = h.registry.add("Web", SourceReference.M3uUrl(url("/list.m3u")), xtreamEnabled = false)
        syncer.sync(id)
        server.takeRequest()
        val before = h.store.syncState(id)!!
        assertThat(before.sourceEtag).isEqualTo("\"e1\"")

        server.enqueue(MockResponse.Builder().code(304).build())
        h.now += 60_000
        assertThat(syncer.sync(id)).isEqualTo(SyncOutcome.Unchanged)

        assertThat(server.takeRequest().headers["If-None-Match"]).isEqualTo("\"e1\"")
        val after = h.store.syncState(id)!!
        // No generation was even allocated: the body was never parsed.
        assertThat(after.lastGeneration).isEqualTo(before.lastGeneration)
        assertThat(after.activeGeneration).isEqualTo(before.activeGeneration)
        assertThat(after.lastSuccessAt).isEqualTo(h.now)
        assertThat(after.contentUpdatedAt).isEqualTo(before.contentUpdatedAt)
    }

    @Test
    fun identicalBodyWithoutValidatorsIsUnchangedAndItsGenerationDiscarded() = runBlocking<Unit> {
        server.enqueue(ok(genericPlaylist, etag = null))
        val id = h.registry.add("Web", SourceReference.M3uUrl(url("/list.m3u")), xtreamEnabled = false)
        syncer.sync(id)
        val active = h.store.syncState(id)!!.activeGeneration!!

        server.enqueue(ok(genericPlaylist, etag = null))
        assertThat(syncer.sync(id)).isEqualTo(SyncOutcome.Unchanged)

        assertThat(h.store.syncState(id)!!.activeGeneration).isEqualTo(active)
        assertThat(h.store.generationsPresent(id)).containsExactly(active)
        assertThat(h.vaultDir.resolve("p$id").list()!!.toList()).containsExactly("g$active.vault")
    }

    @Test
    fun getPhpUrlDerivesTheAccountAndKeepsXtreamOn() = runBlocking<Unit> {
        // Entries point at a CDN (not Xtream-shaped); the get.php URL still names the account.
        server.enqueue(ok(genericPlaylist))
        val source = url("/get.php?username=urluser&password=urlpass&type=m3u_plus&output=ts")
        val id = h.registry.add("Provider", SourceReference.M3uUrl(source), xtreamEnabled = true)

        syncer.sync(id)

        assertThat(h.secureStore.account(id)).isEqualTo(XtreamAccount("http", server.hostName, server.port, "urluser", "urlpass"))
        assertThat(h.store.playlist(id)!!.xtreamEnabled).isTrue()
        assertThat(h.store.syncState(id)!!.catalogStale).isTrue()
    }

    @Test
    fun getPhpWithXtreamEntriesUsesTheEntriesAccount() = runBlocking<Unit> {
        val host = "${server.hostName}:${server.port}"
        server.enqueue(ok(TestPlaylists.build(6, user = "entryuser", pass = "entrypass", host = host)))
        val id = h.registry.add("Provider", SourceReference.M3uUrl(url("/get.php?username=entryuser&password=entrypass")))

        syncer.sync(id)

        assertThat(h.secureStore.account(id)!!.username).isEqualTo("entryuser")
        assertThat(h.store.playlist(id)!!.xtreamEnabled).isTrue()
        assertThat(h.store.channels(id).all { it.streamId != null }).isTrue()
        // Xtream entries rebuild their URLs from stream ids: nothing is vaulted.
        assertThat(h.vault.streamUrl(id, 0)).isNull()
    }

    @Test
    fun nonXtreamPlaylistLoadsLiveOnlyAndIsPlayableThroughTheVault() = runBlocking<Unit> {
        server.enqueue(ok(genericPlaylist))
        val id = h.registry.add("Web", SourceReference.M3uUrl(url("/plain/list.m3u")), xtreamEnabled = true)

        syncer.sync(id)

        assertThat(h.store.playlist(id)!!.xtreamEnabled).isFalse()
        assertThat(h.secureStore.account(id)).isNull()
        val channels = h.store.channels(id)
        assertThat(channels.map { it.name }).containsExactly("### NEWS ###", "News One", "News Two", "Music One").inOrder()
        assertThat(channels.all { it.streamId == null }).isTrue()
        assertThat(h.vault.streamUrl(id, channels[1].ordinal)).isEqualTo("https://cdn.example.invalid/hls/news1.m3u8?token=tok-111")
        assertThat(h.vault.streamUrl(id, channels[3].ordinal)).isEqualTo("https://radio.example.invalid/music1/playlist.m3u8")
        // Headers are not playable and are not vaulted.
        assertThat(channels[0].isHeader).isTrue()
        assertThat(h.vault.streamUrl(id, channels[0].ordinal)).isNull()

        // A refresh swaps the vault with the generation.
        server.enqueue(ok(genericPlaylist.replace("tok-111", "tok-999"), etag = "\"e2\""))
        syncer.sync(id)
        assertThat(h.vault.streamUrl(id, 1)).isEqualTo("https://cdn.example.invalid/hls/news1.m3u8?token=tok-999")
        val active = h.store.syncState(id)!!.activeGeneration!!
        assertThat(h.vaultDir.resolve("p$id").list()!!.toList()).containsExactly("g$active.vault")
    }

    @Test
    fun failedRefreshKeepsTheVaultOfTheCachedCopy() = runBlocking<Unit> {
        server.enqueue(ok(genericPlaylist))
        val id = h.registry.add("Web", SourceReference.M3uUrl(url("/list.m3u")), xtreamEnabled = false)
        syncer.sync(id)

        server.enqueue(MockResponse.Builder().code(404).build())
        val outcome = syncer.sync(id) as SyncOutcome.Failed
        assertThat(outcome.kind).isEqualTo(SourceError.KIND_NOT_FOUND)
        assertThat(h.vault.streamUrl(id, 2)).isEqualTo("https://cdn.example.invalid/hls/news2.m3u8?token=tok-222")
        assertThat(outcome.message).doesNotContain(server.hostName + ":" + server.port)
    }

    @Test
    fun twoPlaylistsOnTheSameAccountStaySeparate() = runBlocking<Unit> {
        val a = h.registry.add("A", SourceReference.Drive("FILE_A"))
        val b = h.registry.add("B", SourceReference.Drive("FILE_B"))
        syncer.sync(a)
        syncer.sync(b)

        assertThat(h.secureStore.account(a)).isEqualTo(h.secureStore.account(b))
        assertThat(h.store.channels(a).map { it.playlistId }.toSet()).containsExactly(a)
        assertThat(h.store.channels(b).map { it.playlistId }.toSet()).containsExactly(b)
        assertThat(h.store.channels(a).size).isEqualTo(h.store.channels(b).size)

        h.registry.remove(a)
        assertThat(h.store.channels(a)).isEmpty()
        assertThat(h.store.channels(b)).hasSize(10)
        assertThat(h.secureStore.account(b)).isNotNull()
    }

    @Test
    fun oneFailingPlaylistDoesNotAffectAnother() = runBlocking<Unit> {
        val good = h.registry.add("Good", SourceReference.Drive("FILE_ID"))
        server.enqueue(ok(genericPlaylist))
        val flaky = h.registry.add("Flaky", SourceReference.M3uUrl(url("/list.m3u")), xtreamEnabled = false)
        syncer.sync(good)
        syncer.sync(flaky)

        server.enqueue(MockResponse.Builder().code(500).build())
        h.now += 1_000
        assertThat(syncer.sync(flaky)).isInstanceOf(SyncOutcome.Failed::class.java)
        assertThat(syncer.sync(good)).isEqualTo(SyncOutcome.Unchanged)

        val flakyState = h.store.syncState(flaky)!!
        assertThat(flakyState.lastFailureKind).isEqualTo(SourceError.KIND_OTHER)
        assertThat(flakyState.activeGeneration).isNotNull() // cached copy still shown
        assertThat(h.store.channels(flaky)).hasSize(4)
        val goodState = h.store.syncState(good)!!
        assertThat(goodState.lastFailureKind).isNull()
        assertThat(goodState.lastFailureAt).isNull()
    }
}
