package app.playlistplayer.core.network.drive

import app.playlistplayer.core.network.source.SourceError
import app.playlistplayer.core.network.source.SourceFetchResult
import app.playlistplayer.core.network.source.SourceVersion
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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DriveClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DriveClient
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    private val playlist = "#EXTM3U\n#EXTINF:-1 group-title=\"G\",A\nhttp://example.invalid/live/user/pass/1.ts\n"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = newClient(apiKey = "TEST_KEY")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun newClient(apiKey: String) = DriveClient(
        http,
        DriveClientConfig(
            apiKey = apiKey,
            packageName = "app.playlistplayer",
            certSha1 = "AABBCCDDEEFF00112233445566778899AABBCCDD",
            apiBaseUrl = server.url("/api/"),
            fallbackBaseUrl = server.url("/uc/"),
        ),
        Dispatchers.IO,
    )

    private fun metadata(md5: String = "abc123", version: String = "7") = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body("""{"md5Checksum":"$md5","version":"$version","size":"${playlist.length}","modifiedTime":"2026-10-28T10:15:30.000Z"}""")
        .build()

    private fun apiError(code: Int, domain: String, reason: String) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json")
        .body("""{"error":{"code":$code,"message":"x","errors":[{"domain":"$domain","reason":"$reason","message":"x"}]}}""")
        .build()

    private fun m3u(body: String = playlist) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "audio/x-mpegurl")
        .body(body)
        .build()

    private fun html(body: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .body(body)
        .build()

    private val readAll: suspend (InputStream) -> String = { it.readBytes().toString(Charsets.UTF_8) }

    private fun fetch(previous: SourceVersion? = null, resourceKey: String? = null, c: DriveClient = client) =
        runBlocking { c.fetch("FILE_ID", resourceKey, previous, readAll) }

    @Test
    fun unchangedMd5MakesNoMediaRequest() {
        server.enqueue(metadata(md5 = "abc123"))

        val result = fetch(previous = SourceVersion(md5 = "abc123", version = "6"))

        assertThat(result).isInstanceOf(SourceFetchResult.Unchanged::class.java)
        val version = (result as SourceFetchResult.Unchanged).version
        assertThat(version.md5).isEqualTo("abc123")
        assertThat(version.modifiedTime).isEqualTo(java.time.Instant.parse("2026-10-28T10:15:30Z").toEpochMilli())
        assertThat(server.requestCount).isEqualTo(1)
        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/api/drive/v3/files/FILE_ID")
        assertThat(request.url.queryParameter("fields")).isEqualTo("md5Checksum,version,size,modifiedTime")
        assertThat(request.url.queryParameter("key")).isEqualTo("TEST_KEY")
        assertThat(request.url.queryParameter("alt")).isNull()
    }

    @Test
    fun changedMd5DownloadsMediaWithAndroidHeaders() {
        server.enqueue(metadata(md5 = "new"))
        server.enqueue(m3u())

        val result = fetch(previous = SourceVersion(md5 = "old"), resourceKey = "RK")

        assertThat(result).isInstanceOf(SourceFetchResult.Fetched::class.java)
        result as SourceFetchResult.Fetched
        assertThat(result.value).isEqualTo(playlist)
        assertThat(result.version.md5).isEqualTo("new")
        assertThat(result.version.version).isEqualTo("7")

        val meta = server.takeRequest()
        val media = server.takeRequest()
        for (request in listOf(meta, media)) {
            assertThat(request.headers[DriveClient.HEADER_ANDROID_PACKAGE]).isEqualTo("app.playlistplayer")
            assertThat(request.headers[DriveClient.HEADER_ANDROID_CERT]).isEqualTo("AABBCCDDEEFF00112233445566778899AABBCCDD")
            assertThat(request.headers[DriveClient.HEADER_RESOURCE_KEYS]).isEqualTo("FILE_ID/RK")
        }
        assertThat(media.url.queryParameter("alt")).isEqualTo("media")
    }

    @Test
    fun versionIsUsedWhenMd5Missing() {
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"version":"9"}""").build(),
        )
        val result = fetch(previous = SourceVersion(version = "9"))
        assertThat(result).isInstanceOf(SourceFetchResult.Unchanged::class.java)
    }

    @Test
    fun firstSyncAlwaysDownloads() {
        server.enqueue(metadata())
        server.enqueue(m3u())
        val result = fetch(previous = null)
        assertThat(result).isInstanceOf(SourceFetchResult.Fetched::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun api404MapsToNotFoundOrNotSharedWithoutFallback() {
        server.enqueue(apiError(404, "global", "notFound"))

        val result = fetch()

        assertThat((result as SourceFetchResult.Failed).error).isInstanceOf(SourceError.NotFoundOrNotShared::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun downloadQuotaExceededMapsToQuotaExceeded() {
        server.enqueue(metadata())
        server.enqueue(apiError(403, "usageLimits", "downloadQuotaExceeded"))

        val result = fetch()

        assertThat((result as SourceFetchResult.Failed).error).isInstanceOf(SourceError.QuotaExceeded::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun rejectedKeyFallsBackAndHtmlBodyMapsToNotShared() {
        server.enqueue(apiError(400, "usageLimits", "keyInvalid"))
        server.enqueue(html("<!DOCTYPE html><html><head><title>Google Drive - Sign in</title></head></html>"))

        val result = fetch(resourceKey = "RK")

        assertThat((result as SourceFetchResult.Failed).error).isInstanceOf(SourceError.NotShared::class.java)
        server.takeRequest()
        val fallback = server.takeRequest()
        assertThat(fallback.url.encodedPath).isEqualTo("/uc/download")
        assertThat(fallback.url.queryParameter("id")).isEqualTo("FILE_ID")
        assertThat(fallback.url.queryParameter("export")).isEqualTo("download")
        assertThat(fallback.url.queryParameter("confirm")).isEqualTo("t")
        assertThat(fallback.url.queryParameter("resourcekey")).isEqualTo("RK")
        // The keyless endpoint gets no API key headers.
        assertThat(fallback.headers[DriveClient.HEADER_ANDROID_PACKAGE]).isNull()
    }

    @Test
    fun htmlWithoutHtmlContentTypeIsStillDetected() {
        server.enqueue(apiError(500, "global", "backendError"))
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/octet-stream").body("\n  <html><body>Sign in</body></html>").build())

        val result = fetch()

        assertThat((result as SourceFetchResult.Failed).error).isInstanceOf(SourceError.NotShared::class.java)
    }

    @Test
    fun fallbackQuotaPageMapsToQuotaExceeded() {
        server.enqueue(apiError(403, "usageLimits", "dailyLimitExceeded"))
        server.enqueue(html("<html><body>Sorry, you can't view or download this file at this time. Too many users have viewed or downloaded this file recently.</body></html>"))

        val result = fetch()

        assertThat((result as SourceFetchResult.Failed).error).isInstanceOf(SourceError.QuotaExceeded::class.java)
    }

    @Test
    fun serverErrorFallsBackToUsercontentAndComputesMd5() {
        server.enqueue(apiError(500, "global", "backendError"))
        server.enqueue(m3u())

        val result = fetch()

        result as SourceFetchResult.Fetched
        assertThat(result.value).isEqualTo(playlist)
        val expected = MessageDigest.getInstance("MD5").digest(playlist.toByteArray()).joinToString("") { "%02x".format(it) }
        assertThat(result.version.md5).isEqualTo(expected)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun mediaFailureFallsBackKeepingApiFingerprint() {
        server.enqueue(metadata(md5 = "api-md5"))
        server.enqueue(apiError(403, "global", "forbidden"))
        server.enqueue(m3u())

        val result = fetch()

        result as SourceFetchResult.Fetched
        assertThat(result.version.md5).isEqualTo("api-md5")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun blankApiKeyUsesFallbackOnly() {
        server.enqueue(m3u())
        val result = fetch(c = newClient(apiKey = ""))
        assertThat(result).isInstanceOf(SourceFetchResult.Fetched::class.java)
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/uc/download")
    }

    @Test
    fun connectionDropMidDownloadMapsToNetwork() {
        server.enqueue(metadata())
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(playlist.repeat(50))
                .setHeader("Content-Length", playlist.length * 500)
                .onResponseEnd(SocketEffect.ShutdownConnection)
                .build(),
        )
        var bytesSeen = 0
        val result = runBlocking {
            client.fetch("FILE_ID", null, null) { stream ->
                val buffer = ByteArray(1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    bytesSeen += n
                }
                "done"
            }
        }

        assertThat((result as SourceFetchResult.Failed).error).isInstanceOf(SourceError.Network::class.java)
        assertThat(bytesSeen).isGreaterThan(0)
        // No fallback once the consumer has seen data.
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun unreachableServerMapsToNetwork() {
        val deadUrl = server.url("/api/")
        server.close()
        val c = DriveClient(http, DriveClientConfig("K", "p", "c", deadUrl, deadUrl), Dispatchers.IO)
        val result = runBlocking { c.fetch("FILE_ID", null, null, readAll) }
        assertThat((result as SourceFetchResult.Failed).error).isInstanceOf(SourceError.Network::class.java)
        assertThat(result.error.message).doesNotContain("FILE_ID")
    }
}
