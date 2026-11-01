package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.TitleTokenizer
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.database.TrendingStore
import app.playlistplayer.core.database.entity.TrendingTitleEntity
import app.playlistplayer.core.network.Redactor
import app.playlistplayer.core.network.RedactingLogger
import app.playlistplayer.core.network.tmdb.TmdbMediaType
import app.playlistplayer.core.network.tmdb.TmdbTrendingSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** One TMDB trending entry, in rank order. */
data class TrendingEntry(val tmdbId: Long, val title: String, val originalTitle: String?, val year: Int?)

/** A cached trending list and when it was fetched. */
data class CachedTrendingList(val fetchedAt: Long, val entries: List<TrendingEntry>)

/** Where trending lists are kept between launches (Room in the app). */
interface TrendingCache {
    suspend fun cached(kind: VodKind): CachedTrendingList?
    suspend fun replace(kind: VodKind, entries: List<TrendingEntry>, fetchedAt: Long)
}

class RoomTrendingCache(private val store: TrendingStore) : TrendingCache {
    override suspend fun cached(kind: VodKind): CachedTrendingList? = store.cached(kind.name)?.let { c ->
        CachedTrendingList(c.fetchedAt, c.titles.map { TrendingEntry(it.tmdbId, it.title, it.originalTitle, it.year) })
    }

    override suspend fun replace(kind: VodKind, entries: List<TrendingEntry>, fetchedAt: Long) = store.replace(
        kind.name,
        entries.mapIndexed { i, e -> TrendingTitleEntity(kind.name, i, e.tmdbId, e.title, e.originalTitle, e.year) },
        fetchedAt,
    )
}

class InMemoryTrendingCache : TrendingCache {
    private val lists = HashMap<VodKind, CachedTrendingList>()
    override suspend fun cached(kind: VodKind): CachedTrendingList? = synchronized(lists) { lists[kind] }
    override suspend fun replace(kind: VodKind, entries: List<TrendingEntry>, fetchedAt: Long) {
        synchronized(lists) { lists[kind] = CachedTrendingList(fetchedAt, entries) }
    }
}

/** What the browse screen reads: the trending list for a kind, or null to hide the row. */
fun interface TrendingProvider {
    suspend fun trending(kind: VodKind): List<TrendingEntry>?
}

/**
 * TMDB weekly trending (U18, R32), refreshed at most once per [maxAgeMs] (a day).
 *
 * - No key ([source] null) or a failed fetch → null: the Trending row is hidden and the rest of
 *   the screen is unaffected. After a failure the next attempt waits [retryAfterFailureMs].
 * - Errors are logged with [secrets] (the API key) scrubbed and without the throwable, whose
 *   chain could carry the request URL.
 */
class TrendingRepository(
    private val source: TmdbTrendingSource?,
    private val cache: TrendingCache,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: RedactingLogger = RedactingLogger.NONE,
    private val secrets: Collection<String> = emptyList(),
    private val pages: Int = DEFAULT_PAGES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val retryAfterFailureMs: Long = DEFAULT_RETRY_AFTER_FAILURE_MS,
) : TrendingProvider {
    private val mutex = Mutex()
    private val lastFailure = HashMap<VodKind, Long>()

    override suspend fun trending(kind: VodKind): List<TrendingEntry>? {
        val tmdb = source ?: return null
        return mutex.withLock {
            val now = clock()
            val cached = try {
                cache.cached(kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (cached != null && now >= cached.fetchedAt && now - cached.fetchedAt < maxAgeMs) return@withLock cached.entries
            val failedAt = lastFailure[kind]
            if (failedAt != null && now >= failedAt && now - failedAt < retryAfterFailureMs) return@withLock null
            try {
                val type = if (kind == VodKind.MOVIE) TmdbMediaType.MOVIE else TmdbMediaType.TV
                val entries = tmdb.weeklyTrending(type, pages).map { TrendingEntry(it.tmdbId, it.title, it.originalTitle, it.year) }
                cache.replace(kind, entries, now)
                lastFailure.remove(kind)
                entries
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure[kind] = now
                logger.w("Trending ${kind.name} unavailable: ${e.javaClass.simpleName}: ${Redactor.redact(e.message, secrets)}")
                null
            }
        }
    }

    companion object {
        const val DEFAULT_PAGES: Int = 3
        const val DEFAULT_MAX_AGE_MS: Long = 24L * 60 * 60 * 1000
        const val DEFAULT_RETRY_AFTER_FAILURE_MS: Long = 15L * 60 * 1000
    }
}

/**
 * Matches trending entries to a playlist's merged titles (R32): by the provider `tmdb` id, else
 * (for titles with no tmdb id) by normalized title + year. TMDB never adds titles: the result is
 * a subset of [titles], in TMDB rank order.
 */
object TrendingMatcher {

    fun match(entries: List<TrendingEntry>, titles: List<CatalogTitle>, kind: VodKind, limit: Int = Int.MAX_VALUE): List<CatalogTitle> {
        val byTmdb = HashMap<String, MutableList<CatalogTitle>>()
        val byName = HashMap<String, MutableList<CatalogTitle>>()
        for (t in titles) {
            val ids = t.title.versions.mapNotNullTo(HashSet()) { normalizeTmdb(it.item.tmdb) }
            if (ids.isEmpty()) {
                val name = TitleTokenizer.normalize(t.title.title)
                if (name.isNotEmpty()) byName.getOrPut(name) { ArrayList() } += t
            } else {
                ids.forEach { byTmdb.getOrPut(it) { ArrayList() } += t }
            }
        }
        val out = LinkedHashMap<String, CatalogTitle>()
        for (e in entries) {
            if (out.size >= limit) break
            val names = listOfNotNull(e.title, e.originalTitle).map(TitleTokenizer::normalize).filter { it.isNotEmpty() }.distinct()
            // A provider tmdb id can be reused by unrelated items (sports reuse a film's id): reject
            // differing years, and without a year on either side require the same title.
            byTmdb[e.tmdbId.toString()]?.filter { t ->
                val year = t.releaseYear
                if (year != null && e.year != null) abs(year - e.year) <= 1 else TitleTokenizer.normalize(t.title.title) in names
            }?.forEach { out.putIfAbsent(it.key, it) }
            for (name in names) {
                byName[name]?.filter { titleYearMatches(it.releaseYear, e.year, kind) }?.forEach { out.putIfAbsent(it.key, it) }
            }
        }
        return out.values.take(limit)
    }

    /** Title fallback needs the same year; a TV show without a year in the catalog matches on title alone. */
    private fun titleYearMatches(catalogYear: Int?, tmdbYear: Int?, kind: VodKind): Boolean = when {
        catalogYear != null && tmdbYear != null -> catalogYear == tmdbYear
        catalogYear == null -> kind == VodKind.SERIES
        else -> false
    }

    private fun normalizeTmdb(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        return trimmed.trimStart('0').ifEmpty { null }
    }
}
