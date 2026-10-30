package app.playlistplayer.feature.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.playlistplayer.core.consolidation.VersionComparator
import app.playlistplayer.core.consolidation.VersionPreferences
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.consolidation.VodVersion
import app.playlistplayer.core.database.entity.VodInfoCacheEntity
import app.playlistplayer.core.sync.catalog.DetailResult
import app.playlistplayer.core.sync.catalog.VodInfoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** get_vod_info for one movie version ([VodInfoRepository.load] in the app). */
fun interface MovieInfoLoader {
    suspend fun load(playlistId: Long, streamId: Long, forceRefresh: Boolean): DetailResult<VodInfoCacheEntity>

    companion object {
        fun from(repository: VodInfoRepository) = MovieInfoLoader { p, s, f -> repository.load(p, s, f) }
    }
}

data class TitleDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val titleKey: String? = null,
    val title: String = "",
    val year: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val plot: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val rating: Double? = null,
    val durationSecs: Long? = null,
    val detailsLoading: Boolean = false,
    /** get_vod_info failed with nothing cached; the versions stay usable. */
    val detailsFailed: Boolean = false,
    val languageChips: List<FilterChipModel> = emptyList(),
    val qualityChips: List<FilterChipModel> = emptyList(),
    /** Versions passing the filters, in comparator order. */
    val versions: List<VersionRow> = emptyList(),
    val selectedVersionId: String? = null,
    val isFavourite: Boolean = false,
    /** Offer "Resume" at this position (5% < progress < 95%). */
    val resumePositionMs: Long? = null,
    val watched: Boolean = false,
    val playError: PlayError? = null,
)

/**
 * Movie detail (R12–R15): details from get_vod_info, versions ranked by [VersionComparator]
 * over the user's preferences, language/quality filter chips, default = stored manual pick else
 * comparator best, manual pick stored on selection, favourite toggle and the play action.
 */
