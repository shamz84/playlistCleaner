package app.playlistplayer.core.player

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackWatchdogTest {

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.watchdog(onFire: () -> Unit) =
        PlaybackWatchdog(backgroundScope, { testScheduler.currentTime }, onFire)

    @Test
    fun firesOnceAfterTimeout_thenStaysDisarmed() = runTest {
        var fired = 0
        val dog = watchdog { fired++ }
        dog.arm(1_000)
        advance(999)
        assertThat(fired).isEqualTo(0)
        advance(1)
        assertThat(fired).isEqualTo(1)
        assertThat(dog.isArmed).isFalse()
        dog.kick()
        advance(10_000)
        assertThat(fired).isEqualTo(1)
    }

    @Test
    fun kickPostponesTheDeadline() = runTest {
        var fired = 0
        val dog = watchdog { fired++ }
        dog.arm(1_000)
        repeat(10) {
            advance(900)
            dog.kick()
        }
        assertThat(fired).isEqualTo(0)
        advance(1_000)
        assertThat(fired).isEqualTo(1)
    }

    @Test
    fun disarmPreventsFiring() = runTest {
        var fired = 0
        val dog = watchdog { fired++ }
        dog.arm(1_000)
        dog.disarm()
        advance(5_000)
        assertThat(fired).isEqualTo(0)
    }

    @Test
    fun rearmWithShorterTimeoutTakesEffect() = runTest {
        var fired = 0
        val dog = watchdog { fired++ }
        dog.arm(10_000)
        dog.arm(1_000)
        advance(1_000)
        assertThat(fired).isEqualTo(1)
        advance(20_000)
        assertThat(fired).isEqualTo(1)
    }
}
