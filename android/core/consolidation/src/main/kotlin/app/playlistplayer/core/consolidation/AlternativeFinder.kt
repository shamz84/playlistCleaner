package app.playlistplayer.core.consolidation

import java.util.Locale

/**
 * A live playlist entry as the consolidation engine needs it. A later unit maps model types
 * onto this. [id] only needs to be unique within [playlistId].
 *
 * @property providerOrder playlist position, used as the final tie-break.
 */
data class LiveEntry(
    val id: String,
    val playlistId: String,
    val name: String,
    val tvgId: String? = null,
    val providerOrder: Int = 0,
)

/**
 * Finds "other versions" of a live channel. It never changes which channels a group lists;
 * it only answers "what else could play this channel?".
 *
 * Two entries are versions of each other when all of these hold:
 * - same playlist (entries from other playlists are never returned);
 * - neither is a separator header and both have a non-empty base name;
 * - same `+N` timeshift (so `Channel 4 +1` is never a version of `Channel 4`);
 * - compatible region and language (equal, or at least one unknown), so `UK: DISNEY JR` and
 *   `US: DISNEY JR` stay apart;
 * - names: when both have a `tvg-id`, the ids are equal (case-insensitive) and one base name
 *   is a whole-word prefix of the other (`bbc one` / `bbc one london`), because a shared
 *   `tvg-id` already identifies the channel and providers append a region word to the main
 *   feed. When either `tvg-id` is empty, the base names must be identical.
 *
 * Results are ordered by [VersionComparator] and capped at [MAX_ALTERNATIVES].
 *
 * The constructor tokenizes and indexes every entry once (by playlist, `tvg-id` and base
 * name), so a lookup touches only the entry's own buckets.
 */
class AlternativeFinder(entries: Iterable<LiveEntry>) {

    private class Indexed(
        val entry: LiveEntry,
        val tokens: TitleTokens,
        val tvgKey: String?,
        val version: Version,
    ) {
        val matchable: Boolean get() = !tokens.isSeparator && tokens.isParseable
    }

    private class PlaylistIndex {
        val byId = HashMap<String, Indexed>()
        val byTvg = HashMap<String, MutableList<Indexed>>()
        val byName = HashMap<String, MutableList<Indexed>>()
    }

    private val playlists = HashMap<String, PlaylistIndex>()

    init {
        for (entry in entries) {
            val indexed = index(entry)
            val playlist = playlists.getOrPut(entry.playlistId) { PlaylistIndex() }
            playlist.byId[entry.id] = indexed
            if (!indexed.matchable) continue
            indexed.tvgKey?.let { playlist.byTvg.getOrPut(it) { ArrayList(2) }.add(indexed) }
            playlist.byName.getOrPut(indexed.tokens.baseName) { ArrayList(2) }.add(indexed)
        }
    }

    /** Tokens for an indexed entry (tokenized on demand for unknown entries). */
    fun tokensOf(entry: LiveEntry): TitleTokens = lookup(entry).tokens

    /** The comparator view of an entry, e.g. for the failover controller. */
    fun versionOf(entry: LiveEntry): Version = lookup(entry).version

    /**
     * Up to [limit] other versions of [entry] from the same playlist, any group, best first.
     */
    fun alternativesFor(
        entry: LiveEntry,
        preferences: VersionPreferences = VersionPreferences(),
        limit: Int = MAX_ALTERNATIVES,
    ): List<LiveEntry> {
        if (limit <= 0) return emptyList()
        val playlist = playlists[entry.playlistId] ?: return emptyList()
        val self = lookup(entry)
        if (!self.matchable) return emptyList()

        val candidates = LinkedHashSet<Indexed>()
        val base = self.tokens.baseName
        if (self.tvgKey != null) {
            playlist.byTvg[self.tvgKey]?.forEach { other ->
                if (isWordPrefixRelated(base, other.tokens.baseName)) candidates += other
            }
            playlist.byName[base]?.forEach { other -> if (other.tvgKey == null) candidates += other }
        } else {
            playlist.byName[base]?.let { candidates += it }
        }

        val comparator = VersionComparator(preferences)
        return candidates.asSequence()
            .filter { it.entry.id != entry.id && compatible(self.tokens, it.tokens) }
            .sortedWith { a, b -> comparator.compare(a.version, b.version) }
            .take(limit)
            .map { it.entry }
            .toList()
    }

    private fun lookup(entry: LiveEntry): Indexed =
        playlists[entry.playlistId]?.byId?.get(entry.id)?.takeIf { it.entry == entry }
            ?: index(entry)

    private fun index(entry: LiveEntry): Indexed {
        val tokens = TitleTokenizer.tokenize(entry.name, TokenizeMode.LIVE)
        val tvgKey = entry.tvgId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        return Indexed(entry, tokens, tvgKey, Version.from(entry.id, tokens, entry.providerOrder))
    }

    private fun compatible(a: TitleTokens, b: TitleTokens): Boolean =
        a.timeshiftHours == b.timeshiftHours &&
            compatibleTag(a.region, b.region) &&
            compatibleTag(a.language, b.language)

    private fun compatibleTag(a: String?, b: String?): Boolean = a == null || b == null || a == b

    private fun isWordPrefixRelated(a: String, b: String): Boolean =
        a == b || isWordPrefix(a, b) || isWordPrefix(b, a)

    private fun isWordPrefix(prefix: String, of: String): Boolean =
        of.length > prefix.length && of.startsWith(prefix) && of[prefix.length] == ' '

    companion object {
        const val MAX_ALTERNATIVES: Int = 5
    }
}
