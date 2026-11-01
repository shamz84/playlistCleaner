package app.playlistplayer.feature.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.sync.catalog.DetailResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * A Continue watching card. For TV shows [season]/[episode] name the episode to watch: the one in
 * progress, or the next unwatched one ([upNext]). [fraction] is the saved progress, when any.
 */
data class ContinueCard(
    val card: VodCard,
    val season: Int? = null,
    val episode: Int? = null,
    val fraction: Float? = null,
    val upNext: Boolean = false,
)

/**
 * One playlist section (header = playlist name, R25): Trending, Continue watching, then the full
 * title list in [sort] order. [titles] is the shown page of [totalTitles].
 */
data class PlaylistSection(
    val playlist: VodPlaylist,
    val trending: List<VodCard> = emptyList(),
    val continueWatching: List<ContinueCard> = emptyList(),
    val sort: VodSort = VodSort.RECENTLY_ADDED,
    val titles: List<VodCard> = emptyList(),
    val totalTitles: Int = 0,
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    val hasMore: Boolean get() = titles.size < totalTitles
}

data class VodBrowseUiState(
    val kind: VodKind,
    val loading: Boolean = true,
    val sections: List<PlaylistSection> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && sections.all { !it.loading && it.totalTitles == 0 }

    /** TMDB's terms need a visible credit wherever its data shows. */
    val showTmdbCredit: Boolean get() = sections.any { it.trending.isNotEmpty() }
}

/**
 * Movies or TV Shows browse (R12, R25, R32, R33). Per playlist section: Trending (TMDB weekly
 * trending matched to this playlist's catalog), Continue watching, then every merged title
 * (parental-hidden ones excluded) sorted by the section's persisted [VodSort], paged by
 * [pageSize]. Grouping runs in [VodCatalogRepository]; sorting on [computeDispatcher].
 */
