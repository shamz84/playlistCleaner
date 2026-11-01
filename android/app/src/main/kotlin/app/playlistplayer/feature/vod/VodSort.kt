package app.playlistplayer.feature.vod

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.playlistplayer.core.consolidation.VodKind
import java.text.Collator
import java.util.Locale

/** Orders of a section's full title list (R33). */
enum class VodSort { RECENTLY_ADDED, A_Z, RELEASE_YEAR }

/** The sort chosen for each section (playlist + kind), kept across launches. */
interface VodSortStore {
    suspend fun sort(kind: VodKind, playlistId: Long): VodSort?
    suspend fun setSort(kind: VodKind, playlistId: Long, sort: VodSort)
}

class InMemoryVodSortStore : VodSortStore {
    private val sorts = HashMap<Pair<VodKind, Long>, VodSort>()
    override suspend fun sort(kind: VodKind, playlistId: Long): VodSort? = synchronized(sorts) { sorts[kind to playlistId] }
    override suspend fun setSort(kind: VodKind, playlistId: Long, sort: VodSort) {
        synchronized(sorts) { sorts[kind to playlistId] = sort }
    }
}

/** [VodSortStore] in a private SharedPreferences file (not secret: only enum names per section). */
class SharedPreferencesVodSortStore(context: Context) : VodSortStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override suspend fun sort(kind: VodKind, playlistId: Long): VodSort? =
        prefs.getString(key(kind, playlistId), null)?.let { name -> VodSort.entries.firstOrNull { it.name == name } }

    override suspend fun setSort(kind: VodKind, playlistId: Long, sort: VodSort) {
        prefs.edit { putString(key(kind, playlistId), sort.name) }
    }

    /** Forgets a removed playlist's choices. */
    fun forgetPlaylist(playlistId: Long) {
        prefs.edit { VodKind.entries.forEach { remove(key(it, playlistId)) } }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private fun key(kind: VodKind, playlistId: Long) = "${kind.name}:$playlistId"

    private companion object {
        const val FILE = "vod_sort"
    }
}

/** Sorting of merged titles for the full list. Stable: ties keep provider order. */
object VodTitleSorter {
    private val collator: Collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }

    private val byName: Comparator<CatalogTitle> = Comparator { a, b -> collator.compare(a.title.title, b.title.title) }

    fun sort(titles: List<CatalogTitle>, sort: VodSort): List<CatalogTitle> = when (sort) {
        // Newest first; titles without an added time go last.
        VodSort.RECENTLY_ADDED -> titles.sortedWith(compareBy(nullsLast(reverseOrder())) { it.addedAt })
        VodSort.A_Z -> titles.sortedWith(byName)
        // Newest year first; unknown years last; A–Z within a year.
        VodSort.RELEASE_YEAR -> titles.sortedWith(compareBy<CatalogTitle, Int?>(nullsLast(reverseOrder())) { it.releaseYear }.then(byName))
    }
}
