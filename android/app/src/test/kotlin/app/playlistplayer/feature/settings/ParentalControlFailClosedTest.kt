package app.playlistplayer.feature.settings

import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Damaged parental storage must fail closed: Locked, everything hidden, only "Reset app" recovers. */
class ParentalControlFailClosedTest {

    private val store = InMemorySecretSlotStore()
    private val clock = FakeClock()
    private val hasher = PinHasher(iterations = 1_000)

    private suspend fun load() = ParentalControl.load(store, clock, hasher, hashContext = EmptyCoroutineContext)

    /** PIN 1234 with one locked group, as persisted by a previous run. */
    private suspend fun persistedPinAndLock() {
        val control = load()
        control.setPin("1234")
        control.setLockedGroups(setOf(LockedGroup(1, ContentGroupKeys.live("Adults"))))
    }

    private suspend fun assertFailsClosed(control: ParentalControl) {
        assertThat(control.isDamaged).isTrue()
        assertThat(control.currentState()).isEqualTo(ParentalState.ResetRequired)
        assertThat(control.hasPin).isTrue()
        // Every group of every playlist is hidden, including ones never locked.
        assertThat(control.isGroupHidden(1, ContentGroupKeys.live("Adults"))).isTrue()
        assertThat(control.isGroupHidden(1, ContentGroupKeys.live("News"))).isTrue()
        assertThat(control.isGroupHidden(99, ContentGroupKeys.movies("7"))).isTrue()
        // No PIN (even the right one) unlocks it.
        assertThat(control.verify("1234")).isEqualTo(Authorization.ResetRequired)
        assertThat(control.authorize(ProtectedAction.RemovePlaylist, "1234")).isEqualTo(Authorization.ResetRequired)
        assertThat(control.authorize(ProtectedAction.ChangeGroupVisibility)).isEqualTo(Authorization.ResetRequired)
        assertThat(control.setPin("5678")).isEqualTo(ParentalResult.Rejected(Authorization.ResetRequired))
        assertThat(control.changePin("1234", "5678")).isEqualTo(ParentalResult.Rejected(Authorization.ResetRequired))
        assertThat(control.removePin("1234")).isEqualTo(ParentalResult.Rejected(Authorization.ResetRequired))
        assertThat(control.setLockedGroups(emptySet(), "1234")).isEqualTo(ParentalResult.Rejected(Authorization.ResetRequired))
        assertThat(control.isGroupHidden(1, ContentGroupKeys.live("News"))).isTrue()

        // "Reset app" is the only way out.
        control.resetAll()
        assertThat(control.isDamaged).isFalse()
        assertThat(control.currentState()).isEqualTo(ParentalState.NoPin)
        assertThat(control.isGroupHidden(1, ContentGroupKeys.live("Adults"))).isFalse()
        assertThat(store.slots).isEmpty()
        assertThat(load().currentState()).isEqualTo(ParentalState.NoPin)
    }

    @Test
    fun `unreadable pin slot is locked with everything hidden until reset`() = runTest {
        persistedPinAndLock()
        store.unreadable += ParentalControl.SLOT_PIN
        assertFailsClosed(load())
    }

    @Test
    fun `unreadable pin slot without other parental data still fails closed`() = runTest {
        store.slots[ParentalControl.SLOT_PIN] = "ciphertext"
        store.unreadable += ParentalControl.SLOT_PIN
        val control = load()
        assertThat(control.currentState()).isEqualTo(ParentalState.ResetRequired)
        assertThat(control.isGroupHidden(5, ContentGroupKeys.series("1"))).isTrue()
    }

    @Test
    fun `unreadable attempts slot fails closed`() = runTest {
        persistedPinAndLock()
        store.slots[ParentalControl.SLOT_ATTEMPTS] = "ciphertext"
        store.unreadable += ParentalControl.SLOT_ATTEMPTS
        assertFailsClosed(load())
    }

    @Test
    fun `unreadable locked groups slot fails closed`() = runTest {
        persistedPinAndLock()
        store.unreadable += ParentalControl.SLOT_GROUPS
        assertFailsClosed(load())
    }

    @Test
    fun `corrupt locked groups record fails closed instead of unlocking everything`() = runTest {
        persistedPinAndLock()
        store.slots[ParentalControl.SLOT_GROUPS] = "garbage"
        assertFailsClosed(load())
    }

    @Test
    fun `missing slots are a normal fresh install`() = runTest {
        val control = load()
        assertThat(control.isDamaged).isFalse()
        assertThat(control.currentState()).isEqualTo(ParentalState.NoPin)
        assertThat(control.isGroupHidden(1, ContentGroupKeys.live("Adults"))).isFalse()
    }

    @Test
    fun `damaged control hides everything from the content filter and its lists`() = runTest {
        persistedPinAndLock()
        store.unreadable += ParentalControl.SLOT_PIN
        val filter = ParentalContentFilter(load())
        val rows = listOf("News" to 1L, "Kids" to 2L)
        assertThat(filter.visible(rows, { it.second }, { ContentGroupKeys.live(it.first) })).isEmpty()
        assertThat(filter.visible(rows, { it.second }, { null })).isEmpty()
        assertThat(filter.isMovieHidden(1, null)).isTrue()
    }
}
