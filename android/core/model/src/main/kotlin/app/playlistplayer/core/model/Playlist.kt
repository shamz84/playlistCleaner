package app.playlistplayer.core.model

/** Where a playlist's M3U comes from. The source reference itself is a secret held in SecureStore. */
enum class PlaylistSourceType { DRIVE, M3U_URL }

/**
 * Top-level scope for all data. Every group, channel, title, programme, favourite and progress
 * record carries the owning playlist's [id].
 */
data class Playlist(
    val id: Long,
    val name: String,
    val sourceType: PlaylistSourceType,
    val xtreamEnabled: Boolean,
    val createdAt: Long,
) {
    companion object {
        const val DEFAULT_NAME: String = "My playlist"
    }
}

/**
 * The secret handle a playlist is fetched from. Both forms are bearer secrets (a link-shared Drive
 * file ID, or an M3U URL that may embed credentials), so they are only ever stored encrypted and
 * [toString] never reveals them.
 */
sealed interface SourceReference {
    val type: PlaylistSourceType

    data class Drive(val fileId: String, val resourceKey: String? = null) : SourceReference {
        override val type: PlaylistSourceType get() = PlaylistSourceType.DRIVE
        override fun toString(): String = "SourceReference.Drive(fileId=***, resourceKey=${if (resourceKey == null) "null" else "***"})"
    }

    data class M3uUrl(val url: String) : SourceReference {
        override val type: PlaylistSourceType get() = PlaylistSourceType.M3U_URL
        override fun toString(): String = "SourceReference.M3uUrl(url=***)"
    }
}
