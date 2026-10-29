package app.playlistplayer.feature.settings

import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ParentalControlTest {

    private val store = InMemorySecretSlotStore()
    private val clock = FakeClock()
    private val hasher = PinHasher(iterations = 1_000)

    private suspend fun newControl(unlockDurationMillis: Long? = null) = ParentalControl.load(
        store = store,
        clock = clock,
        hasher = hasher,
        unlockDurationMillis = unlockDurationMillis,
        hashContext = EmptyCoroutineContext,
    )

    /** A control with PIN 1234 set, then locked (as after a restart). */
    private suspend fun lockedWithPin(): ParentalControl {
        newControl().setPin("1234")
        return newControl()
    }

    private suspend fun ParentalControl.failTimes(n: Int, wrong: String = "9999"): Authorization {
        var last: Authorization = Authorization.Allowed
        repeat(n) { last = verify(wrong) }
        return last
    }

    // --- set / verify / change / remove ---

    @Test
    fun `no pin means NoPin state and verify allows`() = runTest {
        val control = newControl()
        assertThat(control.currentState()).isEqualTo(ParentalState.NoPin)
        assertThat(control.hasPin).isFalse()
        assertThat(control.verify("0000")).isEqualTo(Authorization.Allowed)
    }

    @Test
    fun `setting the first pin stores only a hash and unlocks the session`() = runTest {
        val control = newControl()
        assertThat(control.setPin("1234")).isEqualTo(ParentalResult.Done)
        assertThat(control.currentState()).isEqualTo(ParentalState.Unlocked(null))
        val stored = store.slots.getValue(ParentalControl.SLOT_PIN)
        assertThat(stored).doesNotContain("1234")
        assertThat(stored).startsWith("v1$")
    }

    @Test
    fun `invalid pin formats are rejected`() = runTest {
        val control = newControl()
        listOf("123", "123456789", "12a4", "", "１２３４").forEach {
            assertThat(control.setPin(it)).isEqualTo(ParentalResult.InvalidPinFormat)
        }
        assertThat(control.hasPin).isFalse()
    }

    @Test
    fun `restart starts locked and correct pin unlocks`() = runTest {
        val control = lockedWithPin()
        assertThat(control.currentState()).isEqualTo(ParentalState.Locked)
        assertThat(control.verify("1234")).isEqualTo(Authorization.Allowed)
        assertThat(control.currentState()).isEqualTo(ParentalState.Unlocked(null))
        control.lock()
        assertThat(control.currentState()).isEqualTo(ParentalState.Locked)
        assertThat(control.state.value).isEqualTo(ParentalState.Locked)
    }

    @Test
    fun `wrong pin is denied with remaining attempts`() = runTest {
        val control = lockedWithPin()
        assertThat(control.verify("0000")).isEqualTo(Authorization.Denied(2))
        assertThat(control.verify("0000")).isEqualTo(Authorization.Denied(1))
        assertThat(control.currentState()).isEqualTo(ParentalState.Locked)
    }

    @Test
    fun `blank pin is not counted as an attempt`() = runTest {
        val control = lockedWithPin()
        repeat(5) { assertThat(control.verify("")).isEqualTo(Authorization.PinRequired) }
        assertThat(control.verify("0000")).isEqualTo(Authorization.Denied(2))
    }

    @Test
    fun `timed unlock expires`() = runTest {
        newControl().setPin("1234")
        val control = newControl(unlockDurationMillis = 60_000)
        control.verify("1234")
        assertThat(control.currentState()).isEqualTo(ParentalState.Unlocked(clock.now + 60_000))
        clock.advance(60_000)
        assertThat(control.currentState()).isEqualTo(ParentalState.Locked)
    }

    @Test
    fun `change pin needs the current pin`() = runTest {
        val control = lockedWithPin()
        assertThat(control.changePin("0000", "5678"))
            .isEqualTo(ParentalResult.Rejected(Authorization.Denied(2)))
        assertThat(control.setPin("5678"))
            .isEqualTo(ParentalResult.Rejected(Authorization.PinRequired))
        assertThat(control.changePin("1234", "5678")).isEqualTo(ParentalResult.Done)

        val restarted = newControl()
        assertThat(restarted.verify("1234")).isEqualTo(Authorization.Denied(2))
        assertThat(restarted.verify("5678")).isEqualTo(Authorization.Allowed)
    }

    @Test
    fun `change pin is rejected even in an unlocked session without the current pin`() = runTest {
        val control = lockedWithPin()
        control.verify("1234")
        assertThat(control.changePin("", "5678"))
            .isEqualTo(ParentalResult.Rejected(Authorization.PinRequired))
        assertThat(control.setPin("5678", currentPin = "1234")).isEqualTo(ParentalResult.Done)
    }

    @Test
    fun `change pin with an invalid new pin does not consume an attempt`() = runTest {
        val control = lockedWithPin()
        assertThat(control.changePin("0000", "12")).isEqualTo(ParentalResult.InvalidPinFormat)
        assertThat(control.verify("0000")).isEqualTo(Authorization.Denied(2))
    }

    @Test
    fun `remove pin needs the current pin and clears locked groups`() = runTest {
        val control = lockedWithPin()
        control.verify("1234")
        control.setLockedGroups(setOf(LockedGroup(1, "Adults")))
        control.lock()

        assertThat(control.removePin("0000")).isEqualTo(ParentalResult.Rejected(Authorization.Denied(2)))
        assertThat(control.hasPin).isTrue()
        assertThat(control.removePin("1234")).isEqualTo(ParentalResult.Done)
        assertThat(control.currentState()).isEqualTo(ParentalState.NoPin)
        assertThat(control.isGroupLocked(1, "Adults")).isFalse()
        assertThat(store.slots).isEmpty()
        assertThat(control.changePin("1234", "5678")).isEqualTo(ParentalResult.NoPinSet)
        assertThat(control.removePin("1234")).isEqualTo(ParentalResult.NoPinSet)
    }

    // --- lockout ---

    @Test
    fun `three wrong pins lock out for 30 seconds even for the right pin`() = runTest {
        val control = lockedWithPin()
        val start = clock.now
        assertThat(control.failTimes(3)).isEqualTo(Authorization.LockedOut(start + 30_000))
        assertThat(control.lockedOutUntil()).isEqualTo(start + 30_000)

        clock.advance(29_999)
        assertThat(control.verify("1234")).isEqualTo(Authorization.LockedOut(start + 30_000))
        assertThat(control.authorize(ProtectedAction.EditPlaylist, "1234"))
            .isEqualTo(Authorization.LockedOut(start + 30_000))

        clock.advance(1)
        assertThat(control.lockedOutUntil()).isNull()
        assertThat(control.verify("1234")).isEqualTo(Authorization.Allowed)
    }

    @Test
    fun `lockout survives a restart on the same store`() = runTest {
        val control = lockedWithPin()
        val start = clock.now
        control.failTimes(3)

        val restarted = newControl()
        assertThat(restarted.lockedOutUntil()).isEqualTo(start + 30_000)
        assertThat(restarted.verify("1234")).isEqualTo(Authorization.LockedOut(start + 30_000))
    }

    @Test
    fun `failure count survives a restart`() = runTest {
        lockedWithPin().failTimes(2)
        val restarted = newControl()
        assertThat(restarted.verify("0000")).isInstanceOf(Authorization.LockedOut::class.java)
    }

    @Test
    fun `lockout escalates by doubling and caps at one hour`() = runTest {
        val control = lockedWithPin()
        val expected = listOf(30L, 60, 120, 240, 480, 960, 1920, 3600, 3600).map { it * 1_000 }
        for (duration in expected) {
            val result = control.failTimes(3)
            assertThat(result).isEqualTo(Authorization.LockedOut(clock.now + duration))
            clock.advance(duration)
        }
    }

    @Test
    fun `escalation level survives a restart`() = runTest {
        val control = lockedWithPin()
        control.failTimes(3)
        clock.advance(30_000)
        val restarted = newControl()
        assertThat(restarted.failTimes(3)).isEqualTo(Authorization.LockedOut(clock.now + 60_000))
    }

    @Test
    fun `success resets the counter and the escalation`() = runTest {
        val control = lockedWithPin()
        control.failTimes(3)
        clock.advance(30_000)
        control.failTimes(2)
        assertThat(control.verify("1234")).isEqualTo(Authorization.Allowed)
        assertThat(store.slots).doesNotContainKey(ParentalControl.SLOT_ATTEMPTS)

        assertThat(control.failTimes(2)).isEqualTo(Authorization.Denied(1))
        assertThat(control.verify("9999")).isEqualTo(Authorization.LockedOut(clock.now + 30_000))
    }

    @Test
    fun `lockout ends an unlocked session`() = runTest {
        val control = lockedWithPin()
        control.verify("1234")
        repeat(3) { control.authorize(ProtectedAction.RevealSourceReference, "0000") }
        assertThat(control.currentState()).isEqualTo(ParentalState.Locked)
    }

    @Test
    fun `corrupt attempt record fails closed at the cap`() = runTest {
        lockedWithPin()
        store.slots[ParentalControl.SLOT_ATTEMPTS] = "nonsense"
        val control = newControl()
        assertThat(control.verify("1234")).isEqualTo(Authorization.LockedOut(clock.now + 3_600_000))
    }

    @Test
    fun `clock moved backwards cannot extend a lockout beyond the cap`() = runTest {
        val control = lockedWithPin()
        control.failTimes(3)
        clock.advance(-10 * 3_600_000L)
        assertThat(control.lockedOutUntil()).isEqualTo(clock.now + 3_600_000)
    }

    @Test
    fun `attempt is persisted before the pin is compared`() = runTest {
        val control = lockedWithPin()
        val writes = mutableListOf<String?>()
        val recording = object : SecretSlotStore by store {
            override suspend fun put(name: String, value: String) {
                if (name == ParentalControl.SLOT_ATTEMPTS) writes += value
                store.put(name, value)
            }
        }
        val observed = ParentalControl.load(recording, clock, hasher, hashContext = EmptyCoroutineContext)
        observed.verify("1234")
        // The failure was written first, then cleared on success.
        assertThat(writes).hasSize(1)
        assertThat(store.slots).doesNotContainKey(ParentalControl.SLOT_ATTEMPTS)
        assertThat(control.hasPin).isTrue()
    }

    // --- protected actions ---

    @Test
    fun `every protected action is allowed without a pin set`() = runTest {
        val control = newControl()
        ProtectedAction.entries.forEach {
            assertThat(control.authorize(it)).isEqualTo(Authorization.Allowed)
        }
    }

    @Test
    fun `every protected action requires the pin when locked`() = runTest {
        val control = lockedWithPin()
        ProtectedAction.entries.forEach {
            assertThat(control.authorize(it)).isEqualTo(Authorization.PinRequired)
        }
        assertThat(control.authorize(ProtectedAction.RemovePlaylist)).isEqualTo(Authorization.PinRequired)
        assertThat(control.authorize(ProtectedAction.RemovePlaylist, "0000")).isEqualTo(Authorization.Denied(2))
        assertThat(control.authorize(ProtectedAction.RemovePlaylist, "1234")).isEqualTo(Authorization.Allowed)
    }

    @Test
    fun `unlocked session allows session actions but fresh pin actions still need the pin`() = runTest {
        val control = lockedWithPin()
        control.verify("1234")
        ProtectedAction.entries.forEach { action ->
            val expected = if (action.requiresFreshPin) Authorization.PinRequired else Authorization.Allowed
            assertThat(control.authorize(action)).isEqualTo(expected)
        }
        assertThat(control.authorize(ProtectedAction.RevealSourceReference, "1234")).isEqualTo(Authorization.Allowed)
    }

    @Test
    fun `locked out without a pin entry reports the lockout`() = runTest {
        val control = lockedWithPin()
        control.failTimes(3)
        assertThat(control.authorize(ProtectedAction.EditPlaylist))
            .isEqualTo(Authorization.LockedOut(clock.now + 30_000))
    }

    // --- locked groups ---

    @Test
    fun `locked groups are hidden until unlocked`() = runTest {
        val control = newControl()
        control.setPin("1234")
        val adults = LockedGroup(7, "XXX 🔞")
        assertThat(control.setLockedGroups(setOf(adults))).isEqualTo(ParentalResult.Done)
        control.lock()

        assertThat(control.isGroupLocked(7, "XXX 🔞")).isTrue()
        assertThat(control.isGroupHidden(7, "XXX 🔞")).isTrue()
        assertThat(control.isGroupHidden(8, "XXX 🔞")).isFalse()
        assertThat(control.isGroupHidden(7, "Sports")).isFalse()

        control.verify("1234")
        assertThat(control.isGroupLocked(7, "XXX 🔞")).isTrue()
        assertThat(control.isGroupHidden(7, "XXX 🔞")).isFalse()
    }

    @Test
    fun `wrong pins keep locked groups hidden`() = runTest {
        val control = newControl()
        control.setPin("1234")
        control.setLockedGroups(setOf(LockedGroup(1, "Adults")))
        control.lock()
        control.failTimes(3)
        assertThat(control.isGroupHidden(1, "Adults")).isTrue()
    }

    @Test
    fun `locked groups persist across restart with unusual names`() = runTest {
        val control = newControl()
        control.setPin("1234")
        val groups = setOf(
            LockedGroup(1, "ᴬᴰᵁᴸᵀ ✨ 18+"),
            LockedGroup(1, "tab\tand\nnewline"),
            LockedGroup(2, "Adults"),
        )
        control.setLockedGroups(groups)
        val restarted = newControl()
        assertThat(restarted.lockedGroups.value).isEqualTo(groups)
        groups.forEach { assertThat(restarted.isGroupHidden(it.playlistId, it.groupKey)).isTrue() }
    }

    @Test
    fun `changing locked groups without the pin is denied`() = runTest {
        val control = lockedWithPin()
        val groups = setOf(LockedGroup(1, "Adults"))
        assertThat(control.setLockedGroups(groups))
            .isEqualTo(ParentalResult.Rejected(Authorization.PinRequired))
        assertThat(control.setLockedGroups(groups, pin = "0000"))
            .isEqualTo(ParentalResult.Rejected(Authorization.Denied(2)))
        assertThat(control.isGroupLocked(1, "Adults")).isFalse()

        assertThat(control.setLockedGroups(groups, pin = "1234")).isEqualTo(ParentalResult.Done)
        assertThat(control.isGroupLocked(1, "Adults")).isTrue()
    }

    @Test
    fun `locking groups needs a pin to exist`() = runTest {
        val control = newControl()
        assertThat(control.setLockedGroups(setOf(LockedGroup(1, "Adults"))))
            .isEqualTo(ParentalResult.NoPinSet)
    }

    @Test
    fun `forgetting a playlist drops only its locks`() = runTest {
        val control = newControl()
        control.setPin("1234")
        control.setLockedGroups(setOf(LockedGroup(1, "A"), LockedGroup(2, "B")))
        control.lock()
        control.forgetPlaylist(1)
        assertThat(newControl().lockedGroups.value).containsExactly(LockedGroup(2, "B"))
    }

    // --- reset ---

    @Test
    fun `resetAll clears pin lockout and locked groups without a pin`() = runTest {
        val control = newControl()
        control.setPin("1234")
        control.setLockedGroups(setOf(LockedGroup(1, "Adults")))
        control.lock()
        control.failTimes(3)

        control.resetAll()
        assertThat(control.currentState()).isEqualTo(ParentalState.NoPin)
        assertThat(control.isGroupHidden(1, "Adults")).isFalse()
        assertThat(control.lockedOutUntil()).isNull()
        assertThat(store.slots).isEmpty()

        val restarted = newControl()
        assertThat(restarted.currentState()).isEqualTo(ParentalState.NoPin)
        assertThat(restarted.lockedGroups.value).isEmpty()
        assertThat(restarted.setPin("2468")).isEqualTo(ParentalResult.Done)
    }

    @Test
    fun `group codec round trips and tolerates junk`() {
        val groups = setOf(LockedGroup(3, "émoji 🎬"), LockedGroup(-1, ""))
        assertThat(ParentalControl.decodeGroups(ParentalControl.encodeGroups(groups))).isEqualTo(groups)
        assertThat(ParentalControl.decodeGroups("junk")).isEmpty()
        assertThat(ParentalControl.decodeGroups("v1\nbad line\n5\t%%%\n6\tQQ")).containsExactly(LockedGroup(6, "A"))
    }
}
