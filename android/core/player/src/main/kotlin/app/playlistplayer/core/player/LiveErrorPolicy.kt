@file:OptIn(UnstableApi::class)

package app.playlistplayer.core.player

import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.BehindLiveWindowException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import java.io.FileNotFoundException
import java.io.IOException

/** Loader-level failure classes used by [LiveErrorPolicy]. */
sealed interface LoadFailure {
    data class Http(val status: Int) : LoadFailure
    data object Network : LoadFailure
    data object Fatal : LoadFailure
}

/**
 * In-loader retry settings. Kept small for live so failures reach [PlaybackStateMachine], which
 * owns the real retry budget, connectivity pause and failover; larger for VOD so a blip resumes
 * the same byte range without a re-prepare.
 */
data class LoaderRetryConfig(
    /** Network-class retries inside the loader before the error surfaces to the player. */
    val networkRetries: Int,
    /** Retries for HTTP 5xx/408/429 before surfacing. Other statuses surface immediately. */
    val serverErrorRetries: Int,
    val initialDelayMs: Long,
    val maxDelayMs: Long,
) {
    companion object {
        /** One quick retry for a transient blip, then the state machine takes over. */
        val LIVE = LoaderRetryConfig(networkRetries = 1, serverErrorRetries = 0, initialDelayMs = 250, maxDelayMs = 250)

        /** VOD keeps its position, so retry longer in place. */
        val VOD = LoaderRetryConfig(networkRetries = 6, serverErrorRetries = 2, initialDelayMs = 1_000, maxDelayMs = 5_000)
    }
}

/**
 * [LoadErrorHandlingPolicy] for the player's media sources.
 *
 * HTTP status errors other than server-side ones are never retried in the loader: 401/403/509
 * must reach the state machine immediately for account classification, and 404/405 mean the
 * stream does not exist (failover). Network-class errors get [LoaderRetryConfig.networkRetries]
 * capped-backoff retries in place. HLS track/location fallback is delegated to Media3's default.
 */
class LiveErrorPolicy(
    private val config: LoaderRetryConfig,
) : LoadErrorHandlingPolicy {

    private val fallback = DefaultLoadErrorHandlingPolicy()

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = fallback.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        retryDelayMs(classify(loadErrorInfo.exception), loadErrorInfo.errorCount) ?: C.TIME_UNSET

    /**
     * The loader surfaces an error to the player once its count exceeds this, even while retrying,
     * so it matches the configured retry counts: retries stay silent, and a "don't retry" decision
     * surfaces at once.
     */
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = maxOf(config.networkRetries, config.serverErrorRetries)

    /** Delay before retry number [errorCount] (1-based), or null to surface the error now. */
    fun retryDelayMs(failure: LoadFailure, errorCount: Int): Long? {
        val allowed = when (failure) {
            LoadFailure.Fatal -> return null
            LoadFailure.Network -> config.networkRetries
            is LoadFailure.Http -> if (isServerSide(failure.status)) config.serverErrorRetries else return null
        }
        if (errorCount > allowed) return null
        var delay = config.initialDelayMs
        repeat((errorCount - 1).coerceIn(0, 30)) { delay = (delay * 2).coerceAtMost(config.maxDelayMs) }
        return delay.coerceAtMost(config.maxDelayMs)
    }

    companion object {
        private fun isServerSide(status: Int): Boolean = status in 500..599 && status != 509 || status == 408 || status == 429

        /** Maps a loader exception to a [LoadFailure]. */
        fun classify(exception: IOException): LoadFailure = when {
            exception is HttpDataSource.InvalidResponseCodeException -> LoadFailure.Http(exception.responseCode)
            exception is ParserException -> LoadFailure.Fatal
            exception is FileNotFoundException -> LoadFailure.Fatal
            exception is Loader.UnexpectedLoaderException -> LoadFailure.Fatal
            exception is HttpDataSource.CleartextNotPermittedException -> LoadFailure.Fatal
            else -> LoadFailure.Network
        }
    }
}

/** Maps a Media3 [PlaybackException] to a URL-free [PlaybackError]. */
object PlaybackErrorClassifier {

    fun classify(error: PlaybackException): PlaybackError {
        causes(error).forEach { cause ->
            when (cause) {
                is HttpDataSource.InvalidResponseCodeException -> return PlaybackError.fromHttpStatus(cause.responseCode)
                is BehindLiveWindowException -> return PlaybackError(PlaybackError.Kind.BEHIND_LIVE_WINDOW)
            }
        }
        return classifyErrorCode(error.errorCode)
    }

    /** Classification from a [PlaybackException] error code alone. */
    fun classifyErrorCode(errorCode: Int): PlaybackError = PlaybackError(
        when (errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> PlaybackError.Kind.BEHIND_LIVE_WINDOW
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackError.Kind.HTTP_NOT_FOUND
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            -> PlaybackError.Kind.HTTP_OTHER
            in 2000..2999 -> PlaybackError.Kind.NETWORK
            in 3000..6999 -> PlaybackError.Kind.MEDIA
            PlaybackException.ERROR_CODE_TIMEOUT -> PlaybackError.Kind.NETWORK
            else -> PlaybackError.Kind.UNKNOWN
        },
    )

    private fun causes(error: Throwable): Sequence<Throwable> =
        generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(16)
}
