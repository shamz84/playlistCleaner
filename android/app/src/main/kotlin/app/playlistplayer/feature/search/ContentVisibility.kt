package app.playlistplayer.feature.search

import app.playlistplayer.core.consolidation.VodKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Parental visibility used by search and home rows (R20). The settings unit implements it over
 * `ParentalControl.isGroupHidden(playlistId, groupKey)` (choosing the group key scheme: live
 * group name/ordinal, VOD category id). Calls are non-suspending so results filter
 * synchronously. Hidden content is filtered at query time and never counted, so no screen can
 * reveal that a locked match exists.
 *
 * VOD rule: a merged title is hidden when every category it appears in is hidden (a title with
 * any visible category is reachable from browse anyway); a title with no category is visible.
 */
interface ContentVisibility {
    fun isLiveGroupHidden(playlistId: Long, groupOrdinal: Int, groupName: String): Boolean

    fun isVodCategoryHidden(playlistId: Long, kind: VodKind, categoryId: String): Boolean

    /** Emits whenever visibility may have changed (lock/unlock, locked groups edited). */
    val changes: Flow<Unit> get() = flowOf(Unit)

    fun isTitleHidden(playlistId: Long, kind: VodKind, categoryIds: Collection<String>): Boolean =
        categoryIds.isNotEmpty() && categoryIds.all { isVodCategoryHidden(playlistId, kind, it) }

    companion object {
        val ALL_VISIBLE: ContentVisibility = object : ContentVisibility {
            override fun isLiveGroupHidden(playlistId: Long, groupOrdinal: Int, groupName: String) = false
            override fun isVodCategoryHidden(playlistId: Long, kind: VodKind, categoryId: String) = false
        }
    }
}
