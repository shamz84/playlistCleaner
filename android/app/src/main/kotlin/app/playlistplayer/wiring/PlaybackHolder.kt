package app.playlistplayer.wiring

import androidx.annotation.MainThread
import app.playlistplayer.core.player.ConfiguredPlayer
import app.playlistplayer.core.player.ExoPlaybackController

/**
 * The app's single player: one [ConfiguredPlayer] (ExoPlayer + load control) and its
 * [ExoPlaybackController], shared by every screen of both form factors, so screen changes and
 * rotation never re-create the decoder pipeline. The provider allows one connection per account,
 * so there must never be a second player.
 *
 * Lifecycle (main thread only):
 * - created lazily on first use (`MainActivity` builds the UI dependencies);
 * - the activity pauses it when it stops and calls [stop] after it stayed in the background for
 *   `MainActivity.BACKGROUND_STOP_MS` (frees the provider connection, keeps the instances);
 * - [release] when the activity finishes for good; the next activity creates fresh instances.
 *   Process death releases everything with the process.
 */
class PlaybackHolder(
    private val createPlayer: () -> ConfiguredPlayer,
    private val createController: (ConfiguredPlayer) -> ExoPlaybackController,
) {
    private var player: ConfiguredPlayer? = null
    private var controller: ExoPlaybackController? = null

    @MainThread
    fun player(): ConfiguredPlayer = player ?: createPlayer().also { player = it }

    @MainThread
    fun controller(): ExoPlaybackController = controller ?: createController(player()).also { controller = it }

    /** The player when it exists (never creates one). */
    val playerOrNull: ConfiguredPlayer? get() = player

    val controllerOrNull: ExoPlaybackController? get() = controller

    @MainThread
    fun pause() {
        controller?.pause()
    }

    /** Stops playback (closes the stream connection) but keeps the instances for the UI. */
    @MainThread
    fun stop() {
        controller?.stop()
    }

    @MainThread
    fun release() {
        controller?.close()
        controller = null
        player?.release()
        player = null
    }
}