class TitleDetailViewModel(
    private val playlistId: Long,
    private val itemId: String,
    private val repository: VodCatalogRepository,
    private val movieInfo: MovieInfoLoader,
    private val picks: TitlePickStore,
    private val favourites: FavouritesStore,
    private val progress: PlaybackProgressRepository,
    private val preferences: VersionPreferencesProvider,
    private val accounts: AccountProvider,
) : ViewModel() {

    private val mutableState = MutableStateFlow(TitleDetailUiState())
    val state: StateFlow<TitleDetailUiState> = mutableState.asStateFlow()

    private var title: CatalogTitle? = null
    private var ranked: List<VodVersion> = emptyList()
    private var defaultId: String? = null
    private var selectedId: String? = null
    private var filter = VersionFilter()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val found = repository.title(playlistId, VodKind.MOVIE, itemId)
        if (found == null) {
            mutableState.value = TitleDetailUiState(loading = false, notFound = true)
            return
        }
        title = found
        val prefs = preferences.current().copy(manualPickId = null)
        ranked = prefs.rank(found.title.versions)
        val pick = picks.pick(found.key)?.takeIf { id -> ranked.any { it.item.id == id } }
        defaultId = pick ?: ranked.first().item.id
        selectedId = defaultId
        val key = ProgressKey(found.key)
        mutableState.value = TitleDetailUiState(
            loading = false,
            titleKey = found.key,
            title = found.title.title,
            year = found.title.year,
            posterUrl = found.posterUrl,
            isFavourite = favourites.isFavourite(favouriteRef(found)),
            resumePositionMs = progress.resumePosition(key),
            watched = progress.isWatched(key),
        )
        publishVersions()
        loadDetails(forceRefresh = false)
    }

    fun selectLanguage(key: String?) {
        filter = filter.toggleLanguage(key, ranked)
        publishVersions()
    }

    fun selectQuality(key: String?) {
        filter = filter.toggleQuality(key, ranked)
        publishVersions()
    }

    /** The user picked a version: it becomes the default for this title from now on. */
    fun selectVersion(versionId: String) {
        val t = title ?: return
        if (ranked.none { it.item.id == versionId }) return
        selectedId = versionId
        defaultId = versionId
        publishVersions()
        viewModelScope.launch { picks.setPick(t.key, versionId) }
    }

    fun toggleFavourite() {
        val t = title ?: return
        viewModelScope.launch {
            val ref = favouriteRef(t)
            val next = !favourites.isFavourite(ref)
            favourites.setFavourite(ref, next)
            mutableState.update { it.copy(isFavourite = next) }
        }
    }

    fun retryDetails() {
        viewModelScope.launch { loadDetails(forceRefresh = true) }
    }

    /**
     * Builds the play request for the selected version (others follow as failover candidates).
     * [resume] starts at the saved position when one is offered. Returns null and sets
     * [TitleDetailUiState.playError] when no account is available.
     */
    suspend fun playbackRequest(resume: Boolean): PlaybackRequest? {
        val t = title ?: return null
        val chosen = currentVisible().firstOrNull { it.item.id == selectedId } ?: currentVisible().firstOrNull() ?: return null
        val account = accounts.streamAccount(playlistId)
        if (account == null) {
            mutableState.update { it.copy(playError = PlayError.NO_ACCOUNT) }
            return null
        }
        val ordered = listOf(chosen) + ranked.filter { it !== chosen }
        val sources = try {
            ordered.map { VodSources.movie(account, playlistId, it, t.meta[it.item.id]) }
        } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(playError = PlayError.INVALID_STREAM) }
            return null
        }
        mutableState.update { it.copy(playError = null) }
        val resumeAt = if (resume) progress.resumePosition(ProgressKey(t.key)) else null
        return PlaybackRequest(
            sources = sources,
            progressKey = ProgressKey(t.key),
            playlistId = playlistId,
            kind = VodKind.MOVIE,
            label = t.title.title,
            startPositionMs = resumeAt ?: 0L,
        )
    }

    /** Re-reads progress after the player closes. */
    fun refreshProgress() {
        val t = title ?: return
        viewModelScope.launch {
            val key = ProgressKey(t.key)
            mutableState.update { it.copy(resumePositionMs = progress.resumePosition(key), watched = progress.isWatched(key)) }
        }
    }

    private fun currentVisible(): List<VodVersion> = filter.apply(ranked)

    private fun publishVersions() {
        val visible = currentVisible()
        val selected = visible.firstOrNull { it.item.id == selectedId } ?: visible.firstOrNull()
        selectedId = selected?.item?.id
        mutableState.update {
            it.copy(
                languageChips = filter.languageChips(ranked),
                qualityChips = filter.qualityChips(ranked),
                versions = visible.toRows(defaultId, selectedId),
                selectedVersionId = selectedId,
            )
        }
    }

    private suspend fun loadDetails(forceRefresh: Boolean) {
        val t = title ?: return
        mutableState.update { it.copy(detailsLoading = true, detailsFailed = false) }
        // Default version first; other versions carry the same film's details.
        val candidates = (listOfNotNull(ranked.firstOrNull { it.item.id == defaultId }) + ranked).distinct().take(MAX_DETAIL_ATTEMPTS)
        for (candidate in candidates) {
            val streamId = candidate.item.id.toLongOrNull() ?: continue
            val result = movieInfo.load(playlistId, streamId, forceRefresh)
            if (result is DetailResult.Loaded) {
                val info = result.value
                mutableState.update {
                    it.copy(
                        detailsLoading = false,
                        detailsFailed = false,
                        plot = info.plot,
                        genre = info.genre,
                        cast = info.cast,
                        director = info.director,
                        rating = info.rating ?: t.meta[candidate.item.id]?.rating,
                        durationSecs = info.durationSecs,
                        backdropUrl = info.backdrop,
                        posterUrl = it.posterUrl ?: info.cover,
                    )
                }
                return
            }
        }
        mutableState.update { it.copy(detailsLoading = false, detailsFailed = true) }
    }

    private fun favouriteRef(t: CatalogTitle) = FavouriteRef(FavouriteKind.MOVIE, playlistId, t.key)

    private companion object {
        const val MAX_DETAIL_ATTEMPTS = 3
    }
}

/** Convenience for tests and callers that rank outside a ViewModel. */
internal fun VersionPreferences.rank(versions: List<VodVersion>): List<VodVersion> {
    val comparator = VersionComparator(this)
    return versions.sortedWith { a, b -> comparator.compare(a.version, b.version) }
}
