package app.playlistplayer.feature.settings

import app.playlistplayer.core.database.CatalogStore
import app.playlistplayer.core.database.PlaylistStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Which browse tree a lockable group belongs to. */
enum class ContentGroupKind { LIVE, MOVIES, SERIES }

/**
 * Stable group keys for [LockedGroup.groupKey]. Live groups are keyed by their playlist group
 * title (ordinals move between syncs; titles are what the user sees and picks), VOD and series
 * categories by the provider category id. Every browse, search, home-row and guide query must
 * build its keys with these functions so a lock applies everywhere.
 */
object ContentGroupKeys {
    fun live(groupName: String): String = "live:$groupName"

    fun movies(categoryId: String): String = "vod:$categoryId"

    fun series(categoryId: String): String = "series:$categoryId"

    fun kindOf(groupKey: String): ContentGroupKind? = when {
        groupKey.startsWith("live:") -> ContentGroupKind.LIVE
        groupKey.startsWith("vod:") -> ContentGroupKind.MOVIES
        groupKey.startsWith("series:") -> ContentGroupKind.SERIES
        else -> null
    }
}

/** One lockable group or category of a playlist, for Settings → Content → Group visibility. */
data class ContentGroup(
    val playlistId: Long,
    val kind: ContentGroupKind,
    val key: String,
    val name: String,
) {
    val lockedGroup: LockedGroup get() = LockedGroup(playlistId, key)

    fun candidate(): GroupCandidate = GroupCandidate(playlistId, key, name)
}

/** Lists a playlist's lockable groups (live groups, then movie and series categories). */
fun interface ContentGroupSource {
    suspend fun groups(playlistId: Long): List<ContentGroup>
}

/** [ContentGroupSource] over the Room stores (active generations only). */
class StoreContentGroupSource(
    private val playlistStore: PlaylistStore,
    private val catalogStore: CatalogStore,
) : ContentGroupSource {
    override suspend fun groups(playlistId: Long): List<ContentGroup> {
        val live = playlistStore.groups(playlistId)
            .distinctBy { it.name }
            .map { ContentGroup(playlistId, ContentGroupKind.LIVE, ContentGroupKeys.live(it.name), it.name) }
        val movies = catalogStore.vodCategories(playlistId)
            .map { ContentGroup(playlistId, ContentGroupKind.MOVIES, ContentGroupKeys.movies(it.categoryId), it.name) }
        val series = catalogStore.seriesCategories(playlistId)
            .map { ContentGroup(playlistId, ContentGroupKind.SERIES, ContentGroupKeys.series(it.categoryId), it.name) }
        return live + movies + series
    }
}

/**
 * The single predicate browse, search, home rows and the guide use to drop locked content (R20).
 * Hidden while the parental session is locked; everything is hidden while the parental storage
 * is damaged ([ParentalControl.isDamaged]). Collect [changes] to re-run queries on lock/unlock.
 */
class ParentalContentFilter(private val parental: ParentalControl) {

    /** Emits whenever the result of the predicates may have changed (state or locked groups). */
    val changes: Flow<Any> = combine(parental.state, parental.lockedGroups) { state, groups -> state to groups }
        .distinctUntilChanged()

    fun isHidden(playlistId: Long, groupKey: String): Boolean = parental.isGroupHidden(playlistId, groupKey)

    fun isLiveGroupHidden(playlistId: Long, groupName: String): Boolean =
        isHidden(playlistId, ContentGroupKeys.live(groupName))

    /** A movie is hidden when its category is; an uncategorised item is hidden only when everything is. */
    fun isMovieHidden(playlistId: Long, categoryId: String?): Boolean =
        if (categoryId == null) parental.isDamaged else isHidden(playlistId, ContentGroupKeys.movies(categoryId))

    fun isSeriesHidden(playlistId: Long, categoryId: String?): Boolean =
        if (categoryId == null) parental.isDamaged else isHidden(playlistId, ContentGroupKeys.series(categoryId))

    /**
     * Keeps the items whose group is visible now. An item without a group key is kept unless the
     * parental storage is damaged.
     */
    fun <T> visible(items: List<T>, playlistId: (T) -> Long, groupKey: (T) -> String?): List<T> =
        items.filter { item ->
            val key = groupKey(item)
            if (key == null) !parental.isDamaged else !isHidden(playlistId(item), key)
        }
}
