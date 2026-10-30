package app.playlistplayer.feature.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.core.consolidation.QualityTier
import app.playlistplayer.core.consolidation.VersionPreferences
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.consolidation.VodVersion
import app.playlistplayer.core.database.entity.EpisodeEntity
import app.playlistplayer.core.sync.catalog.DetailResult
import app.playlistplayer.core.sync.catalog.SeriesDetails
import app.playlistplayer.core.sync.catalog.SeriesInfoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** get_series_info for one source series ([SeriesInfoRepository.load] in the app). */
fun interface SeriesInfoLoader {
    suspend fun load(playlistId: Long, seriesId: Long, forceRefresh: Boolean): DetailResult<SeriesDetails>

    companion object {
        fun from(repository: SeriesInfoRepository) = SeriesInfoLoader { p, s, f -> repository.load(p, s, f) }
    }
}

/** Season + episode identity, shared by every source series of a title. */
data class EpisodeKey(val season: Int, val episode: Int)

/** One source series ([series]) that has the episode ([episode] row of that source). */
data class EpisodeVersion(val series: VodVersion, val episode: EpisodeEntity)

data class SeasonTab(val number: Int, val name: String?, val selected: Boolean)

data class EpisodeRow(
    val key: EpisodeKey,
    val title: String?,
    val plot: String?,
    val imageUrl: String?,
    val durationSecs: Long?,
    /** Only the source versions that contain this episode, in comparator order. */
    val versions: List<VersionRow>,
    val selectedVersionId: String,
    val progressFraction: Double?,
    val watched: Boolean,
)

data class SeriesUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    /** Every source's get_series_info failed and nothing is cached: show Retry. */
    val episodesFailed: Boolean = false,
    val episodesRetryable: Boolean = true,
    /** Some sources failed; their episodes are missing until a retry. */
    val failedSources: Int = 0,
    val titleKey: String? = null,
    val title: String = "",
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val rating: Double? = null,
    val languageChips: List<FilterChipModel> = emptyList(),
    val qualityChips: List<FilterChipModel> = emptyList(),
    /** Source series (title versions) passing the filters, comparator order. */
    val versions: List<VersionRow> = emptyList(),
    val selectedVersionId: String? = null,
    val seasons: List<SeasonTab> = emptyList(),
    val episodes: List<EpisodeRow> = emptyList(),
    val isFavourite: Boolean = false,
    val playError: PlayError? = null,
)

/**
 * Series navigation (R16): season tabs, then episodes. Per-episode versions are merged across
 * the title's source series; only sources that contain the episode are offered. The user's
 * chosen language + quality (from the version they picked, or the default) is sticky:
 * auto-advance keeps it when the next episode has it, else takes the comparator's next best
 * for that episode, and returns to the chosen one as soon as it is available again.
 */
