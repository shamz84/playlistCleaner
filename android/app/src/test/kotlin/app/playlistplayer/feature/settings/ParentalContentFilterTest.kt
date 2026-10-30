package app.playlistplayer.feature.settings

import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Locked groups' channels and titles never reach browse, search, home rows or the guide while
 * locked (plan U12 integration scenario), using the one shared predicate.
 */
class ParentalContentFilterTest {

    private val store = InMemorySecretSlotStore()
    private val clock = FakeClock()

    private data class LiveRow(val playlistId: Long, val group: String, val name: String)
    private data class TitleRow(val playlistId: Long, val categoryId: String?, val name: String, val series: Boolean = false)

    private val live = listOf(
        LiveRow(1, "🇬🇧 UK", "BBC One"),
        LiveRow(1, "🔞 Adults", "Late Night"),
        LiveRow(2, "🔞 Adults", "Other playlist, same name"),
    )
    private val titles = listOf(
        TitleRow(1, "10", "Dune"),
        TitleRow(1, "66", "Locked film"),
        TitleRow(1, "66", "Locked series", series = true),
        TitleRow(1, null, "Uncategorised"),
    )

    private suspend fun control(): ParentalControl {
        val c = ParentalControl.load(store, clock, PinHasher(iterations = 1_000), hashContext = EmptyCoroutineContext)
        c.setPin("1234")
        c.setLockedGroups(
            setOf(
                LockedGroup(1, ContentGroupKeys.live("🔞 Adults")),
                LockedGroup(1, ContentGroupKeys.movies("66")),
                LockedGroup(1, ContentGroupKeys.series("66")),
            ),
        )
        c.lock()
        return c
    }

    private fun ParentalContentFilter.browseLive() = visible(live, { it.playlistId }, { ContentGroupKeys.live(it.group) })

    private fun ParentalContentFilter.searchTitles(query: String) = titles
        .filter { it.name.contains(query, ignoreCase = true) }
        .filterNot { if (it.series) isSeriesHidden(it.playlistId, it.categoryId) else isMovieHidden(it.playlistId, it.categoryId) }

    @Test
    fun lockedGroupsAreExcludedFromBrowseSearchHomeAndGuideUntilUnlocked() = runTest {
        val parental = control()
        val filter = ParentalContentFilter(parental)

        // Browse rail and the EPG grid (both list channels of visible groups).
        assertThat(filter.browseLive().map { it.name }).containsExactly("BBC One", "Other playlist, same name")
        assertThat(filter.isLiveGroupHidden(1, "🔞 Adults")).isTrue()
        // A lock is per playlist: the same group name elsewhere stays visible.
        assertThat(filter.isLiveGroupHidden(2, "🔞 Adults")).isFalse()
        // Search and home rows (titles).
        assertThat(filter.searchTitles("locked")).isEmpty()
        assertThat(filter.searchTitles("").map { it.name }).containsExactly("Dune", "Uncategorised")

        // Unlock for the session: everything shows; lock again hides it.
        assertThat(parental.verify("1234")).isEqualTo(Authorization.Allowed)
        val unlocked = filter.changes.first()
        assertThat(unlocked).isNotNull()
        assertThat(filter.browseLive()).hasSize(3)
        assertThat(filter.searchTitles("locked")).hasSize(2)

        parental.lock()
        assertThat(filter.browseLive()).hasSize(2)
        assertThat(filter.searchTitles("locked")).isEmpty()
    }

    @Test
    fun withoutPinNothingIsHidden() = runTest {
        val parental = ParentalControl.load(store, clock, PinHasher(iterations = 1_000), hashContext = EmptyCoroutineContext)
        val filter = ParentalContentFilter(parental)
        assertThat(filter.browseLive()).hasSize(3)
        assertThat(filter.searchTitles("")).hasSize(4)
    }

    @Test
    fun groupKeysIdentifyTheirKind() {
        assertThat(ContentGroupKeys.kindOf(ContentGroupKeys.live("UK"))).isEqualTo(ContentGroupKind.LIVE)
        assertThat(ContentGroupKeys.kindOf(ContentGroupKeys.movies("1"))).isEqualTo(ContentGroupKind.MOVIES)
        assertThat(ContentGroupKeys.kindOf(ContentGroupKeys.series("1"))).isEqualTo(ContentGroupKind.SERIES)
        assertThat(ContentGroupKeys.kindOf("other")).isNull()
    }
}
