package app.playlistplayer.core.consolidation

import java.util.Locale

/** Movies and series are separate namespaces and never share a title. */
enum class VodKind { MOVIE, SERIES }

/**
 * A VOD catalog item (one movie stream or one series) as the engine needs it.
 *
 * @property tmdb provider `tmdb` value as a string (int or string upstream); blank or `0`
 *   means absent.
 * @property categoryName provider category name, used for a language hint.
 * @property categoryLanguage explicit language hint for the category; wins over
 *   [categoryName] when both are given.
 */
data class VodItem(
    val id: String,
    val playlistId: String,
    val kind: VodKind,
    val name: String,
    val tmdb: String? = null,
    val categoryName: String? = null,
    val categoryLanguage: String? = null,
    val providerOrder: Int = 0,
)

/** One item inside a [VodTitle], with its parsed tokens and comparator view. */
data class VodVersion(
    val item: VodItem,
    val tokens: TitleTokens,
    val version: Version,
)

/**
 * A consolidated title.
 *
 * @property key stable identity across re-syncs (derived from playlist, kind, tmdb/name and
 *   year, or item ids for unparseable names and split overrides). Use it to store per-title
 *   state such as the manual pick.
 * @property versions in provider order.
 */
data class VodTitle(
    val key: String,
    val playlistId: String,
    val kind: VodKind,
    val title: String,
    val year: Int?,
    val tmdb: String?,
    val versions: List<VodVersion>,
) {
    fun rankedVersions(preferences: VersionPreferences = VersionPreferences()): List<VodVersion> {
        val comparator = VersionComparator(preferences)
        return versions.sortedWith { a, b -> comparator.compare(a.version, b.version) }
    }

    fun defaultVersion(preferences: VersionPreferences = VersionPreferences()): VodVersion =
        rankedVersions(preferences).first()
}

/**
 * Groups VOD items into titles, scoped per playlist and per [VodKind].
 *
 * Keys, in order of evaluation:
 * 1. Unparseable names (empty base name) form their own single-version title, never dropped.
 * 2. Dated items (`SOC - Arsenal - Liverpool 04.02.2024`, `WWE Raw (30/06/2025)`) key on base
 *    name + date and ignore tmdb: sports items reuse an unrelated film's tmdb (spike §3).
 * 3. Items with a tmdb are bucketed by tmdb, then split by year: a tmdb match is rejected when
 *    years differ. An item without a year joins a same-tmdb year cluster only when its base
 *    name equals a member's (`TOP - Avengers: Infinity War` joins the 2018 cluster); otherwise
 *    it forms a no-year cluster per base name.
 * 4. Items without a tmdb key on base name + year (a missing year never merges with a known
 *    year). Such an item joins a tmdb cluster only when exactly one cluster in scope has the
 *    same year (both known, or both missing) and a member with the same base name.
 *
 * Version language: the name's own language tag, else (platform prefix or no prefix) the
 * category language hint, else unknown.
 *
 * [overrides] are applied after grouping (see [Overrides]).
 */
object TitleGrouper {

    fun group(items: List<VodItem>, overrides: List<TitleOverride> = emptyList()): List<VodTitle> {
        val titles = ArrayList<VodTitle>()
        val scopes = LinkedHashMap<Pair<String, VodKind>, MutableList<VodItem>>()
        for (item in items) scopes.getOrPut(item.playlistId to item.kind) { ArrayList() }.add(item)
        for ((scope, scopeItems) in scopes) titles += groupScope(scope.first, scope.second, scopeItems)
        val applied = Overrides.apply(titles, overrides)
        return applied.sortedWith(compareBy<VodTitle>({ t -> t.versions.minOf { it.item.providerOrder } }, { it.key }))
    }

    /** Parses one item into a [VodVersion] (exposed for callers that rank ad hoc). */
    fun versionOf(item: VodItem): VodVersion {
        val tokens = TitleTokenizer.tokenize(item.name, TokenizeMode.VOD)
        val language = tokens.language
            ?: item.categoryLanguage?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            ?: TitleTokenizer.languageOfCategory(item.categoryName)
        return VodVersion(item, tokens, Version.from(item.id, tokens, item.providerOrder, language))
    }