class SeriesViewModel(
    private val playlistId: Long,
    private val itemId: String,
    private val repository: VodCatalogRepository,
    private val seriesInfo: SeriesInfoLoader,
    private val picks: TitlePickStore,
    private val favourites: FavouritesStore,
    private val progress: PlaybackProgressRepository,
    private val preferences: VersionPreferencesProvider,
    private val accounts: AccountProvider,
) : ViewModel() {

    private data class Sticky(val versionId: String, val language: String?, val quality: QualityTier)

    private val mutableState = MutableStateFlow(SeriesUiState())
    val state: StateFlow<SeriesUiState> = mutableState.asStateFlow()

    private var title: CatalogTitle? = null
    private var prefs = VersionPreferences()
    private var ranked: List<VodVersion> = emptyList()
    private var filter = VersionFilter()
    private var sticky: Sticky? = null
    private var details: Map<String, SeriesDetails> = emptyMap()
    private var episodes: Map<EpisodeKey, List<EpisodeVersion>> = emptyMap()
    private var order: List<EpisodeKey> = emptyList()
    private var selectedSeason: Int? = null
    private var current: EpisodeKey? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val found = repository.title(playlistId, VodKind.SERIES, itemId)
        if (found == null) {
            mutableState.value = SeriesUiState(loading = false, notFound = true)
            return
        }
        title = found
        prefs = preferences.current().copy(manualPickId = null)
        ranked = prefs.rank(found.title.versions)
        val pick = picks.pick(found.key)?.let { id -> ranked.firstOrNull { it.item.id == id } }
        sticky = (pick ?: ranked.first()).toSticky()
        val anchorMeta = found.meta[found.anchorId]
        mutableState.value = SeriesUiState(
            loading = true,
            titleKey = found.key,
            title = found.title.title,
            year = found.title.year,
            posterUrl = found.posterUrl,
            plot = anchorMeta?.plot,
            genre = anchorMeta?.genre,
            rating = anchorMeta?.rating,
            isFavourite = favourites.isFavourite(FavouriteRef(FavouriteKind.SERIES, playlistId, found.key)),
        )
        publishVersions()
        loadEpisodes(forceRefresh = false)
    }

    /** Retry after a get_series_info failure. */
    fun retry() {
        viewModelScope.launch { loadEpisodes(forceRefresh = true) }
    }

    fun selectSeason(number: Int) {
        if (episodes.keys.none { it.season == number }) return
        selectedSeason = number
        viewModelScope.launch { publishEpisodes() }
    }

    fun selectLanguage(key: String?) {
        filter = filter.toggleLanguage(key, ranked)
        followFilter()
    }

    fun selectQuality(key: String?) {
        filter = filter.toggleQuality(key, ranked)
        followFilter()
    }

    /** The user picked a source version: it becomes sticky and the title's stored pick. */
    fun selectVersion(versionId: String) {
        val t = title ?: return
        val version = ranked.firstOrNull { it.item.id == versionId } ?: return
        sticky = version.toSticky()
        publishVersions()
        viewModelScope.launch {
            picks.setPick(t.key, versionId)
            publishEpisodes()
        }
    }

    fun toggleFavourite() {
        val t = title ?: return
        viewModelScope.launch {
            val ref = FavouriteRef(FavouriteKind.SERIES, playlistId, t.key)
            val next = !favourites.isFavourite(ref)
            favourites.setFavourite(ref, next)
            mutableState.update { it.copy(isFavourite = next) }
        }
    }

    /** The version auto-advance or Play uses for [key] under the sticky language + quality. */
    fun versionFor(key: EpisodeKey): EpisodeVersion? {
        val available = episodes[key] ?: return null
        val s = sticky ?: return available.first()
        available.firstOrNull { it.series.item.id == s.versionId }?.let { return it }
        available.firstOrNull { it.series.version.language == s.language && it.series.version.quality == s.quality }
            ?.let { return it }
        // Comparator's next best, keeping the chosen language ahead of the user's list.
        val languages = (listOfNotNull(s.language) + prefs.languages).distinct()
        val ranking = prefs.copy(languages = languages).rank(available.map { it.series })
        return ranking.firstNotNullOfOrNull { v -> available.firstOrNull { it.series === v } }
    }

    /** The episode after [key] in season-then-episode order (specials last), or null. */
    fun nextEpisode(key: EpisodeKey): EpisodeKey? {
        val index = order.indexOf(key)
        return if (index >= 0) order.getOrNull(index + 1) else null
    }

    /** Episode currently playing (set by [playbackRequest]). */
    val currentEpisode: EpisodeKey? get() = current

    /**
     * Builds the play request for [key]. [versionId] is an explicit per-episode choice (it becomes
     * sticky); null uses [versionFor]. Other versions of the same episode follow for failover.
     */
    suspend fun playbackRequest(key: EpisodeKey, versionId: String? = null, resume: Boolean = true): PlaybackRequest? {
        val t = title ?: return null
        val available = episodes[key] ?: return null
        val explicit = versionId?.let { id -> available.firstOrNull { it.series.item.id == id } }
        if (explicit != null) {
            sticky = explicit.series.toSticky()
            publishVersions()
        }
        val chosen = explicit ?: versionFor(key) ?: return null
        val account = accounts.streamAccount(playlistId)
        if (account == null) {
            mutableState.update { it.copy(playError = PlayError.NO_ACCOUNT) }
            return null
        }
        val others = prefs.rank(available.map { it.series })
            .mapNotNull { v -> available.firstOrNull { it.series === v } }
            .filter { it !== chosen }
        val sources = try {
            (listOf(chosen) + others).map { VodSources.episode(account, playlistId, it.series.item.name, it) }
        } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(playError = PlayError.INVALID_STREAM) }
            return null
        }
        current = key
        mutableState.update { it.copy(playError = null) }
        val progressKey = ProgressKey(t.key, key.season, key.episode)
        return PlaybackRequest(
            sources = sources,
            progressKey = progressKey,
            playlistId = playlistId,
            kind = VodKind.SERIES,
            label = episodeLabel(t, key, chosen),
            startPositionMs = if (resume) progress.resumePosition(progressKey) ?: 0L else 0L,
            nextEpisodeLabel = nextEpisode(key)?.let { next -> episodes[next]?.firstOrNull()?.let { episodeLabel(t, next, it) } },
        )
    }

    /** Auto-advance: the request for the episode after the current one, or null at the end. */
    suspend fun nextEpisodeRequest(): PlaybackRequest? {
        val next = current?.let(::nextEpisode) ?: return null
        return playbackRequest(next, versionId = null, resume = false)
    }

    /** Re-reads progress after the player closes. */
    fun refreshProgress() {
        viewModelScope.launch { publishEpisodes() }
    }

    private fun followFilter() {
        val visible = filter.apply(ranked)
        val s = sticky
        if (s != null && visible.none { it.item.id == s.versionId }) visible.firstOrNull()?.let { sticky = it.toSticky() }
        publishVersions()
        viewModelScope.launch { publishEpisodes() }
    }

    private suspend fun loadEpisodes(forceRefresh: Boolean) {
        mutableState.update { it.copy(loading = true, episodesFailed = false) }
        val loaded = LinkedHashMap<String, SeriesDetails>()
        var failed = 0
        var retryable = false
        for (version in ranked) {
            val seriesId = version.item.id.toLongOrNull() ?: continue
            when (val result = seriesInfo.load(playlistId, seriesId, forceRefresh)) {
                is DetailResult.Loaded -> loaded[version.item.id] = result.value
                is DetailResult.Failed -> {
                    failed++
                    retryable = retryable || result.retryable
                }
            }
        }
        details = loaded
        if (loaded.isEmpty()) {
            episodes = emptyMap()
            order = emptyList()
            mutableState.update {
                it.copy(loading = false, episodesFailed = true, episodesRetryable = retryable, failedSources = failed, seasons = emptyList(), episodes = emptyList())
            }
            return
        }
        buildEpisodes()
        val primary = sticky?.versionId?.let(loaded::get) ?: loaded.values.first()
        mutableState.update {
            it.copy(
                loading = false,
                episodesFailed = false,
                failedSources = failed,
                plot = primary.info.plot ?: it.plot,
                genre = primary.info.genre ?: it.genre,
                cast = primary.info.cast,
                rating = primary.info.rating ?: it.rating,
                backdropUrl = primary.info.backdrop,
                posterUrl = it.posterUrl ?: primary.info.cover,
            )
        }
        publishEpisodes()
    }

    private fun buildEpisodes() {
        val map = HashMap<EpisodeKey, MutableList<EpisodeVersion>>()
        for (version in ranked) {
            val d = details[version.item.id] ?: continue
            val positionInSeason = HashMap<Int, Int>()
            for (e in d.episodes.sortedBy { it.sortOrder }) {
                val n = (positionInSeason[e.season] ?: 0) + 1
                positionInSeason[e.season] = n
                val key = EpisodeKey(e.season, e.episodeNum ?: n)
                val list = map.getOrPut(key) { ArrayList() }
                if (list.none { it.series === version }) list += EpisodeVersion(version, e)
            }
        }
        episodes = map
        order = map.keys.sortedWith(compareBy<EpisodeKey>({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))
        val seasons = order.map { it.season }.distinct()
        if (selectedSeason !in seasons) selectedSeason = seasons.firstOrNull()
    }

    private suspend fun publishEpisodes() {
        val t = title ?: return
        val seasonNumbers = order.map { it.season }.distinct()
        val seasonNames = details.values.flatMap { it.seasons }.groupBy { it.seasonNumber }
            .mapValues { (_, rows) -> rows.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) } }
        val season = selectedSeason
        val rows = order.filter { it.season == season }.mapNotNull { key ->
            val available = episodes[key] ?: return@mapNotNull null
            val chosen = versionFor(key) ?: return@mapNotNull null
            val p = progress.get(ProgressKey(t.key, key.season, key.episode))
            val rankedAvailable = prefs.rank(available.map { it.series })
            EpisodeRow(
                key = key,
                title = chosen.episode.title,
                plot = chosen.episode.plot,
                imageUrl = chosen.episode.image,
                durationSecs = chosen.episode.durationSecs,
                versions = rankedAvailable.toRows(defaultId = chosen.series.item.id, selectedId = chosen.series.item.id),
                selectedVersionId = chosen.series.item.id,
                progressFraction = p?.fraction,
                watched = p?.let { PlaybackProgressRepository.isWatched(it.positionMs, it.durationMs) } ?: false,
            )
        }
        mutableState.update {
            it.copy(
                seasons = seasonNumbers.map { n -> SeasonTab(n, seasonNames[n], n == season) },
                episodes = rows,
            )
        }
    }

    private fun publishVersions() {
        val visible = filter.apply(ranked)
        val selected = sticky?.versionId
        mutableState.update {
            it.copy(
                languageChips = filter.languageChips(ranked),
                qualityChips = filter.qualityChips(ranked),
                versions = visible.toRows(selected, selected),
                selectedVersionId = selected,
            )
        }
    }

    private fun episodeLabel(t: CatalogTitle, key: EpisodeKey, version: EpisodeVersion): String {
        val name = version.episode.title?.takeIf { it.isNotBlank() }
        return "${t.title.title} · S${key.season} E${key.episode}" + (name?.let { " · $it" } ?: "")
    }

    private fun VodVersion.toSticky() = Sticky(item.id, version.language, version.quality)
}
