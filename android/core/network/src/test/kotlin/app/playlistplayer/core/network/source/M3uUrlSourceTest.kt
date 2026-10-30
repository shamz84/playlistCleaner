package app.playlistplayer.core.network.source

import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.model.XtreamAccount
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Synthetic data only: fake hosts and placeholder credentials. */
class M3uUrlSourceTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    private val playlist = "#EXTM3U\n#EXTINF:-1 group-title=\"G\",A\nhttp://cdn.example.invalid/hls/a.m3u8\n"
    private var consumed = 0

    private val readAll: suspend (InputStream) -> String = { stream ->
        consumed++
        stream.readBytes().toString(Charsets.UTF_8)
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun source(path: String = "/list.m3u") =
        M3uUrlSource(http, SourceReference.M3uUrl(server.url(path).toString()), Dispatchers.IO)

    private fun fetch(previous: SourceVersion? = null, s: M3uUrlSource = source()) = runBlocking { s.fetch(previous, readAll) }

    private fun ok(body: String = playlist, etag: String? = "\"v1\"", lastModified: String? = "Wed, 28 Oct 2026 10:15:30 GMT") =
        MockResponse.Builder()
            .code(200)
            .setHeader("Content-Type", "audio/x-mpegurl")
            .apply {
                etag?.let { setHeader("ETag", it) }
                lastModified?.let { setHeader("Last-Modified", it) }
            }
            .body(body)
            .build()

    private fun status(code: Int) = MockResponse.Builder().code(code).body("nope").build()

    @Test
    fun firstFetchStreamsBodyAndRecordsValidatorsAndHash() {
        server.enqueue(ok())

        val result = fetch()

        val fetched = result as SourceFetchResult.Fetched
        assertThat(fetched.value).isEqualTo(playlist)
        assertThat(fetched.version.etag).isEqualTo("\"v1\"")
        assertThat(fetched.version.lastModified).isEqualTo("Wed, 28 Oct 2026 10:15:30 GMT")
        assertThat(fetched.version.modifiedTime).isEqualTo(java.time.Instant.parse("2026-10-28T10:15:30Z").toEpochMilli())
        assertThat(fetched.version.md5).startsWith(M3uUrlSource.HASH_PREFIX)
        val request = server.takeRequest()
        assertThat(request.headers["If-None-Match"]).isNull()
        assertThat(request.headers["If-Modified-Since"]).isNull()
    }

    @Test
    fun notModifiedSkipsTheConsumer() {
        server.enqueue(ok())
        val first = (fetch() as SourceFetchResult.Fetched).version
        server.takeRequest()
        server.enqueue(MockResponse.Builder().code(304).build())

        val result = fetch(previous = first)

        assertThat(result).isInstanceOf(SourceFetchResult.Unchanged::class.java)
        assertThat((result as SourceFetchResult.Unchanged).version.etag).isEqualTo("\"v1\"")
        assertThat(consumed).isEqualTo(1)
        val request = server.takeRequest()
        assertThat(request.headers["If-None-Match"]).isEqualTo("\"v1\"")
        assertThat(request.headers["If-Modified-Since"]).isEqualTo("Wed, 28 Oct 2026 10:15:30 GMT")
    }

    @Test
    fun sameContentWithoutValidatorsIsUnchangedByHash() {
        server.enqueue(ok(etag = null, lastModified = null))
        val first = (fetch() as SourceFetchResult.Fetched).version
        assertThat(first.etag).isNull()
        server.enqueue(ok(etag = null, lastModified = null))

        assertThat(fetch(previous = first)).isInstanceOf(SourceFetchResult.Unchanged::class.java)

        server.enqueue(ok(body = playlist + "#EXTINF:-1,B\nhttp://cdn.example.invalid/hls/b.m3u8\n", etag = null, lastModified = null))
        val changed = fetch(previous = first)
        assertThat(changed).isInstanceOf(SourceFetchResult.Fetched::class.java)
        assertThat((changed as SourceFetchResult.Fetched).version.md5).isNotEqualTo(first.md5)
    }

    @Test
    fun httpErrorsMapToSourceErrors() {
        val expected = mapOf(
            404 to SourceError.KIND_NOT_FOUND,
            410 to SourceError.KIND_NOT_FOUND,
            401 to SourceError.KIND_UNAUTHORIZED,
            403 to SourceError.KIND_UNAUTHORIZED,
            429 to SourceError.KIND_QUOTA,
            500 to SourceError.KIND_OTHER,
        )
        for ((code, kind) in expected) {
            server.enqueue(status(code))
            val result = fetch() as SourceFetchResult.Failed
            assertThat(result.error.kind).isEqualTo(kind)
        }
        server.enqueue(status(503))
        assertThat(((fetch() as SourceFetchResult.Failed).error as SourceError.Other).httpCode).isEqualTo(503)
        assertThat(consumed).isEqualTo(0)
    }

    @Test
    fun htmlPageIsNotAPlaylist() {
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "text/plain").body("  <!DOCTYPE html><html>login</html>").build())
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "text/html; charset=utf-8").body("#EXTM3U").build())

        assertThat((fetch() as SourceFetchResult.Failed).error).isInstanceOf(SourceError.NotAPlaylist::class.java)
        assertThat((fetch() as SourceFetchResult.Failed).error).isInstanceOf(SourceError.NotAPlaylist::class.java)
        assertThat(consumed).isEqualTo(0)
    }

    @Test
    fun connectionDropMidBodyMapsToNetwork() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(playlist.repeat(50))
                .setHeader("Content-Length", playlist.length * 500)
                .onResponseEnd(SocketEffect.ShutdownConnection)
                .build(),
        )
        assertThat((fetch() as SourceFetchResult.Failed).error).isInstanceOf(SourceError.Network::class.java)
    }

    @Test
    fun consumerExceptionBecomesItsError() {
        server.enqueue(ok())
        val result = runBlocking {
            source().fetch(null) { throw SourceConsumerException(SourceError.Other("bad body")) }
        }
        assertThat((result as SourceFetchResult.Failed).error.message).isEqualTo("bad body")
    }

    @Test
    fun credentialUrlNeverAppearsInErrorsAndDerivesTheAccount() {
        val url = server.url("/get.php?username=secretuser&password=secretpass&type=m3u_plus").toString()
        val s = M3uUrlSource(http, SourceReference.M3uUrl(url), Dispatchers.IO)
        assertThat(s.derivedAccount).isEqualTo(
            XtreamAccount("http", server.hostName, server.port, "secretuser", "secretpass"),
        )
        assertThat(s.toString()).doesNotContain("secret")

        server.enqueue(status(403))
        val refused = fetch(s = s) as SourceFetchResult.Failed
        assertThat(refused.error.message).doesNotContain("secret")

        server.close()
        val offline = fetch(s = s) as SourceFetchResult.Failed
        assertThat(offline.error).isInstanceOf(SourceError.Network::class.java)
        assertThat(offline.error.message).doesNotContain("secret")
        assertThat(offline.error.message).doesNotContain(server.hostName)
    }

    @Test
    fun plainUrlDerivesNoAccountAndInvalidUrlFails() {
        assertThat(M3uUrlSource.accountFromUrl("https://lists.example.invalid/channels.m3u")).isNull()
        val bad = M3uUrlSource(http, SourceReference.M3uUrl("ftp://example.invalid/x.m3u"), Dispatchers.IO)
        assertThat((runBlocking { bad.fetch(null, readAll) } as SourceFetchResult.Failed).error.kind).isEqualTo(SourceError.KIND_OTHER)
        assertThat(M3uUrlSource.isValidUrl("https://lists.example.invalid/channels.m3u")).isTrue()
        assertThat(M3uUrlSource.isValidUrl("not a url")).isFalse()
    }
}
