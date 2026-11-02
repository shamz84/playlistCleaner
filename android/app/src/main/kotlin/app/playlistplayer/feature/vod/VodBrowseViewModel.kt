package app.playlistplayer.feature.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.core.consolidation.VodKind
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * title list in [sort] order. [titles] is the shown page of [totalTitles]. [loading] stays true
 * while the playlist's title index is still being built in the background (first sync, upgrade).
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
 * Movies or TV Shows browse (R12, R25, R32, R33, R35). Per playlist section: Trending (TMDB weekly
 * trending matched to this playlist's catalog), Continue watching, then every merged title
 * (parental-hidden ones excluded) in the section's persisted [VodSort], paged by [pageSize].
 *
 * Every read is an indexed query on the precomputed title index ([VodCatalogRepository]); this
 * ViewModel never groups or sorts the catalog. [changes] (index rebuilt after a catalog sync)
 * reloads the screen. TV shows' "next episode" uses cached series info only: details are fetched
 * when a title is opened, never while browsing.
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
    changes: Flow<Unit> = emptyFlow(),
) : ViewModel() {

    private val mutableState = MutableStateFlow(VodBrowseUiState(kind))
    val state: StateFlow<VodBrowseUiState> = mutableState.asStateFlow()

    /** A loaded section: its hidden categories and the cards shown so far for [sort]. */
    private class Loaded(val hidden: Set<String>, var sort: VodSort, var cards: List<VodCard>, var total: Int) {
        var paging: Job? = null
    }

    private val loaded = HashMap<Long, Loaded>()
    private var refreshJob: Job? = null
    private val continueJobs = HashMap<Long, Job>()

    init {
        refresh()
        viewModelScope.launch { changes.collect { refresh() } }
    }

    fun refresh() {
        refreshJob?.cancel()
        continueJobs.values.forEach { it.cancel() }
        continueJobs.clear()
        loaded.values.forEach { it.paging?.cancel() }
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
        loaded.forEach { (playlistId, l) -> launchContinue(playlistId, l.hidden) }
    }

    /** Shows the next page of a section's full list. */
    fun loadMore(playlistId: Long) {
        val l = loaded[playlistId] ?: return
        if (l.cards.size >= l.total || l.paging?.isActive == true) return
        val sort = l.sort
        val offset = l.cards.size
        l.paging = viewModelScope.launch {
            val page = try {
                repository.page(playlistId, kind, sort, l.hidden, offset, pageSize)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (l.sort != sort || l.cards.size != offset || loaded[playlistId] !== l) return@launch
            l.cards = l.cards + page
            // Fewer rows than counted (the index changed underneath): stop paging.
            if (page.size < pageSize) l.total = l.cards.size
            publishTitles(playlistId, l)
        }
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
            reloadFirstPage(playlistId, sort)
        }
    }

    private suspend fun reloadFirstPage(playlistId: Long, sort: VodSort) {
        val l = loaded[playlistId] ?: return
        l.paging?.cancel()
        l.sort = sort
        val first = try {
            repository.page(playlistId, kind, sort, l.hidden, 0, pageSize)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (l.sort != sort || loaded[playlistId] !== l) return
        l.cards = first
        publishTitles(playlistId, l)
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
        val l = try {
            // Still building in the background: stay "loading"; [changes] reloads once it is ready.
            if (repository.isIndexPending(playlistId)) return
            val hidden = repository.hiddenCategories(playlistId, kind)
            val sort = sectionSort(playlistId)
            val total = repository.count(playlistId, kind, hidden)
            val first = repository.page(playlistId, kind, sort, hidden, 0, pageSize)
            Loaded(hidden, sort, first, total).also { loaded[playlistId] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateSection(playlistId) { it.copy(loading = false, failed = true) }
            return
        }
        updateSection(playlistId) { it.copy(loading = false, titles = l.cards, totalTitles = l.total) }
        // A sort chosen while the first page loaded wins.
        sectionSort(playlistId).takeIf { it != l.sort }?.let { reloadFirstPage(playlistId, it) }
        launchContinue(playlistId, l.hidden)
        val entries = trendingList.await() ?: return
        val cards = try {
            repository.trending(playlistId, kind, entries, l.hidden, rowLimit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        updateSection(playlistId) { it.copy(trending = cards) }
    }

    private fun launchContinue(playlistId: Long, hidden: Set<String>) {
        val repo = progress ?: return
        continueJobs[playlistId]?.cancel()
        continueJobs[playlistId] = viewModelScope.launch {
            val cards = try {
                continueCards(repo, playlistId, hidden)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            updateSection(playlistId) { it.copy(continueWatching = cards) }
        }
    }

    private suspend fun continueCards(repo: PlaybackProgressRepository, playlistId: Long, hidden: Set<String>): List<ContinueCard> {
        val candidates = repo.recent(RECENT_SCAN).filter { it.playlistId == playlistId && it.kind == kind }
        if (candidates.isEmpty()) return emptyList()
        // Only titles still in the catalog and not parental-hidden (one indexed lookup).
        val byKey = repository.cards(playlistId, kind, candidates.map { it.key.titleKey }.distinct(), hidden)
        val recent = candidates.filter { it.key.titleKey in byKey }
        return when (kind) {
            VodKind.MOVIE -> recent
                .filter { PlaybackProgressRepository.isResumable(it.positionMs, it.durationMs) && !PlaybackProgressRepository.isWatched(it.positionMs, it.durationMs) }
                .distinctBy { it.key.titleKey }
                .take(rowLimit)
                .map { ContinueCard(byKey.getValue(it.key.titleKey), fraction = it.fraction.toFloat().coerceIn(0f, 1f)) }
            VodKind.SERIES -> {
                // The newest entry per show decides: unfinished → that episode; finished → the next one.
                val latest = recent.filter { it.key.season != null && it.key.episode != null }.distinctBy { it.key.titleKey }
                val out = ArrayList<ContinueCard>()
                for (p in latest) {
                    if (out.size >= rowLimit) break
                    val card = byKey.getValue(p.key.titleKey)
                    if (!PlaybackProgressRepository.isWatched(p.positionMs, p.durationMs)) {
                        val fraction = p.fraction.toFloat().coerceIn(0f, 1f).takeIf { PlaybackProgressRepository.isResumable(p.positionMs, p.durationMs) }
                        out += ContinueCard(card, p.key.season, p.key.episode, fraction)
                        continue
                    }
                    val next = nextEpisode(playlistId, card.titleKey, p.key.season!!, p.key.episode!!) ?: continue
                    if (repo.isWatched(ProgressKey(card.titleKey, next.first, next.second))) continue
                    out += ContinueCard(card, next.first, next.second, upNext = true)
                }
                out
            }
        }
    }

    /**
     * The episode after (season, episode) across the show's source series, from their **cached**
     * info only: get_series_info runs when a series is opened, never while browsing. A show
     * without cached info gets no "next" card.
     */
    private suspend fun nextEpisode(playlistId: Long, titleKey: String, season: Int, episode: Int): Pair<Int, Int>? {
        val loader = seriesInfo ?: return null
        val order = compareBy<Pair<Int, Int>>({ it.first }, { it.second })
        val current = season to episode
        var best: Pair<Int, Int>? = null
        for (itemId in repository.versionIds(playlistId, titleKey)) {
            val seriesId = itemId.toLongOrNull() ?: continue
            val details = loader.cached(playlistId, seriesId) ?: continue
            // Same numbering as the series screen: a missing episode number is its position in the season.
            val positionInSeason = HashMap<Int, Int>()
            details.episodes.sortedBy { it.sortOrder }
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
        updateSection(playlistId) { it.copy(titles = l.cards, totalTitles = l.total) }
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
