package app.playlistplayer.core.sync

import app.playlistplayer.core.database.PlaylistStore
import app.playlistplayer.core.model.Playlist
import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.sync.secure.SecureStore

/**
 * Creates and removes playlists, keeping the Room registry row and the encrypted source
 * reference/account in step. U15 builds its repository and orchestration on top of this.
 */
class PlaylistRegistry(
    private val store: PlaylistStore,
    private val secureStore: SecureStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Registers a playlist and stores its [reference] encrypted. Returns the playlist id. */
    suspend fun add(name: String, reference: SourceReference, xtreamEnabled: Boolean = true): Long {
        val id = store.createPlaylist(name.ifBlank { Playlist.DEFAULT_NAME }, reference.type, xtreamEnabled, clock())
        try {
            secureStore.putSourceReference(id, reference)
        } catch (e: Exception) {
            store.deletePlaylist(id)
            throw e
        }
        return id
    }

    /** Deletes the playlist's rows and secrets. Callers also cancel its scheduled work. */
    suspend fun remove(playlistId: Long) {
        store.deletePlaylist(playlistId)
        secureStore.removePlaylist(playlistId)
    }
}
