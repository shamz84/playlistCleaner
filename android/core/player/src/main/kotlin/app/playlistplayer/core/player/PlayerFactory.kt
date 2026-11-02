@file:OptIn(UnstableApi::class)

package app.playlistplayer.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Player settings that come from the user's preferences or are tuned on device.
 *
 * @property tunnelingEnabled video tunneling (device-specific; off by default, a setting enables it).
 * @property tsExtractorFlags TS payload reader flags. The provider's live TS is an ffmpeg remux
 *   that starts on a sync byte (spike §8); non-IDR keyframes are allowed so playback can start on
 *   the first usable frame instead of waiting for an IDR.
 */
data class PlayerSettings(
    val tunnelingEnabled: Boolean = false,
    val userAgent: String = "PlaylistPlayer/0.1 (Android)",
    val connectTimeoutMs: Long = 4_000,
    val readTimeoutMs: Long = 10_000,
    val tsExtractorFlags: Int = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES,
    val loadControlTuning: LoadControlTuning = LoadControlTuning(),
    val liveLoaderRetry: LoaderRetryConfig = LoaderRetryConfig.LIVE,
    val vodLoaderRetry: LoaderRetryConfig = LoaderRetryConfig.VOD,
)

/**
 * Builds the app's single long-lived [ExoPlayer] (create once per process, release when the app is
 * backgrounded). Configuration:
 * - OkHttp data source: follows redirects including cross-host (the provider 302s every stream to
 *   a tokenised path on another host), short connect timeout, small connection pool. Media
 *   requests send `Accept-Encoding: identity` (the Media3 OkHttp data source does this unless gzip
 *   is explicitly allowed), so OkHttp's transparent gzip never applies to media.
 * - [DefaultRenderersFactory] with decoder fallback; [DefaultTrackSelector] with the tunneling flag.
 * - [ProfileLoadControl] with device-scaled live/VOD profiles; [LiveErrorPolicy] per kind.
 * - Wake mode network, movie audio attributes with audio focus, pause on becoming noisy.
 *
 * [baseClient] lets the app share its OkHttp dispatcher/pool; no interceptors that log URLs may be
 * installed on it.
 */
@OptIn(UnstableApi::class)
class PlayerFactory(
    context: Context,
    private val settings: PlayerSettings = PlayerSettings(),
    private val baseClient: OkHttpClient? = null,
    private val deviceMemory: DeviceMemory? = null,
) {
    private val context = context.applicationContext

    fun create(): ConfiguredPlayer {
        val client = (baseClient?.newBuilder() ?: OkHttpClient.Builder())
            .connectTimeout(settings.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(settings.readTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(client).setUserAgent(settings.userAgent)

        val extractorsFactory = DefaultExtractorsFactory().setTsExtractorFlags(settings.tsExtractorFlags)

        val liveSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            .setLoadErrorHandlingPolicy(LiveErrorPolicy(settings.liveLoaderRetry))
        // On-demand items expose their embedded text tracks (MKV SRT/ASS/PGS/VobSub, MP4 tx3g/WebVTT,
        // HLS WebVTT renditions, CEA-608/708 captions): subtitles are parsed during extraction with
        // the default parsers, and the default TextRenderer renders them (R40). Track selection
        // (preferred languages, text on/off) happens in the app's VOD players.
        val vodSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
            .setSubtitleParserFactory(DefaultSubtitleParserFactory())
            .experimentalParseSubtitlesDuringExtraction(true)
            .setLoadErrorHandlingPolicy(LiveErrorPolicy(settings.vodLoaderRetry))

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(settings.tunnelingEnabled))
        }

        val memory = deviceMemory ?: DeviceMemory.from(context)
        val loadControl = ProfileLoadControl(settings.loadControlTuning.resolve(memory))

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(liveSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        return ConfiguredPlayer(player, loadControl, trackSelector, liveSourceFactory, vodSourceFactory)
    }
}

/** The long-lived player plus the handles needed to switch profiles and settings. */
@OptIn(UnstableApi::class)
class ConfiguredPlayer internal constructor(
    val exoPlayer: ExoPlayer,
    val loadControl: ProfileLoadControl,
    val trackSelector: DefaultTrackSelector,
    private val liveSourceFactory: MediaSource.Factory,
    private val vodSourceFactory: MediaSource.Factory,
) {
    /** Applies the tunneling setting; takes effect on the next track selection. */
    fun setTunnelingEnabled(enabled: Boolean) {
        trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(enabled))
    }

    /** Stops and clears the current item, closing its stream (the provider connection). */
    fun stopAndClear() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    /**
     * Closes the current stream, then prepares [source] with the matching profile and starts
     * playback. A null [startPositionMs] means the default (live edge for live).
     */
    fun prepare(source: PlayableSource, startPositionMs: Long?) {
        stopAndClear()
        loadControl.mode = if (source.isLive) PlaybackMode.LIVE else PlaybackMode.VOD
        val item = MediaItem.Builder()
            .setUri(source.url)
            .setMediaId(source.id)
            .apply { mimeTypeFor(source)?.let(::setMimeType) }
            .build()
        val factory = if (source.isLive) liveSourceFactory else vodSourceFactory
        val mediaSource = factory.createMediaSource(item)
        if (startPositionMs != null && !source.isLive) {
            exoPlayer.setMediaSource(mediaSource, startPositionMs)
        } else {
            exoPlayer.setMediaSource(mediaSource, /* resetPosition = */ true)
        }
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
    }

    fun release() {
        exoPlayer.release()
    }

    private fun mimeTypeFor(source: PlayableSource): String? {
        source.mimeType?.let { return it }
        val path = source.url.substringBefore('?').substringBefore('#').lowercase()
        return if (path.endsWith(".m3u8")) MimeTypes.APPLICATION_M3U8 else null
    }
}
