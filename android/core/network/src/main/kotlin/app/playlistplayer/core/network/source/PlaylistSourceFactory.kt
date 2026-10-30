package app.playlistplayer.core.network.source

import app.playlistplayer.core.model.SourceReference
import app.playlistplayer.core.network.drive.DriveClient
import app.playlistplayer.core.network.drive.DrivePlaylistSource
import okhttp3.OkHttpClient

/** Builds the [PlaylistSource] for a stored [SourceReference]. */
fun interface PlaylistSourceFactory {
    fun create(reference: SourceReference): PlaylistSource
}

/** Drive references use [DriveClient]; M3U URL references use [M3uUrlSource] over [httpClient]. */
class DefaultPlaylistSourceFactory(
    private val driveClient: DriveClient,
    private val httpClient: OkHttpClient,
    private val userAgent: String? = null,
) : PlaylistSourceFactory {
    override fun create(reference: SourceReference): PlaylistSource = when (reference) {
        is SourceReference.Drive -> DrivePlaylistSource(driveClient, reference)
        is SourceReference.M3uUrl -> M3uUrlSource(httpClient, reference, userAgent = userAgent)
    }
}
