package app.playlistplayer.feature.live

import androidx.media3.common.C
import app.playlistplayer.core.player.ConfiguredPlayer
import app.playlistplayer.core.player.ExoPlaybackController
import app.playlistplayer.core.player.PlayableSource
import app.playlistplayer.core.player.PlaybackState
import kotlinx.coroutines.flow.StateFlow

/**
 * What the live and catch-up ViewModels drive. Keeps the ViewModels free of Media3 so they run
 * as plain JVM tests; [ExoPlayerDriver] adapts the U6 controller. Call on the main thread.
 */
interface PlayerDriver {
    val state: StateFlow<PlaybackState>

    /** Position in the current media item (ms); 0 when unknown. */
    val positionMs: Long

    val isPlaying: Boolean

    /** Zap to [sources]: selected version first, then failover versions in comparator order. */
    fun play(sources: List<PlayableSource>, startPositionMs: Long? = null)

    /** Native seek within the current item. */
    fun seekTo(positionMs: Long)

    fun pause()
    fun resume()
    fun retry()
    fun stop()
}

/** [PlayerDriver] over the app's [ExoPlaybackController] and its [ConfiguredPlayer]. */
class ExoPlayerDriver(
    private val controller: ExoPlaybackController,
    private val player: ConfiguredPlayer,
) : PlayerDriver {
    override val state: StateFlow<PlaybackState> get() = controller.state

    override val positionMs: Long
        get() = player.exoPlayer.currentPosition.takeIf { it != C.TIME_UNSET && it >= 0 } ?: 0L

    override val isPlaying: Boolean get() = player.exoPlayer.isPlaying

    override fun play(sources: List<PlayableSource>, startPositionMs: Long?) = controller.play(sources, startPositionMs)

    override fun seekTo(positionMs: Long) = player.exoPlayer.seekTo(positionMs.coerceAtLeast(0))

    override fun pause() = controller.pause()

    override fun resume() = controller.resume()

    override fun retry() = controller.retry()

    override fun stop() = controller.stop()
}
