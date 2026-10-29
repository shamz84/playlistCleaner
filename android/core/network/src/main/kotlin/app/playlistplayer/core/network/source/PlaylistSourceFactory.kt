package app.playlistplayer.core.network.source

import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.network.drive.DriveClient
import app.playlistplayer.core.network.drive.DrivePlaylistSource
import java.io.InputStream

/** Builds the [PlaylistSource] for a stored [SourceReference]. */
fun interface PlaylistSourceFactory {
    fun create(reference: SourceReference): PlaylistSource
}

/**
 * Drive references use [DriveClient]. M3U URL references are not implemented yet (U15 adds
 * `M3uUrlSource`); until then they fail with [SourceError.Other] rather than throwing.
 */
class DefaultPlaylistSourceFactory(private val driveClient: DriveClient) : PlaylistSourceFactory {
    override fun create(reference: SourceReference): PlaylistSource = when (reference) {
        is SourceReference.Drive -> DrivePlaylistSource(driveClient, reference)
        is SourceReference.M3uUrl -> UnsupportedSource
    }

    private object UnsupportedSource : PlaylistSource {
        override suspend fun <T> fetch(previous: SourceVersion?, consume: suspend (InputStream) -> T): SourceFetchResult<T> =
            SourceFetchResult.Failed(SourceError.Other("M3U URL playlists are not supported yet"))
    }
}
