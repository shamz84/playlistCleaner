package app.playlistplayer.core.parsing.xmltv

import java.text.Normalizer
import java.util.Locale

/**
 * A playlist channel to map to guide data.
 *
 * @param key unique per entry (e.g. the stream id); results are keyed by it. Entries that share a
 *   `tvg-id` have different keys and resolve to the same guide channel.
 * @param tvgId playlist `tvg-id`.
 * @param epgChannelId Xtream `get_live_streams` `epg_channel_id` for the same stream id, if any.
 * @param name playlist display name, for the name fallback.
 */
data class EpgPlaylistChannel(
    val key: String,
    val tvgId: String?,
    val epgChannelId: String? = null,
    val name: String? = null,
)

enum class EpgMatchMethod { EXACT_ID, CASE_INSENSITIVE_ID, DISPLAY_NAME }

data class EpgChannelMatch(
    /** The guide channel id exactly as in the XMLTV document. */
    val xmltvChannelId: String,
    val method: EpgMatchMethod,
)

data class EpgMatchResult(
    /** Matched entries by [EpgPlaylistChannel.key]; unmatched entries are absent. */
    val matches: Map<String, EpgChannelMatch>,
    /** Distinct entry keys considered. */
    val totalEntries: Int,
) {
    val matchedEntries: Int get() = matches.size

    /** Matched / total, in `[0, 1]`; `0.0` when there are no entries. Stored for Settings. */
    val matchRate: Double get() = if (totalEntries == 0) 0.0 else matchedEntries.toDouble() / totalEntries

    /** The guide channel ids to request from [XmltvParser] (exact document spelling). */
    val wantedXmltvChannelIds: Set<String> get() = matches.values.mapTo(LinkedHashSet()) { it.xmltvChannelId }

    fun countBy(method: EpgMatchMethod): Int = matches.values.count { it.method == method }
}

/**
 * Maps playlist channels to XMLTV channels. For each entry the first rule that matches wins:
 *
 * 1. Exact id: `tvg-id`, then `epg_channel_id`.
 * 2. Case-insensitive id: `tvg-id`, then `epg_channel_id`. The provider feed contains ids that
 *    differ only by case (`skysportsmainevent.uk` / `SkySportsMainEvent.uk`), which is why exact
 *    matching runs first; among case-insensitive collisions the first in document order wins.
 * 3. Normalized display name ([normalizeName]) against every `display-name` of every channel,
 *    first channel in document order wins. Disabled with `matchByName = false`.
 */
object EpgChannelMatcher {

    fun match(
        entries: Collection<EpgPlaylistChannel>,
        xmltvChannels: Collection<XmltvChannel>,
        matchByName: Boolean = true,
    ): EpgMatchResult {
        val exact = HashMap<String, String>(xmltvChannels.size * 2)
        val folded = HashMap<String, String>(xmltvChannels.size * 2)
        val byName = HashMap<String, String>()
        for (channel in xmltvChannels) {
            val id = channel.id.trim()
            if (id.isEmpty()) continue
            exact.putIfAbsent(id, id)
            folded.putIfAbsent(id.idKey(), id)
            if (matchByName) {
                for (displayName in channel.displayNames) {
                    val normalized = normalizeName(displayName)
                    if (normalized.isNotEmpty()) byName.putIfAbsent(normalized, id)
                }
            }
        }

        val matches = LinkedHashMap<String, EpgChannelMatch>()
        val keys = HashSet<String>()
        for (entry in entries) {
            if (!keys.add(entry.key)) continue
            val ids = listOfNotNull(entry.tvgId?.trim()?.ifEmpty { null }, entry.epgChannelId?.trim()?.ifEmpty { null })
            val match = ids.firstNotNullOfOrNull { exact[it] }?.let { EpgChannelMatch(it, EpgMatchMethod.EXACT_ID) }
                ?: ids.firstNotNullOfOrNull { folded[it.idKey()] }
                    ?.let { EpgChannelMatch(it, EpgMatchMethod.CASE_INSENSITIVE_ID) }
                ?: entry.name?.takeIf { matchByName }
                    ?.let { byName[normalizeName(it)] }
                    ?.let { EpgChannelMatch(it, EpgMatchMethod.DISPLAY_NAME) }
            if (match != null) matches[entry.key] = match
        }
        return EpgMatchResult(matches, keys.size)
    }

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val RESOLUTION = Regex("\\d{3,4}[pi]\\d{0,3}")
    private val QUALITY_TOKENS = setOf(
        "sd", "hd", "fhd", "uhd", "qhd", "hq", "lq", "4k", "8k", "raw", "hevc", "h264", "h265", "hdr",
        "fps", "50fps", "60fps",
    )

    /**
     * Folds a channel name for comparison: NFKD (so superscript labels such as `ᴴᴰ`/`ᴿᴬᵂ` become
     * ASCII), diacritics removed, lower case, punctuation/symbols (`◉`, `:`, `/`) dropped, and
     * quality/resolution tokens removed (versions of one channel share a guide). Region prefixes
     * like `UK` are kept. Returns an empty string when nothing remains.
     */
    fun normalizeName(name: String): String {
        val folded = COMBINING_MARKS.replace(Normalizer.normalize(name, Normalizer.Form.NFKD), "")
            .lowercase(Locale.ROOT)
        return NON_ALNUM.split(folded)
            .filter { it.isNotEmpty() && it !in QUALITY_TOKENS && !RESOLUTION.matches(it) }
            .joinToString(" ")
    }
}
