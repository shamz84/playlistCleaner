package app.playlistplayer.core.model

/**
 * Xtream Codes account derived from a playlist's URLs. Bearer secret: it is held only in
 * SecureStore (never in Room) and [toString] never reveals host, username or password.
 *
 * [username] and [password] are stored decoded; URL builders must percent-encode them.
 * [port] is null when the URL used the scheme's default port.
 */
data class XtreamAccount(
    val scheme: String,
    val host: String,
    val port: Int?,
    val username: String,
    val password: String,
) {
    /** `scheme://host[:port]` without a trailing slash. Secret: never log it. */
    val baseUrl: String
        get() = buildString {
            append(scheme).append("://").append(host)
            if (port != null) append(':').append(port)
        }

    override fun toString(): String = "XtreamAccount(scheme=$scheme, host=***, port=***, username=***, password=***)"
}
