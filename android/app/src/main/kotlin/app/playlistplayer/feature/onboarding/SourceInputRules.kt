package app.playlistplayer.feature.onboarding

import java.net.URI
import java.net.URISyntaxException

/** Outcome of reading the Drive field: a bare ID, an ID extracted from a share link, or nothing usable. */
sealed interface DriveFieldInput {
    data object Empty : DriveFieldInput

    /** The text is a URL but no Drive file ID could be found in it. */
    data object UnrecognisedLink : DriveFieldInput

    /** [fromLink] is true when [id] (and [resourceKey], if any) came out of a pasted share link. */
    data class Parsed(val id: String, val resourceKey: String?, val fromLink: Boolean) : DriveFieldInput {
        override fun toString(): String = "Parsed(id=***, resourceKey=${if (resourceKey == null) "null" else "***"}, fromLink=$fromLink)"
    }
}

/**
 * Input assist for the Drive field (U9): accepts a bare file ID or a full share link and extracts
 * the ID and optional `resourcekey`.
 *
 * Recognised link shapes (any `*.google.com` host, with or without scheme):
 * - `https://drive.google.com/file/d/<id>/view?usp=sharing&resourcekey=<key>` (also `/file/u/0/d/<id>`)
 * - `https://drive.google.com/open?id=<id>`
 * - `https://drive.google.com/uc?id=<id>&export=download` (also `uc?export=download&id=<id>`)
 */
object DriveLinkParser {
    private val pathId = Regex("/d/([^/?#&]+)")

    fun parse(raw: String): DriveFieldInput {
        val text = raw.trim()
        if (text.isEmpty()) return DriveFieldInput.Empty
        if (!looksLikeLink(text)) return DriveFieldInput.Parsed(text, resourceKey = null, fromLink = false)

        val withoutFragment = text.substringBefore('#')
        val host = hostOf(withoutFragment)
        if (host == null || !(host == "google.com" || host.endsWith(".google.com"))) {
            return DriveFieldInput.UnrecognisedLink
        }
        val path = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        val params = parseQuery(query)

        val id = pathId.find(path)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            ?: params["id"]?.takeIf { it.isNotEmpty() }
            ?: return DriveFieldInput.UnrecognisedLink
        val resourceKey = params["resourcekey"]?.takeIf { it.isNotEmpty() }
        return DriveFieldInput.Parsed(id, resourceKey, fromLink = true)
    }

    private fun looksLikeLink(text: String): Boolean =
        "://" in text || '/' in text || '?' in text || '=' in text ||
            text.startsWith("drive.google.", ignoreCase = true) || text.startsWith("docs.google.", ignoreCase = true)

    private fun hostOf(link: String): String? {
        val afterScheme = if ("://" in link) link.substringAfter("://") else link
        val authority = afterScheme.substringBefore('/').substringBefore('?')
        val host = authority.substringAfter('@').substringBefore(':').lowercase()
        return host.takeIf { it.isNotEmpty() }
    }

    /** Query parameters with lower-case names; the first occurrence wins. */
    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val name = pair.substringBefore('=').lowercase()
            val value = pair.substringAfter('=', missingDelimiterValue = "")
            out.putIfAbsent(name, value)
        }
        return out
    }
}

/** Live validation of the Drive field, shown as an inline hint. */
enum class DriveIdValidation {
    Empty,
    UnrecognisedLink,
    InvalidCharacters,
    TooShort,
    TooLong,
    Valid,
    ;

    val isValid: Boolean get() = this == Valid
}

object DriveIdRules {
    const val MIN_LENGTH = 25
    const val MAX_LENGTH = 60
    private val allowed = Regex("[A-Za-z0-9_-]+")

    /** Letters, digits, `-` and `_` only, 25–60 characters. */
    fun validate(id: String): DriveIdValidation = when {
        id.isEmpty() -> DriveIdValidation.Empty
        !allowed.matches(id) -> DriveIdValidation.InvalidCharacters
        id.length < MIN_LENGTH -> DriveIdValidation.TooShort
        id.length > MAX_LENGTH -> DriveIdValidation.TooLong
        else -> DriveIdValidation.Valid
    }

    fun validate(input: DriveFieldInput): DriveIdValidation = when (input) {
        DriveFieldInput.Empty -> DriveIdValidation.Empty
        DriveFieldInput.UnrecognisedLink -> DriveIdValidation.UnrecognisedLink
        is DriveFieldInput.Parsed -> validate(input.id)
    }

    /**
     * The ID split into groups of [groupSize] characters separated by spaces, for reading back in
     * large monospace text (e.g. `1AbC dEf9 ...`). The spaces are display-only.
     */
    fun groupForDisplay(id: String, groupSize: Int = 4): String {
        require(groupSize > 0) { "groupSize must be positive" }
        return id.chunked(groupSize).joinToString(" ")
    }
}

enum class M3uUrlValidation {
    Empty,

    /** Not an http:// or https:// URL. */
    NotHttp,

    /** http(s) but unparseable or without a host. */
    Malformed,
    Valid,
    ;

    val isValid: Boolean get() = this == Valid
}

object M3uUrlRules {
    fun validate(raw: String): M3uUrlValidation {
        val text = raw.trim()
        if (text.isEmpty()) return M3uUrlValidation.Empty
        val scheme = text.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return M3uUrlValidation.NotHttp
        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            return M3uUrlValidation.Malformed
        }
        // URI.host is null for hosts with '_' (legal in DNS labels used by some providers); fall back to the authority.
        val host = uri.host ?: uri.rawAuthority?.substringAfter('@')?.substringBefore(':')
        return if (host.isNullOrBlank()) M3uUrlValidation.Malformed else M3uUrlValidation.Valid
    }
}
