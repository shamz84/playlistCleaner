package app.playlistplayer.core.network.drive

import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.network.source.PlaylistSource
import app.playlistplayer.core.network.source.SourceFetchResult
import app.playlistplayer.core.network.source.SourceVersion
import java.io.InputStream

/** [PlaylistSource] over a link-shared Drive file. */
class DrivePlaylistSource(
    private val client: DriveClient,
    private val reference: SourceReference.Drive,
) : PlaylistSource {
    override suspend fun <T> fetch(previous: SourceVersion?, consume: suspend (InputStream) -> T): SourceFetchResult<T> =
        client.fetch(reference.fileId, reference.resourceKey, previous, consume)

    override fun toString(): String = "DrivePlaylistSource(***)"
}
