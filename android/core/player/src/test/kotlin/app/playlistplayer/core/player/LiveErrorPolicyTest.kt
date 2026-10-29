package app.playlistplayer.core.player

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class LiveErrorPolicyTest {

    private val live = LiveErrorPolicy(LoaderRetryConfig.LIVE)
    private val vod = LiveErrorPolicy(LoaderRetryConfig.VOD)

    @Test
    fun live_networkError_oneQuickRetry_thenSurfaces() {
        assertThat(live.retryDelayMs(LoadFailure.Network, 1)).isEqualTo(250L)
        assertThat(live.retryDelayMs(LoadFailure.Network, 2)).isNull()
    }

    @Test
    fun authAndNotFoundStatuses_surfaceImmediately() {
        for (status in listOf(401, 403, 509, 404, 405, 410, 400)) {
            assertThat(live.retryDelayMs(LoadFailure.Http(status), 1)).isNull()
            assertThat(vod.retryDelayMs(LoadFailure.Http(status), 1)).isNull()
        }
    }

    @Test
    fun serverErrors_retryOnlyForVod() {
        assertThat(live.retryDelayMs(LoadFailure.Http(503), 1)).isNull()
        assertThat(vod.retryDelayMs(LoadFailure.Http(503), 1)).isEqualTo(1_000L)
        assertThat(vod.retryDelayMs(LoadFailure.Http(503), 2)).isEqualTo(2_000L)
        assertThat(vod.retryDelayMs(LoadFailure.Http(503), 3)).isNull()
    }

    @Test
    fun vod_networkBackoffIsCapped() {
        val delays = (1..7).map { vod.retryDelayMs(LoadFailure.Network, it) }
        assertThat(delays).containsExactly(1_000L, 2_000L, 4_000L, 5_000L, 5_000L, 5_000L, null).inOrder()
    }

    @Test
    fun fatal_neverRetried() {
        assertThat(vod.retryDelayMs(LoadFailure.Fatal, 1)).isNull()
    }

    @Test
    fun minimumRetryCountMatchesConfiguredRetries() {
        assertThat(live.getMinimumLoadableRetryCount(0)).isEqualTo(1)
        assertThat(vod.getMinimumLoadableRetryCount(0)).isEqualTo(6)
    }

    @Test
    fun classify_ioAndParserExceptions() {
        assertThat(LiveErrorPolicy.classify(IOException("reset"))).isEqualTo(LoadFailure.Network)
        assertThat(LiveErrorPolicy.classify(SocketTimeoutException())).isEqualTo(LoadFailure.Network)
        assertThat(LiveErrorPolicy.classify(ParserException.createForMalformedContainer("bad", null)))
            .isEqualTo(LoadFailure.Fatal)
    }

    @Test
    fun httpStatusMapping() {
        assertThat(PlaybackError.fromHttpStatus(401).kind).isEqualTo(PlaybackError.Kind.HTTP_AUTH)
        assertThat(PlaybackError.fromHttpStatus(403).kind).isEqualTo(PlaybackError.Kind.HTTP_AUTH)
        assertThat(PlaybackError.fromHttpStatus(509).kind).isEqualTo(PlaybackError.Kind.HTTP_AUTH)
        assertThat(PlaybackError.fromHttpStatus(404).kind).isEqualTo(PlaybackError.Kind.HTTP_NOT_FOUND)
        assertThat(PlaybackError.fromHttpStatus(405).kind).isEqualTo(PlaybackError.Kind.HTTP_NOT_FOUND)
        assertThat(PlaybackError.fromHttpStatus(503).kind).isEqualTo(PlaybackError.Kind.NETWORK)
        assertThat(PlaybackError.fromHttpStatus(429).kind).isEqualTo(PlaybackError.Kind.NETWORK)
        assertThat(PlaybackError.fromHttpStatus(400).kind).isEqualTo(PlaybackError.Kind.HTTP_OTHER)
        assertThat(PlaybackError.fromHttpStatus(403).httpStatus).isEqualTo(403)
    }

    @Test
    fun errorCodeMapping() {
        fun kind(code: Int) = PlaybackErrorClassifier.classifyErrorCode(code).kind
        assertThat(kind(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)).isEqualTo(PlaybackError.Kind.NETWORK)
        assertThat(kind(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)).isEqualTo(PlaybackError.Kind.NETWORK)
        assertThat(kind(PlaybackException.ERROR_CODE_TIMEOUT)).isEqualTo(PlaybackError.Kind.NETWORK)
        assertThat(kind(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)).isEqualTo(PlaybackError.Kind.BEHIND_LIVE_WINDOW)
        assertThat(kind(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)).isEqualTo(PlaybackError.Kind.MEDIA)
        assertThat(kind(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)).isEqualTo(PlaybackError.Kind.MEDIA)
        assertThat(kind(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)).isEqualTo(PlaybackError.Kind.MEDIA)
        assertThat(kind(PlaybackException.ERROR_CODE_UNSPECIFIED)).isEqualTo(PlaybackError.Kind.UNKNOWN)
    }
}
