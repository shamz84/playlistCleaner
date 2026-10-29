package app.playlistplayer.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class RedactorTest {
    private fun assertClean(text: String) {
        assertThat(text).doesNotContain("example.invalid")
        assertThat(text).doesNotContain("alice")
        assertThat(text).doesNotContain("s3cret")
        assertThat(text).doesNotContain("8080")
    }

    @Test
    fun masksXtreamCredentialPaths() {
        for (kind in listOf("live", "movie", "series", "timeshift")) {
            val redacted = Redactor.redact("GET http://example.invalid:8080/$kind/alice/s3cret/12345.ts failed")
            assertClean(redacted)
            assertThat(redacted).contains("/$kind/***/***/12345.ts")
        }
    }

    @Test
    fun masksPathWithoutHost() {
        val redacted = Redactor.redact("path=/live/alice/s3cret/1.ts")
        assertThat(redacted).isEqualTo("path=/live/***/***/1.ts")
    }

    @Test
    fun masksTimeshiftPathKeepingTimes() {
        val redacted = Redactor.redact("http://example.invalid:8080/timeshift/alice/s3cret/65/2026-10-29:20-00/123.ts")
        assertClean(redacted)
        assertThat(redacted).endsWith("/timeshift/***/***/65/2026-10-29:20-00/123.ts")
    }

    @Test
    fun masksQueryCredentials() {
        val redacted = Redactor.redact(
            "http://example.invalid:8080/player_api.php?username=alice&password=s3cret&action=get_live_streams",
        )
        assertClean(redacted)
        assertThat(redacted).contains("username=***")
        assertThat(redacted).contains("password=***")
        assertThat(redacted).contains("action=get_live_streams")

        val php = Redactor.redact("/streaming/timeshift.php?username=alice&password=s3cret&stream=1&start=2026-10-29:20-00&duration=65")
        assertClean(php)
        assertThat(php).contains("stream=1")
    }

    @Test
    fun masksRedirectTargetsAndTokenPaths() {
        val live = Redactor.redact("Redirect to http://edge.example.invalid/live/play/eyJ0b2tlbiI6ImFsaWNlIn0/12345.ts")
        assertThat(live).doesNotContain("example.invalid")
        assertThat(live).doesNotContain("eyJ0b2tlbiI6ImFsaWNlIn0")
        assertThat(live).contains("/live/play/***/12345.ts")

        val hls = Redactor.redact("segment http://edge.example.invalid:25461/hls/abcDEF123token/12345_77.ts")
        assertThat(hls).doesNotContain("abcDEF123token")
        assertThat(hls).contains("/hls/***/12345_77.ts")

        val php = Redactor.redact("http://edge.example.invalid/play/timeshift.php?token=abcDEF123token")
        assertThat(php).doesNotContain("abcDEF123token")
        assertThat(php).doesNotContain("example.invalid")
    }

    @Test
    fun masksShortXtreamFormAndUserinfo() {
        val short = Redactor.redact("http://example.invalid:8080/alice/s3cret/12345")
        assertClean(short)
        val userinfo = Redactor.redact("http://alice:s3cret@example.invalid:8080/stream")
        assertClean(userinfo)
    }

    @Test
    fun masksDriveFileIdsAndKeysButKeepsGoogleHosts() {
        val api = Redactor.redact("https://www.googleapis.com/drive/v3/files/1AbCdEfGhIjK?alt=media&key=AIzaSyFAKE")
        assertThat(api).doesNotContain("1AbCdEfGhIjK")
        assertThat(api).doesNotContain("AIzaSyFAKE")
        assertThat(api).contains("www.googleapis.com")
        val fallback = Redactor.redact("https://drive.usercontent.google.com/download?id=1AbCdEfGhIjK&export=download&confirm=t&resourcekey=RK123")
        assertThat(fallback).doesNotContain("1AbCdEfGhIjK")
        assertThat(fallback).doesNotContain("RK123")
        assertThat(fallback).contains("export=download")
    }

    @Test
    fun redactionIsIdempotentAndLeavesPlainTextAlone() {
        val once = Redactor.redact("http://example.invalid:8080/live/alice/s3cret/1.ts?username=alice")
        assertThat(Redactor.redact(once)).isEqualTo(once)
        assertThat(Redactor.redact("Parsed 15433 channels in 130 groups")).isEqualTo("Parsed 15433 channels in 130 groups")
        assertThat(Redactor.redact("stream_id=42")).isEqualTo("stream_id=42")
        assertThat(Redactor.redact(null)).isEmpty()
    }

    @Test
    fun masksThrowableMessagesAndCauses() {
        val cause = IOException("unexpected end of stream on http://example.invalid:8080/live/alice/s3cret/1.ts")
        val error = IllegalStateException("Sync failed for http://example.invalid:8080/get.php?username=alice&password=s3cret", cause)

        val text = Redactor.redact(error)
        assertClean(text)
        assertThat(text).contains("java.lang.IllegalStateException")
        assertThat(text).contains("Caused by: java.io.IOException")

        val copy = Redactor.redacted(error)
        assertClean(copy.toString())
        assertClean(copy.message.orEmpty())
        assertClean(copy.cause?.message.orEmpty())
        assertThat(copy.stackTrace).isEqualTo(error.stackTrace)
        assertThat(error.redactedMessage()).isEqualTo(text)
    }

    @Test
    fun dropsMessagesOfExceptionsThatNameTheHost() {
        val text = Redactor.redact(UnknownHostException("Unable to resolve host \"example.invalid\": No address associated with hostname"))
        assertClean(text)
        assertThat(text).contains("UnknownHostException")
    }

    @Test
    fun literalSecretsAreMasked() {
        val text = Redactor.redact("login alice at example.invalid failed", listOf("alice", "example.invalid", "x"))
        assertClean(text)
        assertThat(text).isEqualTo("login *** at *** failed")
    }

    @Test
    fun loggerRedactsMessagesAndThrowables() {
        val lines = mutableListOf<Pair<String, Throwable?>>()
        val logger = RedactingLogger({ _, _, message, t -> lines += message to t })
        logger.e(
            "Failed http://example.invalid:8080/live/alice/s3cret/1.ts",
            IOException("http://example.invalid:8080/movie/alice/s3cret/2.mkv"),
        )
        val (message, throwable) = lines.single()
        assertClean(message)
        assertClean(throwable.toString())
        assertThat(throwable).isInstanceOf(RedactedThrowable::class.java)
    }
}
