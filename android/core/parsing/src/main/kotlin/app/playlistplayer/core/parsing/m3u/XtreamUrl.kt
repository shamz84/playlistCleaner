package app.playlistplayer.core.parsing.m3u

import app.playlistplayer.core.model.XtreamAccount
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * The parts of an Xtream-shaped URL. Secret: holds credentials; never log or persist it.
 *
 * Recognised shapes:
 * - stream paths `/live|movie|series/<user>/<pass>/<id>[.<ext>]`
 * - the short live form `/<user>/<pass>/<id>[.<ext>]`
 * - API/playlist endpoints `/get.php|player_api.php|xmltv.php?username=<u>&password=<p>`
 */
class XtreamUrl internal constructor(
    val scheme: String,
    val host: String,
    val port: Int?,
    val kind: Kind,
    val username: String,
    val password: String,
    val streamId: Long?,
    val extension: String?,
) {
    enum class Kind { LIVE, MOVIE, SERIES, SHORT_LIVE, API }

    val account: XtreamAccount get() = XtreamAccount(scheme, host, port, username, password)

    override fun toString(): String = "XtreamUrl(kind=$kind, streamId=$streamId, extension=$extension)"

    companion object {
        // scheme://[userinfo@]host[:port][path][?query][#fragment]
        private val URL = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://(?:[^/?#@]*@)?([^/?#:]+|\[[^\]]+])(?::(\d{1,5}))?([^?#]*)(?:\?([^#]*))?""")
        private val STREAM_PATH = Regex("""^/(live|movie|series)/([^/]+)/([^/]+)/(\d+)(?:\.([A-Za-z0-9]+))?/?$""")
        private val SHORT_PATH = Regex("""^/([^/]+)/([^/]+)/(\d+)(?:\.([A-Za-z0-9]+))?/?$""")
        private val API_PATH = Regex("""^/(?:[^?#]*/)?(get|player_api|xmltv|panel_api)\.php$""", RegexOption.IGNORE_CASE)

        /** Parses [url], returning null when it is not Xtream-shaped. Never throws. */
        fun parse(url: String): XtreamUrl? {
            val match = URL.find(url.trim()) ?: return null
            val scheme = match.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = match.groupValues[2]
            val port = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
                ?.takeUnless { (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
            val path = match.groupValues[4]
            val query = match.groupValues[5]

            STREAM_PATH.matchEntire(path)?.let { m ->
                val kind = when (m.groupValues[1]) {
                    "live" -> Kind.LIVE
                    "movie" -> Kind.MOVIE
                    else -> Kind.SERIES
                }
                return XtreamUrl(
                    scheme, host, port, kind,
                    username = decodePath(m.groupValues[2]),
                    password = decodePath(m.groupValues[3]),
                    streamId = m.groupValues[4].toLongOrNull(),
                    extension = m.groupValues[5].takeIf { it.isNotEmpty() }?.lowercase(),
                )
            }
            if (API_PATH.matches(path)) {
                val params = parseQuery(query)
                val user = params["username"]
                val pass = params["password"]
                if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                    return XtreamUrl(scheme, host, port, Kind.API, user, pass, streamId = null, extension = null)
                }
                return null
            }
            SHORT_PATH.matchEntire(path)?.let { m ->
                return XtreamUrl(
                    scheme, host, port, Kind.SHORT_LIVE,
                    username = decodePath(m.groupValues[1]),
                    password = decodePath(m.groupValues[2]),
                    streamId = m.groupValues[3].toLongOrNull(),
                    extension = m.groupValues[4].takeIf { it.isNotEmpty() }?.lowercase(),
                )
            }
            return null
        }

        private fun parseQuery(query: String): Map<String, String> {
            if (query.isEmpty()) return emptyMap()
            val result = HashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = decode(if (eq < 0) pair else pair.substring(0, eq)).lowercase()
                val value = if (eq < 0) "" else decode(pair.substring(eq + 1))
                result.putIfAbsent(key, value)
            }
            return result
        }

        /** Path segments: '+' is literal, only percent-escapes decode. */
        private fun decodePath(value: String): String = decode(value.replace("+", "%2B"))

        private fun decode(value: String): String =
            if ('%' !in value && '+' !in value) {
                value
            } else {
                try {
                    URLDecoder.decode(value, StandardCharsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    value
                }
            }
    }
}
