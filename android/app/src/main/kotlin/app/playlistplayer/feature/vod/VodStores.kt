package app.playlistplayer.feature.vod

import app.playlistplayer.core.consolidation.VersionPreferences
import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.core.player.url.StreamAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Interfaces the VOD feature needs from the wiring unit. In-memory implementations below serve
// tests and can back the app until Room tables exist.

/**
 * The Xtream account for a playlist, for building stream URLs at play time. Secrets are read on
 * demand and never kept in ViewModel state.
 */
fun interface AccountProvider {
    suspend fun streamAccount(playlistId: Long): StreamAccount?
}

/** Ranking inputs: `ContentPreferences.versionPreferences(device)` in the app. */
fun interface VersionPreferencesProvider {
    suspend fun current(): VersionPreferences
}

/** The version a user picked for a title (R13), keyed by `VodTitle.key`. */
interface TitlePickStore {
    suspend fun pick(titleKey: String): String?
    suspend fun setPick(titleKey: String, versionId: String)
    suspend fun clearPick(titleKey: String)
}

/** What a favourite refers to. [key] is the `VodTitle.key` for movies and series. */
data class FavouriteRef(val kind: FavouriteKind, val playlistId: Long, val key: String)

enum class FavouriteKind { MOVIE, SERIES, CHANNEL }

/** Favourites shared across live and VOD (U11 adds the Room table and the Home row). */
interface FavouritesStore {
    fun observe(): Flow<Set<FavouriteRef>>
    suspend fun isFavourite(ref: FavouriteRef): Boolean
    suspend fun setFavourite(ref: FavouriteRef, favourite: Boolean)
}

/**
 * Identity of a resumable item. Movies use the title key alone (any version resumes);
 * episodes add season and episode number, so progress carries across source series.
 */
data class ProgressKey(val titleKey: String, val season: Int? = null, val episode: Int? = null)

/** One saved playback position. */
data class PlaybackProgress(
    val key: ProgressKey,
    val playlistId: Long,
    val kind: VodKind,
    /** Display title (title name, or "S1 E5 · Episode title"). */
    val label: String,
    val versionId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Double get() = if (durationMs > 0) positionMs.toDouble() / durationMs else 0.0
}

/** Persistence of [PlaybackProgress] (a later unit adds the Room table). */
interface PlaybackProgressStore {
    suspend fun get(key: ProgressKey): PlaybackProgress?
    suspend fun put(progress: PlaybackProgress)
    suspend fun remove(key: ProgressKey)

    /** All entries, most recently updated first. */
    suspend fun recent(limit: Int): List<PlaybackProgress>
}

class InMemoryTitlePickStore : TitlePickStore {
    private val picks = HashMap<String, String>()
    override suspend fun pick(titleKey: String): String? = synchronized(picks) { picks[titleKey] }
    override suspend fun setPick(titleKey: String, versionId: String) {
        synchronized(picks) { picks[titleKey] = versionId }
    }
    override suspend fun clearPick(titleKey: String) {
        synchronized(picks) { picks.remove(titleKey) }
    }
}

class InMemoryFavouritesStore : FavouritesStore {
    private val refs = MutableStateFlow<Set<FavouriteRef>>(emptySet())
    val current: StateFlow<Set<FavouriteRef>> = refs.asStateFlow()
    override fun observe(): Flow<Set<FavouriteRef>> = refs
    override suspend fun isFavourite(ref: FavouriteRef): Boolean = ref in refs.value
    override suspend fun setFavourite(ref: FavouriteRef, favourite: Boolean) {
        refs.update { if (favourite) it + ref else it - ref }
    }
}

class InMemoryPlaybackProgressStore : PlaybackProgressStore {
    private val entries = LinkedHashMap<ProgressKey, PlaybackProgress>()
    override suspend fun get(key: ProgressKey): PlaybackProgress? = synchronized(entries) { entries[key] }
    override suspend fun put(progress: PlaybackProgress) {
        synchronized(entries) { entries[progress.key] = progress }
    }
    override suspend fun remove(key: ProgressKey) {
        synchronized(entries) { entries.remove(key) }
    }
    override suspend fun recent(limit: Int): List<PlaybackProgress> =
        synchronized(entries) { entries.values.sortedByDescending { it.updatedAt }.take(limit) }
}
