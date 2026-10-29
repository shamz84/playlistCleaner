package app.playlistplayer.core.network

/**
 * The single redaction utility every log line and surfaced error message goes through.
 *
 * Masks:
 * - URL hosts and ports (provider hosts and redirect targets), except a small allowlist of public
 *   Google endpoints, plus any `user:pass@` userinfo.
 * - Xtream credential paths `/live|movie|series|timeshift/<u>/<p>/` and the short `/<u>/<p>/<id>`
 *   form after a host.
 * - Opaque redirect token paths `/live|movie|series/play/<token>` and `/hls/<token>`.
 * - Credential and secret query values: `username`, `password`, `token`, `key`, `id`,
 *   `resourcekey`, ...
 * - Drive file ids in `/drive/v3/files/<id>`.
 *
 * Redaction is idempotent: redacting already-redacted text changes nothing.
 */
object Redactor {
    const val MASK: String = "***"

    private val PUBLIC_HOSTS = setOf(
        "www.googleapis.com",
        "googleapis.com",
        "drive.usercontent.google.com",
        "drive.google.com",
        "accounts.google.com",
    )

    private val USERINFO = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^/\s@?#"'<>]+@""")
    private val AUTHORITY = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)([^/\s?#"'<>]+)""")
    private val CREDENTIAL_PATH = Regex("""(?i)/(live|movie|series|timeshift)/(?!play/)[^/\s?#"'<>]+/[^/\s?#"'<>]+(?=/)""")
    private val TOKEN_PATH = Regex("""(?i)/(live|movie|series)/play/[^/\s?#"'<>]+""")
    private val HLS_TOKEN_PATH = Regex("""(?i)/hls/[^/\s?#"'<>]+""")
    private val SHORT_XTREAM_PATH = Regex(
        """(?i)(://[^/\s?#"'<>]+)/(?!(?:live|movie|series|timeshift|hls|drive|download|play)/)[^/\s?#"'<>]+/[^/\s?#"'<>]+/(\d+)(?=[.\s?#"'<>]|$)""",
    )
    private val DRIVE_FILE_PATH = Regex("""(?i)/drive/v3/files/[^/\s?#"'<>]+""")
    private val SECRET_QUERY = Regex(
        """(?i)([?&;]|\b)(username|password|user|pass|pwd|token|access_token|key|id|resourcekey|file_id|fileid)=[^&\s#"'<>]*""",
    )

    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        var out = text
        out = USERINFO.replace(out) { "${it.groupValues[1]}$MASK@" }
        out = SHORT_XTREAM_PATH.replace(out) { "${it.groupValues[1]}/$MASK/$MASK/${it.groupValues[2]}" }
        out = CREDENTIAL_PATH.replace(out) { "/${it.groupValues[1]}/$MASK/$MASK" }
        out = TOKEN_PATH.replace(out) { "/${it.groupValues[1]}/play/$MASK" }
        out = HLS_TOKEN_PATH.replace(out) { "/hls/$MASK" }
        out = DRIVE_FILE_PATH.replace(out) { "/drive/v3/files/$MASK" }
        out = SECRET_QUERY.replace(out) { m ->
            val prefix = m.groupValues[1]
            // `\b` alternative only applies to a key at a word boundary (e.g. "password=x" in prose)
            "$prefix${m.groupValues[2]}=$MASK"
        }
        out = AUTHORITY.replace(out) { m ->
            val authority = m.groupValues[2]
            val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
            if (host in PUBLIC_HOSTS || authority == MASK || authority.endsWith("@$MASK")) {
                m.value
            } else {
                val userinfo = if ('@' in authority) authority.substringBeforeLast('@') + "@" else ""
                "${m.groupValues[1]}$userinfo$MASK"
            }
        }
        return out
    }

    /**
     * [redact] plus literal masking of [secrets] (e.g. a known host, username and password), for
     * text whose shape the patterns cannot recognise. Secrets shorter than 3 characters are ignored
     * to avoid masking unrelated text.
     */
    fun redact(text: String?, secrets: Collection<String>): String {
        var out = redact(text)
        for (secret in secrets.filter { it.length >= 3 }.sortedByDescending { it.length }) {
            out = out.replace(secret, MASK, ignoreCase = true)
        }
        return out
    }

    /**
     * Network exceptions whose messages name the host without a URL scheme (e.g.
     * `Unable to resolve host "..."`, `Failed to connect to host/ip:port`). Their messages are
     * dropped entirely.
     */
    private fun messageNamesHost(t: Throwable): Boolean =
        t is java.net.UnknownHostException ||
            t is java.net.ConnectException ||
            t is java.net.NoRouteToHostException ||
            t is java.net.SocketTimeoutException ||
            t is java.net.PortUnreachableException ||
            t is javax.net.ssl.SSLException

    private fun redactMessage(t: Throwable): String? {
        val message = t.message ?: return null
        return if (messageNamesHost(t)) MASK else redact(message)
    }

    /** Class name and redacted message of [t] and each cause, without stack frames. */
    fun redact(t: Throwable): String = buildString {
        var current: Throwable? = t
        var depth = 0
        val seen = HashSet<Throwable>()
        while (current != null && depth < MAX_CAUSES && seen.add(current)) {
            if (depth > 0) append("\nCaused by: ")
            append(current.javaClass.name)
            val message = redactMessage(current)
            if (message != null) append(": ").append(message)
            current = current.cause
            depth++
        }
    }

    /**
     * A copy of [t] (and its causes) whose messages are redacted, keeping the stack frames. Use it
     * wherever a Throwable is handed to a logger or reporter.
     */
    fun redacted(t: Throwable): Throwable = redactedCopy(t, HashSet(), 0)

    private fun redactedCopy(t: Throwable, seen: MutableSet<Throwable>, depth: Int): Throwable {
        if (t is RedactedThrowable) return t
        seen.add(t)
        val cause = t.cause?.takeIf { it !in seen && depth < MAX_CAUSES }?.let { redactedCopy(it, seen, depth + 1) }
        return RedactedThrowable(t.javaClass.name, redactMessage(t), cause).also {
            it.stackTrace = t.stackTrace
        }
    }

    private const val MAX_CAUSES = 8
}

/** Stand-in for a Throwable whose message went through [Redactor]. */
class RedactedThrowable(
    val originalClassName: String,
    redactedMessage: String?,
    cause: Throwable?,
) : Exception(redactedMessage, cause) {
    override fun toString(): String =
        if (message == null) originalClassName else "$originalClassName: $message"
}

/** Redacts this throwable's message chain for logs/UI. */
fun Throwable.redactedMessage(): String = Redactor.redact(this)
