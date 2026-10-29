package app.playlistplayer

/**
 * Minimal local redactor for the debug harness only. The shared `core.network.Redactor` (U3) is
 * not merged on this branch yet; once it is, replace uses of this with it.
 *
 * Masks scheme authority (host/port and userinfo), Xtream credential paths
 * `/live|movie|series|timeshift/<u>/<p>/` and the short `/<u>/<p>/<id>` form, redirect token paths `/<kind>/play/<token>` and
 * `/hls/<token>`, and credential-like query values.
 */
internal object DebugRedactor {
    private const val MASK = "***"
    private val AUTHORITY = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^/\s?#"'<>]+""")
    private val CREDENTIAL_PATH = Regex("""(?i)/(live|movie|series|timeshift)/(?!play/)[^/\s?#"'<>]+/[^/\s?#"'<>]+(?=/)""")
    private val TOKEN_PATH = Regex("""(?i)/(live|movie|series)/play/[^/\s?#"'<>]+""")
    private val HLS_TOKEN_PATH = Regex("""(?i)/hls/[^/\s?#"'<>]+""")
    private val SHORT_XTREAM_PATH = Regex("""(?i)(://[^/\s?#"'<>]+)/(?!(?:live|movie|series|timeshift|hls|play)/)[^/\s?#"'<>]+/[^/\s?#"'<>]+/(\d+)(?=[.\s?#"'<>]|$)""")
    private val SECRET_QUERY = Regex("""(?i)([?&;])(username|password|user|pass|token|key)=[^&\s#"'<>]*""")

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        var out: String = text
        out = SHORT_XTREAM_PATH.replace(out) { "${it.groupValues[1]}/$MASK/$MASK/${it.groupValues[2]}" }
        out = CREDENTIAL_PATH.replace(out) { "/${it.groupValues[1]}/$MASK/$MASK" }
        out = TOKEN_PATH.replace(out) { "/${it.groupValues[1]}/play/$MASK" }
        out = HLS_TOKEN_PATH.replace(out) { "/hls/$MASK" }
        out = SECRET_QUERY.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}=$MASK" }
        out = AUTHORITY.replace(out) { "${it.groupValues[1]}$MASK" }
        return out
    }
}
