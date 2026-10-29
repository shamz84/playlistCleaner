package app.playlistplayer.core.parsing.xmltv

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Base64

/**
 * One entry of `epg_listings` from Xtream `get_short_epg` or `get_simple_data_table`, as raw
 * strings (the provider sends every value as a string; numbers may also arrive as JSON numbers,
 * which the JSON layer should pass through as their text). This module has no JSON library: the
 * network module decodes the response (kotlinx.serialization, lenient) and maps it to this type.
 *
 * [startTimestamp]/[stopTimestamp] are carried only so callers can map the payload 1:1; they are
 * never used. The provider encodes server-local wall time as if it were UTC, which makes them an
 * hour late under CET (see `android/docs/provider-spike.md` §7).
 */
data class XtreamEpgListing(
    val id: String? = null,
    val epgId: String? = null,
    val channelId: String? = null,
    val streamId: String? = null,
    /** Base64 (UTF-8). */
    val title: String? = null,
    /** Base64 (UTF-8). */
    val description: String? = null,
    val lang: String? = null,
    /** Server-local wall time, `yyyy-MM-dd HH:mm:ss`. */
    val start: String? = null,
    /** Server-local wall time, `yyyy-MM-dd HH:mm:ss`. Note: the JSON key is `end`. */
    val end: String? = null,
    val startTimestamp: String? = null,
    val stopTimestamp: String? = null,
    /** `get_simple_data_table` only; `"1"`/`"0"`. */
    val nowPlaying: String? = null,
    /** `get_simple_data_table` only; `"1"`/`"0"`. */
    val hasArchive: String? = null,
)

/**
 * A decoded Xtream EPG entry. Times are UTC epoch millis derived from the `start`/`end` strings.
 * [providerNowPlaying] and [providerHasArchive] are the raw provider flags; `now_playing` is
 * computed from the skewed timestamps and is therefore wrong, so use [isAiringAt] instead.
 */
data class XtreamEpgProgramme(
    val id: String?,
    val epgId: String?,
    val channelId: String?,
    val streamId: String?,
    val title: String?,
    val description: String?,
    val lang: String?,
    val startMillis: Long,
    val stopMillis: Long,
    val providerNowPlaying: Boolean?,
    val providerHasArchive: Boolean?,
) {
    fun isAiringAt(nowMillis: Long): Boolean = nowMillis >= startMillis && nowMillis < stopMillis

    /** True when the programme has ended and started no earlier than [archiveDays] before now. */
    fun isInArchiveWindow(nowMillis: Long, archiveDays: Int): Boolean =
        stopMillis <= nowMillis && startMillis >= nowMillis - archiveDays * 86_400_000L
}

data class XtreamEpgDecodeResult(
    /** Valid entries in input order. */
    val programmes: List<XtreamEpgProgramme>,
    /** Entries dropped for a missing/unparseable start or end, or end <= start. */
    val skipped: Int,
)

/** Pure decoding for Xtream `get_short_epg` / `get_simple_data_table` listings. No networking. */
object XtreamEpgDecoder {

    private val WALL_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd[' ']['T']HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT)

    /**
     * Decodes [listings]. [serverZone] is `server_info.timezone` from `player_api.php` (e.g.
     * `Europe/Amsterdam`); its DST rules are applied per date.
     */
    fun decode(listings: List<XtreamEpgListing>, serverZone: ZoneId): XtreamEpgDecodeResult {
        val out = ArrayList<XtreamEpgProgramme>(listings.size)
        var skipped = 0
        for (listing in listings) {
            val programme = decodeListing(listing, serverZone)
            if (programme == null) skipped++ else out += programme
        }
        return XtreamEpgDecodeResult(out, skipped)
    }

    /** Decodes one entry, or `null` if its times are missing or invalid. */
    fun decodeListing(listing: XtreamEpgListing, serverZone: ZoneId): XtreamEpgProgramme? {
        val startLocal = parseWallTime(listing.start) ?: return null
        val endLocal = parseWallTime(listing.end) ?: return null
        val start = ZonedDateTime.ofLocal(startLocal, serverZone, null)
        var end = ZonedDateTime.ofLocal(endLocal, serverZone, null)
        // Autumn fall-back overlap: an end wall time that only makes sense after the clock change
        // (e.g. 02:45 CEST -> 02:15) resolves to the later offset.
        if (!end.isAfter(start)) end = end.withLaterOffsetAtOverlap()
        if (!end.isAfter(start)) return null
        return XtreamEpgProgramme(
            id = listing.id,
            epgId = listing.epgId,
            channelId = listing.channelId,
            streamId = listing.streamId,
            title = decodeBase64Text(listing.title),
            description = decodeBase64Text(listing.description),
            lang = listing.lang?.ifBlank { null },
            startMillis = start.toInstant().toEpochMilli(),
            stopMillis = end.toInstant().toEpochMilli(),
            providerNowPlaying = parseFlag(listing.nowPlaying),
            providerHasArchive = parseFlag(listing.hasArchive),
        )
    }

    /**
     * Parses a server-local wall time (`yyyy-MM-dd HH:mm[:ss]`) in [serverZone] to epoch millis.
     * Overlapping local times (autumn fall-back) resolve to the earlier offset; gap times (spring
     * forward) shift forward by the gap length.
     */
    fun parseServerWallTime(raw: String?, serverZone: ZoneId): Long? {
        val local = parseWallTime(raw) ?: return null
        return ZonedDateTime.ofLocal(local, serverZone, null).toInstant().toEpochMilli()
    }

    /** The programme airing at [nowMillis] (recomputed from the parsed times), if any. */
    fun nowPlaying(programmes: List<XtreamEpgProgramme>, nowMillis: Long): XtreamEpgProgramme? =
        programmes.firstOrNull { it.isAiringAt(nowMillis) }

    /**
     * Decodes provider base64 text as UTF-8. Tolerates missing padding and embedded whitespace.
     * If [raw] is not valid base64 or not valid UTF-8, it is returned unchanged (some panels send
     * plain text). Blank input yields `null`.
     */
    fun decodeBase64Text(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val compact = raw.filterNot { it.isWhitespace() }
        val padded = when (compact.length % 4) {
            2 -> "$compact=="
            3 -> "$compact="
            else -> compact
        }
        val bytes = try {
            Base64.getDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            return raw.trim()
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return raw.trim()
        }
        return text.trim().ifEmpty { null }
    }

    private fun parseWallTime(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(raw.trim(), WALL_TIME)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseFlag(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }
}
