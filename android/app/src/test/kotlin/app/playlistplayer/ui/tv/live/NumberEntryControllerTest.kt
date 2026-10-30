package app.playlistplayer.ui.tv.live

import app.playlistplayer.ui.tv.live.NumberEntryController.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NumberEntryControllerTest {

    private val controller = NumberEntryController()

    @Test
    fun `single digit commits after the 1_5 s timeout`() {
        assertThat(controller.onDigit(3, nowMillis = 1_000, channelCount = 50)).isNull()
        assertThat(controller.state.digits).isEqualTo("3")
        assertThat(controller.state.deadlineMillis).isEqualTo(2_500)

        assertThat(controller.onTimeout(nowMillis = 2_499, channelCount = 50)).isNull()
        assertThat(controller.state.isActive).isTrue()

        assertThat(controller.onTimeout(nowMillis = 2_500, channelCount = 50)).isEqualTo(Outcome.Jump(3))
        assertThat(controller.state.isActive).isFalse()
    }

    @Test
    fun `multi-digit entry extends the deadline and jumps to the full number`() {
        controller.onDigit(1, nowMillis = 0, channelCount = 150)
        controller.onDigit(2, nowMillis = 1_000, channelCount = 150)
        assertThat(controller.state.digits).isEqualTo("12")
        assertThat(controller.onTimeout(nowMillis = 1_600, channelCount = 150)).isNull() // first deadline passed, second not
        assertThat(controller.onTimeout(nowMillis = 2_500, channelCount = 150)).isEqualTo(Outcome.Jump(12))
    }

    @Test
    fun `out-of-range number is ignored`() {
        // Group of 80: "8" waits (80 exists), "85" is out of range and is ignored without a jump.
        assertThat(controller.onDigit(8, nowMillis = 0, channelCount = 80)).isNull()
        assertThat(controller.onDigit(5, nowMillis = 100, channelCount = 80)).isEqualTo(Outcome.Ignored("85"))
        assertThat(controller.state.isActive).isFalse()
        assertThat(controller.onTimeout(nowMillis = 5_000, channelCount = 80)).isNull()

        // "9" alone in a group of 5 can never fit.
        val small = NumberEntryController()
        assertThat(small.onDigit(9, nowMillis = 0, channelCount = 5)).isEqualTo(Outcome.Ignored("9"))
        assertThat(small.state.isActive).isFalse()
    }

    @Test
    fun `zero and numbers beyond the group size are ignored on commit`() {
        controller.onDigit(0, nowMillis = 0, channelCount = 20)
        assertThat(controller.onTimeout(nowMillis = 1_500, channelCount = 20)).isEqualTo(Outcome.Ignored("0"))

        controller.onDigit(2, nowMillis = 0, channelCount = 20)
        assertThat(controller.onDigit(5, nowMillis = 100, channelCount = 20)).isEqualTo(Outcome.Ignored("25"))
    }

    @Test
    fun `commits immediately when no further digit can fit`() {
        // Group of 120: "13" can only grow to 130+, so it commits at once; "12" waits (120 exists).
        assertThat(controller.onDigit(1, nowMillis = 0, channelCount = 120)).isNull()
        assertThat(controller.onDigit(2, nowMillis = 10, channelCount = 120)).isNull()
        controller.cancel()
        controller.onDigit(1, nowMillis = 0, channelCount = 120)
        assertThat(controller.onDigit(3, nowMillis = 10, channelCount = 120)).isEqualTo(Outcome.Jump(13))
        // Group of 9: any single digit commits at once.
        assertThat(controller.onDigit(4, nowMillis = 20, channelCount = 9)).isEqualTo(Outcome.Jump(4))
    }

    @Test
    fun `max digits commits`() {
        val c = NumberEntryController(maxDigits = 3)
        c.onDigit(1, 0, 100_000)
        c.onDigit(0, 1, 100_000)
        assertThat(c.onDigit(5, 2, 100_000)).isEqualTo(Outcome.Jump(105))
    }

    @Test
    fun `OK commits early and cancel drops the entry`() {
        controller.onDigit(4, nowMillis = 0, channelCount = 99)
        assertThat(controller.commit(channelCount = 99)).isEqualTo(Outcome.Jump(4))
        assertThat(controller.commit(channelCount = 99)).isNull()

        controller.onDigit(4, nowMillis = 0, channelCount = 99)
        controller.cancel()
        assertThat(controller.state.isActive).isFalse()
        assertThat(controller.onTimeout(nowMillis = 5_000, channelCount = 99)).isNull()
    }

    @Test
    fun `a digit after an expired deadline starts a new number`() {
        controller.onDigit(4, nowMillis = 0, channelCount = 99)
        // Timeout callback missed; the next digit arrives late.
        controller.onDigit(2, nowMillis = 2_000, channelCount = 99)
        assertThat(controller.state.digits).isEqualTo("2")
    }
}
