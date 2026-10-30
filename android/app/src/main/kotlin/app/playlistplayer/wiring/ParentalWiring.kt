package app.playlistplayer.wiring

import app.playlistplayer.core.consolidation.VodKind
import app.playlistplayer.feature.search.ContentVisibility
import app.playlistplayer.feature.settings.ParentalContentFilter
import app.playlistplayer.feature.settings.SecretSlotStore
import app.playlistplayer.feature.settings.SlotRead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The search / home / VOD / browse / guide visibility rules ([ContentVisibility]) over the U12
 * [ParentalContentFilter]: one predicate for every content path (R20).
 */
class ParentalContentVisibility(private val filter: ParentalContentFilter) : ContentVisibility {
    override fun isLiveGroupHidden(playlistId: Long, groupOrdinal: Int, groupName: String): Boolean =
        filter.isLiveGroupHidden(playlistId, groupName)

    override fun isVodCategoryHidden(playlistId: Long, kind: VodKind, categoryId: String): Boolean = when (kind) {
        VodKind.MOVIE -> filter.isMovieHidden(playlistId, categoryId)
        VodKind.SERIES -> filter.isSeriesHidden(playlistId, categoryId)
    }

    override val changes: Flow<Unit> = filter.changes.map { }
}

/** Hides everything: used until the parental state has loaded (fail closed). */
object HideAllVisibility : ContentVisibility {
    override fun isLiveGroupHidden(playlistId: Long, groupOrdinal: Int, groupName: String) = true
    override fun isVodCategoryHidden(playlistId: Long, kind: VodKind, categoryId: String) = true
    override fun isTitleHidden(playlistId: Long, kind: VodKind, categoryIds: Collection<String>) = true
}

/**
 * Slots that read as [SlotRead.Unreadable]: when the parental storage itself throws on load,
 * `ParentalControl.load` over this store starts damaged (ResetRequired, everything hidden), so a
 * storage failure can never fail open. Writes go to [delegate] so "Reset app" still clears it.
 */
class UnreadableSlots(private val delegate: SecretSlotStore) : SecretSlotStore {
    override suspend fun read(name: String): SlotRead = SlotRead.Unreadable
    override suspend fun put(name: String, value: String) = delegate.put(name, value)
    override suspend fun remove(name: String) = delegate.remove(name)
}
