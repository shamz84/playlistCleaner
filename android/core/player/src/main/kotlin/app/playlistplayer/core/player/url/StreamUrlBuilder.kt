package app.playlistplayer.core.player.url

/** The user's live format setting. */
enum class LiveFormatPreference { TS, HLS }

/** The container actually requested for a live stream. */
enum class LiveStreamFormat(val extension: String) {
    TS("ts"),
    HLS("m3u8"),
}

/**
 * Builds Xtream stream URLs:
 * - live: `/live/{u}/{p}/{id}.{ts|m3u8}`
 * - movie: `/movie/{u}/{p}/{id}.{container}`
 * - series episode: `/series/{u}/{p}/{episodeId}.{container}`
 *
 * Every path segment is percent-encoded. The result contains credentials: never log it unredacted.
 */
class StreamUrlBuilder(private val account: StreamAccount) {

    fun live(streamId: String, format: LiveStreamFormat): String =
        build("live", streamId, format.extension)

    fun live(streamId: String, preference: LiveFormatPreference, allowedFormats: Collection<String>): String =
        live(streamId, selectLiveFormat(preference, allowedFormats))

    fun movie(streamId: String, containerExtension: String): String =
        build("movie", streamId, normalizeExtension(containerExtension))

    fun seriesEpisode(episodeId: String, containerExtension: String): String =
        build("series", episodeId, normalizeExtension(containerExtension))

    private fun build(kind: String, id: String, extension: String): String {
        require(id.isNotBlank()) { "Stream id is blank" }
        return buildString {
            append(account.origin)
            append('/').append(kind)
            append('/').append(UrlEncoding.encodeComponent(account.username))
            append('/').append(UrlEncoding.encodeComponent(account.password))
            append('/').append(UrlEncoding.encodeComponent(id.trim()))
            append('.').append(extension)
        }
    }

    companion object {
        private val EXTENSION = Regex("[A-Za-z0-9]{1,10}")

        /**
         * `.m3u8` only when the user chose HLS and the account's `allowed_output_formats` include
         * `m3u8`; `.ts` in every other case, including an empty or unknown format list.
         */
        fun selectLiveFormat(preference: LiveFormatPreference, allowedFormats: Collection<String>): LiveStreamFormat {
            val hlsAllowed = allowedFormats.any { it.trim().removePrefix(".").equals("m3u8", ignoreCase = true) }
            return if (preference == LiveFormatPreference.HLS && hlsAllowed) LiveStreamFormat.HLS else LiveStreamFormat.TS
        }

        /** Trims a leading dot and lower-cases; rejects anything but a short alphanumeric extension. */
        fun normalizeExtension(containerExtension: String): String {
            val ext = containerExtension.trim().removePrefix(".")
            require(EXTENSION.matches(ext)) { "Invalid container extension" }
            return ext.lowercase()
        }
    }
}
