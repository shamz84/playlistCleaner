package app.playlistplayer.core.sync

import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.network.drive.DriveClient
import app.playlistplayer.core.network.drive.DriveClientConfig
import app.playlistplayer.core.network.source.DefaultPlaylistSourceFactory
import app.playlistplayer.core.network.source.SourceError
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/** End-to-end: DriveClient over MockWebServer → PlaylistSyncer → Room. */
@RunWith(RobolectricTestRunner::class)
class PlaylistSyncerDriveTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var h: SyncHarness
    private lateinit var server: MockWebServer
    private lateinit var syncer: PlaylistSyncer
    private var playlistId = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        h = SyncHarness(temp.root)
        server = MockWebServer().apply { start() }
        val drive = DriveClient(
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
            DriveClientConfig(
                apiKey = "TEST_KEY",
                packageName = "app.playlistplayer",
                certSha1 = "00112233445566778899AABBCCDDEEFF00112233",
                apiBaseUrl = server.url("/api/"),
                fallbackBaseUrl = server.url("/uc/"),
            ),
        )
        syncer = h.syncer(DefaultPlaylistSourceFactory(drive), batchSize = 25)
        playlistId = h.registry.add("My playlist", SourceReference.Drive("FILE_ID"))

        // Initial good sync.
        server.enqueue(metadata("md5-1"))
        server.enqueue(body(TestPlaylists.fixture()))
        assertThat(syncer.sync(playlistId)).isInstanceOf(SyncOutcome.Updated::class.java)
        server.takeRequest()
        server.takeRequest()
    }

    @After
    fun tearDown() {
        server.close()
        h.close()
    }

    private fun metadata(md5: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body("""{"md5Checksum":"$md5","version":"3","size":"100","modifiedTime":"2026-10-28T10:00:00Z"}""")
        .build()

    private fun body(text: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "audio/x-mpegurl").body(text).build()

    private fun apiError(code: Int, domain: String, reason: String) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json")
        .body("""{"error":{"code":$code,"errors":[{"domain":"$domain","reason":"$reason"}]}}""")
        .build()

    private suspend fun assertPreviousIntact(expectedFailure: String) {
        val state = h.store.syncState(playlistId)!!
        assertThat(state.lastFailureKind).isEqualTo(expectedFailure)
        assertThat(state.sourceMd5).isEqualTo("md5-1")
        assertThat(state.pendingGeneration).isNull()
        assertThat(h.store.generationsPresent(playlistId)).containsExactly(state.activeGeneration)
        assertThat(h.store.channels(playlistId)).hasSize(10)
        assertThat(h.store.groups(playlistId).first().name).isEqualTo("🇬🇧 TV Guide (UK)")
    }

    @Test
    fun unchangedMd5MakesNoMediaRequest() = runBlocking<Unit> {
        server.enqueue(metadata("md5-1"))

        assertThat(syncer.sync(playlistId)).isEqualTo(SyncOutcome.Unchanged)

        val request = server.takeRequest()
        assertThat(request.url.queryParameter("fields")).isNotNull()
        assertThat(request.headers[DriveClient.HEADER_ANDROID_PACKAGE]).isEqualTo("app.playlistplayer")
        assertThat(request.headers[DriveClient.HEADER_ANDROID_CERT]).isEqualTo("00112233445566778899AABBCCDDEEFF00112233")
        assertThat(server.requestCount).isEqualTo(3) // 2 from setUp + 1 metadata
        assertThat(h.store.channels(playlistId)).hasSize(10)
    }

    @Test
    fun notFoundLeavesPreviousIntact() = runBlocking<Unit> {
        server.enqueue(apiError(404, "global", "notFound"))
        val outcome = syncer.sync(playlistId) as SyncOutcome.Failed
        assertThat(outcome.kind).isEqualTo(SourceError.KIND_NOT_FOUND)
        assertThat(outcome.retryable).isFalse()
        assertPreviousIntact(SourceError.KIND_NOT_FOUND)
    }

    @Test
    fun quotaExceededLeavesPreviousIntact() = runBlocking<Unit> {
        server.enqueue(metadata("md5-2"))
        server.enqueue(apiError(403, "usageLimits", "downloadQuotaExceeded"))
        val outcome = syncer.sync(playlistId) as SyncOutcome.Failed
        assertThat(outcome.kind).isEqualTo(SourceError.KIND_QUOTA)
        assertPreviousIntact(SourceError.KIND_QUOTA)
    }

    @Test
    fun htmlFallbackLeavesPreviousIntact() = runBlocking<Unit> {
        server.enqueue(apiError(400, "usageLimits", "keyInvalid"))
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "text/html").body("<!DOCTYPE html><html>Sign in</html>").build(),
        )
        val outcome = syncer.sync(playlistId) as SyncOutcome.Failed
        assertThat(outcome.kind).isEqualTo(SourceError.KIND_NOT_SHARED)
        assertPreviousIntact(SourceError.KIND_NOT_SHARED)
    }

    @Test
    fun connectionDropMidDownloadDoesNotFlip() = runBlocking<Unit> {
        val big = TestPlaylists.build(count = 1_000)
        val partial = big.substring(0, big.length * 6 / 10)
        server.enqueue(metadata("md5-2"))
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(partial)
                .setHeader("Content-Length", big.toByteArray().size)
                .onResponseEnd(SocketEffect.ShutdownConnection)
                .build(),
        )

        val outcome = syncer.sync(playlistId) as SyncOutcome.Failed

        assertThat(outcome.kind).isEqualTo(SourceError.KIND_NETWORK)
        assertThat(outcome.retryable).isTrue()
        assertPreviousIntact(SourceError.KIND_NETWORK)
    }

    @Test
    fun changedFileReplacesChannels() = runBlocking<Unit> {
        server.enqueue(metadata("md5-2"))
        server.enqueue(body(TestPlaylists.build(count = 12)))

        assertThat(syncer.sync(playlistId)).isInstanceOf(SyncOutcome.Updated::class.java)

        assertThat(h.store.channels(playlistId)).hasSize(12)
        assertThat(h.store.syncState(playlistId)!!.sourceMd5).isEqualTo("md5-2")
        assertThat(h.store.syncState(playlistId)!!.sourceModifiedTime).isNotNull()
    }
}
