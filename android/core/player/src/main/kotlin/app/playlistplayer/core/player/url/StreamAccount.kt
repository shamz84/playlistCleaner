package app.playlistplayer.core.player.url

/**
 * The Xtream account parts needed to build stream URLs.
 *
 * A local input type for URL building; later units map the account model onto it.
 * [toString] redacts the credentials so the account can never leak into logs.
 */
class StreamAccount(
    scheme: String,
    host: String,
    val port: Int? = null,
    val username: String,
    val password: String,
) {
    /** Lower-case `http` or `https`. */
    val scheme: String = scheme.trim().lowercase()

    /** Host name or IPv4 address, without scheme, port or path. */
    val host: String = host.trim()

    init {
        require(this.scheme == "http" || this.scheme == "https") { "Unsupported scheme" }
        require(this.host.isNotEmpty() && this.host.none { it in FORBIDDEN_HOST_CHARS || it.isWhitespace() }) {
            "Invalid host"
        }
        require(port == null || port in 1..65535) { "Invalid port" }
        require(username.isNotEmpty()) { "Username is empty" }
        require(password.isNotEmpty()) { "Password is empty" }
    }

    /** `scheme://host[:port]`; carries no credentials. */
    val origin: String
        get() = if (port == null) "$scheme://$host" else "$scheme://$host:$port"

    override fun equals(other: Any?): Boolean =
        other is StreamAccount &&
            scheme == other.scheme &&
            host == other.host &&
            port == other.port &&
            username == other.username &&
            password == other.password

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + (port ?: 0)
        result = 31 * result + username.hashCode()
        result = 31 * result + password.hashCode()
        return result
    }

    override fun toString(): String = "StreamAccount(scheme=$scheme, host=<redacted>, username=<redacted>, password=<redacted>)"

    private companion object {
        const val FORBIDDEN_HOST_CHARS = "/?#@:\\"
    }
}