    internal fun normalizeTmdb(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.all { it.isDigit() }) {
            val stripped = trimmed.trimStart('0')
            return stripped.ifEmpty { null }
        }
        return trimmed.lowercase(Locale.ROOT).takeUnless { it == "null" || it == "none" }
    }

    internal fun scopePrefix(playlistId: String, kind: VodKind): String =
        "$playlistId|${kind.name.lowercase(Locale.ROOT)}"

    private class Cluster(val key: String, val year: Int?, val tmdb: String?) {
        val members = ArrayList<VodVersion>()
        val names = HashSet<String>()
        fun add(v: VodVersion) {
            members += v
            names += v.tokens.baseName
        }
    }

    private fun groupScope(playlistId: String, kind: VodKind, items: List<VodItem>): List<VodTitle> {
        val prefix = scopePrefix(playlistId, kind)
        val clusters = LinkedHashMap<String, Cluster>()
        fun cluster(key: String, year: Int?, tmdb: String?) =
            clusters.getOrPut("$prefix|$key") { Cluster("$prefix|$key", year, tmdb) }

        val tmdbBuckets = LinkedHashMap<String, MutableList<VodVersion>>()
        val fallback = ArrayList<VodVersion>()
        for (item in items) {
            val v = versionOf(item)
            val tokens = v.tokens
            val tmdb = normalizeTmdb(item.tmdb)
            when {
                !tokens.isParseable -> cluster("item:${item.id}", tokens.year, tmdb).add(v)
                tokens.date != null -> cluster("name:${tokens.baseName}|date:${tokens.date}", tokens.year, null).add(v)
                tmdb != null -> tmdbBuckets.getOrPut(tmdb) { ArrayList() }.add(v)
                else -> fallback += v
            }
        }

        // tmdb clusters, indexed by (base name, year) for step 4.
        val tmdbByNameYear = HashMap<Pair<String, Int?>, MutableSet<Cluster>>()
        for ((tmdb, bucket) in tmdbBuckets) {
            val created = LinkedHashSet<Cluster>()
            val yearClusters = LinkedHashMap<Int, Cluster>()
            for (v in bucket) {
                val year = v.tokens.year ?: continue
                yearClusters.getOrPut(year) { cluster("tmdb:$tmdb|year:$year", year, tmdb) }
                    .also { created += it }
                    .add(v)
            }
            for (v in bucket) {
                if (v.tokens.year != null) continue
                val base = v.tokens.baseName
                val target = yearClusters.values
                    .filter { base in it.names }
                    .maxWithOrNull(
                        compareBy<Cluster>(
                            { c -> c.members.count { it.tokens.baseName == base } },
                            { it.members.size },
                            { -(it.year ?: 0) },
                        ),
                    )
                    ?: cluster("tmdb:$tmdb|name:$base|year:none", null, tmdb).also { created += it }
                target.add(v)
            }
            for (c in created) for (name in c.names) {
                tmdbByNameYear.getOrPut(name to c.year) { LinkedHashSet() }.add(c)
            }
        }

        for (v in fallback) {
            val base = v.tokens.baseName
            val year = v.tokens.year
            val matches = tmdbByNameYear[base to year]
            val target = matches?.singleOrNull()
                ?: cluster("name:$base|year:${year ?: "none"}", year, null)
            target.add(v)
        }

        return clusters.values.map { c ->
            buildTitle(c.key, playlistId, kind, c.members, c.year, c.tmdb)
        }
    }

    /** Builds a title; display name is the most common clean title, ties by provider order. */
    internal fun buildTitle(
        key: String,
        playlistId: String,
        kind: VodKind,
        members: List<VodVersion>,
        year: Int? = mostCommon(members.mapNotNull { it.tokens.year }),
        tmdb: String? = mostCommon(members.mapNotNull { normalizeTmdb(it.item.tmdb) }),
    ): VodTitle {
        val versions = members.sortedWith(compareBy({ it.item.providerOrder }, { it.item.id }))
        val title = mostCommon(versions.map { it.tokens.cleanTitle }.filter { it.isNotBlank() })
            ?: versions.first().item.name.trim()
        return VodTitle(key, playlistId, kind, title, year, tmdb, versions)
    }

    /** Most frequent value; ties go to the earliest occurrence. */
    private fun <T> mostCommon(values: List<T>): T? {
        if (values.isEmpty()) return null
        val counts = LinkedHashMap<T, Int>()
        for (v in values) counts[v] = (counts[v] ?: 0) + 1
        return counts.maxByOrNull { it.value }?.key
    }
}
