package app.playlistplayer.core.player.url

/**
 * Percent-encoding for URL path segments and query values.
 *
 * Everything except RFC 3986 unreserved characters (`A-Z a-z 0-9 - . _ ~`) is encoded as UTF-8
 * `%XX`, so `/`, `?`, `#`, `&`, `=`, `@`, `:`, `%`, `+` and spaces in credentials or ids cannot
 * change the URL structure. Spaces become `%20`, never `+`.
 */
object UrlEncoding {
    private const val HEX = "0123456789ABCDEF"

    fun encodeComponent(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (isUnreserved(c)) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private fun isUnreserved(c: Int): Boolean =
        c in 'A'.code..'Z'.code ||
            c in 'a'.code..'z'.code ||
            c in '0'.code..'9'.code ||
            c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
}