class VodBrowseViewModel(
    val kind: VodKind,
    private val repository: VodCatalogRepository,
    private val trending: TrendingProvider? = null,
    private val progress: PlaybackProgressRepository? = null,
    private val seriesInfo: SeriesInfoLoader? = null,
    private val sortStore: VodSortStore = InMemoryVodSortStore(),
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val rowLimit: Int = DEFAULT_ROW_LIMIT,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val mutableState = MutableStateFlow(VodBrowseUiState(kind))
    val state: StateFlow<VodBrowseUiState> = mutableState.asStateFlow()

    private class Loaded(val titles: List<CatalogTitle>, var sorted: List<CatalogTitle>, var shown: Int)

    private val loaded = HashMap<Long, Loaded>()
    private var refreshJob: Job? = null
    private val continueJobs = HashMap<Long, Job>()

    init {
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        continueJobs.values.forEach { it.cancel() }
        continueJobs.clear()
        refreshJob = viewModelScope.launch {
            loaded.clear()
            mutableState.value = VodBrowseUiState(kind, loading = true)
            val playlists = repository.playlists()
            val sorts = playlists.associate { p -> p.id to (readSort(p.id) ?: VodSort.RECENTLY_ADDED) }
            mutableState.value = VodBrowseUiState(
                kind,
                loading = false,
                sections = playlists.map { PlaylistSection(it, sort = sorts.getValue(it.id), loading = true) },
            )
            val trendingList: Deferred<List<TrendingEntry>?> = async { loadTrending() }
            playlists.forEach { p -> launch { loadSection(p.id, trendingList) } }
        }
    }

    /** Re-reads Continue watching (e.g. when the screen shows again after playback). */
    fun refreshContinueWatching() {
        loaded.forEach { (playlistId, l) -> launchContinue(playlistId, l.titles) }
    }

    /** Shows the next page of a section's full list. */
    fun loadMore(playlistId: Long) {
        val l = loaded[playlistId] ?: return
        if (l.shown >= l.sorted.size) return
        l.shown = (l.shown + pageSize).coerceAtMost(l.sorted.size)
        publishTitles(playlistId, l)
    }

    /** Switches a section's sort; the choice persists per section. */
    fun setSort(playlistId: Long, sort: VodSort) {
        updateSection(playlistId) { it.copy(sort = sort) }
        viewModelScope.launch {
            try {
                sortStore.setSort(kind, playlistId, sort)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not persisted; the in-session choice still applies.
            }
            val l = loaded[playlistId] ?: return@launch
            val sorted = withContext(computeDispatcher) { VodTitleSorter.sort(l.titles, sort) }
            if (sectionSort(playlistId) != sort) return@launch
            l.sorted = sorted
            l.shown = pageSize.coerceAtMost(sorted.size)
            publishTitles(playlistId, l)
        }
    }

    private suspend fun readSort(playlistId: Long): VodSort? = try {
        sortStore.sort(kind, playlistId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun loadTrending(): List<TrendingEntry>? = try {
        trending?.trending(kind)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun loadSection(playlistId: Long, trendingList: Deferred<List<TrendingEntry>?>) {
        val titles = try {
            val titles = repository.visibleTitles(playlistId, kind)
            val sort = sectionSort(playlistId)
            val sorted = withContext(computeDispatcher) { VodTitleSorter.sort(titles, sort) }
            val l = Loaded(titles, sorted, pageSize.coerceAtMost(sorted.size))
            loaded[playlistId] = l
            updateSection(playlistId) {
                it.copy(loading = false, titles = sorted.take(l.shown).map(CatalogTitle::card), totalTitles = sorted.size)
            }
            titles
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateSection(playlistId) { it.copy(loading = false, failed = true) }
            return
        }
        launchContinue(playlistId, titles)
        val entries = trendingList.await() ?: return
        val cards = withContext(computeDispatcher) {
            TrendingMatcher.match(entries, titles, kind, rowLimit).map(CatalogTitle::card)
        }
        updateSection(playlistId) { it.copy(trending = cards) }
    }

    private fun launchContinue(playlistId: Long, titles: List<CatalogTitle>) {
        val repo = progress ?: return
        continueJobs[playlistId]?.cancel()
        continueJobs[playlistId] = viewModelScope.launch {
            val cards = try {
                continueCards(repo, playlistId, titles)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            updateSection(playlistId) { it.copy(continueWatching = cards) }
        }
    }

    private suspend fun continueCards(repo: PlaybackProgressRepository, playlistId: Long, titles: List<CatalogTitle>): List<ContinueCard> {
        val byKey = titles.associateBy { it.key }
        val recent = repo.recent(RECENT_SCAN).filter { it.playlistId == playlistId && it.kind == kind && it.key.titleKey in byKey }
        return when (kind) {
            VodKind.MOVIE -> recent
                .filter { PlaybackProgressRepository.isResumable(it.positionMs, it.durationMs) && !PlaybackProgressRepository.isWatched(it.positionMs, it.durationMs) }
                .distinctBy { it.key.titleKey }
                .take(rowLimit)
                .map { ContinueCard(byKey.getValue(it.key.titleKey).card(), fraction = it.fraction.toFloat().coerceIn(0f, 1f)) }
            VodKind.SERIES -> {
                // The newest entry per show decides: unfinished → that episode; finished → the next one.
                val latest = recent.filter { it.key.season != null && it.key.episode != null }.distinctBy { it.key.titleKey }
                val out = ArrayList<ContinueCard>()
                for (p in latest) {
                    if (out.size >= rowLimit) break
                    val title = byKey.getValue(p.key.titleKey)
                    if (!PlaybackProgressRepository.isWatched(p.positionMs, p.durationMs)) {
                        val fraction = p.fraction.toFloat().coerceIn(0f, 1f).takeIf { PlaybackProgressRepository.isResumable(p.positionMs, p.durationMs) }
                        out += ContinueCard(title.card(), p.key.season, p.key.episode, fraction)
                        continue
                    }
                    val next = nextEpisode(title, p.key.season!!, p.key.episode!!) ?: continue
                    if (repo.isWatched(ProgressKey(title.key, next.first, next.second))) continue
                    out += ContinueCard(title.card(), next.first, next.second, upNext = true)
                }
                out
            }
        }
    }

    /** The episode after (season, episode) across the show's source series, from their cached info. */
    private suspend fun nextEpisode(title: CatalogTitle, season: Int, episode: Int): Pair<Int, Int>? {
        val loader = seriesInfo ?: return null
        val order = compareBy<Pair<Int, Int>>({ it.first }, { it.second })
        val current = season to episode
        var best: Pair<Int, Int>? = null
        for (v in title.title.versions) {
            val seriesId = v.item.id.toLongOrNull() ?: continue
            val result = loader.load(title.playlistId, seriesId, forceRefresh = false)
            if (result !is DetailResult.Loaded) continue
            // Same numbering as the series screen: a missing episode number is its position in the season.
            val positionInSeason = HashMap<Int, Int>()
            result.value.episodes.sortedBy { it.sortOrder }
                .map { e ->
                    val n = (positionInSeason[e.season] ?: 0) + 1
                    positionInSeason[e.season] = n
                    e.season to (e.episodeNum ?: n)
                }
                // Specials (season 0) are never "next".
                .filter { it.first > 0 && order.compare(it, current) > 0 }
                .minWithOrNull(order)
                ?.let { candidate -> if (best == null || order.compare(candidate, best!!) < 0) best = candidate }
            if (best != null) break
        }
        return best
    }

    private fun sectionSort(playlistId: Long): VodSort =
        state.value.sections.firstOrNull { it.playlist.id == playlistId }?.sort ?: VodSort.RECENTLY_ADDED

    private fun publishTitles(playlistId: Long, l: Loaded) {
        updateSection(playlistId) {
            it.copy(titles = l.sorted.take(l.shown).map(CatalogTitle::card), totalTitles = l.sorted.size)
        }
    }

    private fun updateSection(playlistId: Long, change: (PlaylistSection) -> PlaylistSection) {
        mutableState.update { s ->
            s.copy(sections = s.sections.map { if (it.playlist.id == playlistId) change(it) else it })
        }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 60
        const val DEFAULT_ROW_LIMIT: Int = 20
        private const val RECENT_SCAN: Int = 500
    }
}
